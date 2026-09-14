package com.dailywork.sportswidget

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * 后台联网拉数据。WorkManager 保证进程被杀后也会按周期唤醒。
 *
 * 逻辑很短，真正的活在 [CalendarRefresher.refresh] 里 —— Worker 只是个壳子。
 */
class RefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    companion object {
        /**
         * 「这次是用户手动点的，顺便让 GitHub 也去抓一趟」。
         *
         * 小组件顶栏那个 ↻ 没法像 App 里那样直接把结果告诉用户，所以触发
         * 只能放在这里做。失败也不特殊处理 —— 那就退化成「只拉现成的」，
         * 也就是以前的行为，不会更糟。
         */
        const val KEY_TRIGGER_REMOTE = "trigger_remote"
    }

    override suspend fun doWork(): Result = try {
        // 用户手动点的先让后端开跑。放在拉数据**之前** —— 反过来的话，
        // 这次拉到的还是触发前那份旧数据，等于白点。
        if (inputData.getBoolean(KEY_TRIGGER_REMOTE, false)) {
            withContext(Dispatchers.IO) {
                val err = GithubDispatch.trigger(applicationContext)
                if (err == null) {
                    // 后端要跑一两分钟，排一个稍后回来取数的任务
                    RefreshScheduler.scheduleDelayedFetch(applicationContext)
                } else {
                    // 没配 token 是常态，不当错误处理；这里只记一句，界面上不打扰
                    println("触发远程抓取失败：$err")
                }
            }
        }

        // ⚠️ 必须看返回值。refresh() 把网络异常都自己吞了（它要做三级降级），
        // 只靠返回值告诉外面「这次到底刷成没有」——无视它的话，网络全挂也会被
        // 当成 success 上报，WorkManager 不会按退避策略重试，
        // 只能干等下一个周期（默认 6 小时）。Doze + 国内 CDN 不稳的场景下，
        // 这会明显放大「小组件一整天不更新」的概率。
        if (CalendarRefresher.refresh(applicationContext)) Result.success() else Result.retry()
    } catch (e: Exception) {
        // 这里只有 Prefs 读写 / 重画之类的意外异常
        Result.retry()
    }
}

/**
 * 「拉 -> 解析 -> 存 -> 重画」这一整套。
 *
 * 单独抽出来是因为它有三个调用方：Worker（定时）、用户点 ↻（一次性任务）、
 * App 里的「保存并刷新」。放一处省得三份逻辑各写各的。
 */
object CalendarRefresher {

    /**
     * @return 真的拿到新数据了返回 true。false 表示这次没刷成，**旧数据原样保留**。
     */
    suspend fun refresh(context: Context): Boolean {
        val prev = Prefs.loadData(context)
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()

        val raw = try {
            withContext(Dispatchers.IO) { CalendarClient.fetchRaw(context) }
        } catch (e: Exception) {
            // 网络全挂了。如果本地一点数据都没有，退到 APK 内置的快照 ——
            // 只在「没缓存」时才用，因为它可能已经是几个月前打包进去的了，
            // 有缓存的话缓存一定更新。
            if (prev.events.isEmpty()) {
                val bundled = CalendarClient.loadBundled(context)
                val fromAsset = bundled?.let { CalendarParser.parse(it, now, zone) }
                if (fromAsset != null && fromAsset.events.isNotEmpty()) {
                    Prefs.saveData(context, fromAsset.copy(error = "离线，用 APK 内置快照"))
                    WidgetProvider.updateAll(context)
                    return false
                }
            }
            Prefs.saveData(context, prev.copy(error = e.message ?: "网络不可用"))
            WidgetProvider.updateAll(context)
            return false
        }

        val parsed = CalendarParser.parse(raw, now, zone)
        if (parsed == null) {
            // 拿到了东西但解析不了。分两种情况给不同的话，否则用户完全不知道该改什么。
            val msg = if (CalendarClient.looksLikeHtml(raw)) {
                "拉到的是网页不是数据（${raw.length / 1000}KB 的 HTML）—— 检查数据地址，" +
                        "要填 raw 地址或 jsDelivr 地址，不能填 github.com 的网页地址"
            } else {
                "返回内容不是赛程数据，检查数据地址"
            }
            Prefs.saveData(context, prev.copy(error = msg))
            WidgetProvider.updateAll(context)
            return false
        }

        // 成功：整份替换，错误清空
        Prefs.saveData(context, parsed.copy(error = null))

        // 把用户上传的图标读进内存再重画。
        //
        // 图标只在内存里查 —— RemoteViewsFactory 跑在 binder 线程上，
        // 那边每次取图都读盘太慢。所以先 warmUp 再让小组件重画。
        withContext(Dispatchers.IO) { LogoStore.warmUp(context) }

        WidgetProvider.updateAll(context)
        return true
    }
}

