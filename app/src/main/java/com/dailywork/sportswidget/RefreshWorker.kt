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

    override suspend fun doWork(): Result = try {
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

        // 先把图标下下来再重画。
        //
        // 顺序很重要：图标只在内存里查（RemoteViewsFactory 跑在 binder 线程，
        // 那边不能联网），所以必须先 warmUp 把图放进内存，再让小组件重画，
        // 否则第一次刷新出来的是字母块，要等下一次刷新才变成真队标。
        //
        // 单个图标失败不影响其余（LogoStore 里逐个 try），最坏就是那一个用字母块。
        // 放 IO 调度器上：这里是阻塞式的网络 + 读盘，Default 调度器不该被这么占。
        withContext(Dispatchers.IO) { LogoStore.warmUp(context, parsed.logos.values) }

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

    private val networkConstraint =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(context: Context, immediate: Boolean = false) {
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
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONESHOT_WORK, ExistingWorkPolicy.REPLACE, oneShot)
        }
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
        WorkManager.getInstance(context).cancelUniqueWork(ONESHOT_WORK)
    }
}