/**
 * 排期。周期任务和一次性任务用不同的 unique name，
 * 这样「点一下 ↻」不会把周期任务的排期冲掉。
 */
object RefreshScheduler {

    private const val PERIODIC_WORK = "sports_periodic_refresh"
    private const val ONESHOT_WORK = "sports_oneshot_refresh"

    /**
     * 触发 GitHub 抓取之后，「过一会儿再来取一次」的那个任务。
     *
     * ⚠️ **必须和 ONESHOT_WORK 用不同的名字。** 两个都是「去刷新」，
     * 共用名字的话会互相顶掉：ONESHOT_WORK 那边用的是 ExistingWorkPolicy.REPLACE，
     * 用户再点一次刷新，就会把这个等着两分钟后去取数的任务一并取消 ——
     * 表现是「点了触发、GitHub 也真的跑了，但手机上永远没拿到新数据」，
     * 而且不报错、不崩，很难往这个方向想。
     */
    private const val DELAYED_WORK = "sports_delayed_refresh"

    /**
     * 触发抓取之后隔多久回来取。
     *
     * 一次 Actions（checkout + 抓七个源 + commit）实测一两分钟。
     * 两分钟偏保守：跑得快的话这次拿到的还是旧数据 —— 那也没关系，
     * 周期任务和下一次手动刷新都会再取一遍。
     */
    private const val DELAY_MINUTES = 2L

    private val networkConstraint =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /**
     * @param immediate      立刻排一次性刷新（用户点了 ↻ 或者保存设置）
     * @param triggerRemote  这次是**用户手动点的**，顺便让 GitHub 也去抓一趟。
     *                       周期任务永远是 false —— 后端本来就每 6 小时自己跑，
     *                       再触发一遍只是白烧 Actions 配额。
     */
    fun schedule(context: Context, immediate: Boolean = false, triggerRemote: Boolean = false) {
        val interval = Prefs.getIntervalMinutes(context)

        val periodic = PeriodicWorkRequestBuilder<RefreshWorker>(interval.toLong(), TimeUnit.MINUTES)
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        // UPDATE 让改完间隔立刻生效，又不会丢掉已有排期
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, periodic)

        if (immediate) {
            val oneShot = OneTimeWorkRequestBuilder<RefreshWorker>()
                .setConstraints(networkConstraint)
                .setInputData(workDataOf(RefreshWorker.KEY_TRIGGER_REMOTE to triggerRemote))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONESHOT_WORK, ExistingWorkPolicy.REPLACE, oneShot)
        }
    }

    /**
     * 排一个「过两分钟再来取一次」的任务。触发 GitHub 抓取之后调它 ——
     * 那边刚被叫醒，现在去取拿到的还是旧数据。
     *
     * REPLACE 而不是 KEEP：连着点两次触发时以最后一次为准重新计时，
     * 而不是让一个已经走了大半的计时继续跑下去。
     */
    fun scheduleDelayedFetch(context: Context) {
        val work = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setConstraints(networkConstraint)
            .setInitialDelay(DELAY_MINUTES, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(DELAYED_WORK, ExistingWorkPolicy.REPLACE, work)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
        WorkManager.getInstance(context).cancelUniqueWork(ONESHOT_WORK)
        WorkManager.getInstance(context).cancelUniqueWork(DELAYED_WORK)
    }
}
