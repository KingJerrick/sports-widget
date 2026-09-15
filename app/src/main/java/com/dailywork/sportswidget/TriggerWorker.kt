package com.dailywork.sportswidget

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 「抓取」这个按键的全部后台逻辑：让 GitHub 现在就去抓一次。
 *
 * ── 为什么不和 [RefreshWorker] 合成一个 ────────────────────────────────
 * 两个按键的边界就是「谁去 GitHub」：
 *   · 刷新（[RefreshWorker]）—— 手机 GET 一份现成的 calendar.json，不碰 Actions
 *   · 抓取（这里）           —— 让 Actions 现在开跑，**不拉数据**
 *
 * 以前这两件事揉在同一个按键里：点一下先触发、再拉一次、再排一个「2 分钟后
 * 自动回来取」的延时任务。最后那个延时任务靠 WorkManager 的 setInitialDelay
 * 加网络约束，而 Doze 和各家的省电策略想推迟就推迟，实测经常根本不执行 ——
 * 表现是「点了触发、GitHub 也真跑了，但手机上永远没自动拿到新数据」，
 * 不报错、不崩，很难往这个方向想。
 *
 * 现在把「什么时候回来取」交回给人：点「抓取」→ 等一两分钟 → 自己点「刷新」。
 *
 * 发请求本身在 [GithubDispatch]，这里只管「排期 + 把结果写到界面上」。
 */
class TriggerWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // 失败（没配 token / 权限不够 / 网络不通）是常态，**刻意不当错误重试** ——
        // 重试只会再发一次 POST，而这些原因重试一百次也还是同一个结果。
        // 结论已经写进 notice、显示在小组件状态行上了，用户看得见。
        TriggerRunner.run(applicationContext)
        return Result.success()
    }
}

/**
 * 触发一次，并把结果落到小组件状态行上。
 *
 * App 里的「触发 GitHub 抓取」和小组件上的「抓取」都走这里。两处各写一份的话，
 * 很容易变成「App 里点了有提示、桌面上点了没反应」，而桌面恰恰是这个按键的主战场。
 */
object TriggerRunner {

    /** 点完立刻显示在状态行上的占位，几秒后会被真实结果覆盖。 */
    const val PENDING = "触发中…"

    private val STAMP_FMT = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    /**
     * 发 HTTP 请求，所以**不要在主线程上直接调** —— 两个调用方（[TriggerWorker]
     * 和 MainActivity）都已经在协程里，这里再自己切一次 IO 就都安全了。
     */
    suspend fun run(context: Context): TriggerResult {
        val result = withContext(Dispatchers.IO) { GithubDispatch.trigger(context) }

        // 成功也写一条：桌面上的「抓取」按钮不像 App 里那样有个按钮旁的结果区，
        // 状态行是它唯一能说话的地方。时间戳让人能对上一次刷新是什么时候。
        val text = if (result.ok) {
            "✅ 已触发 ${Instant.now().atZone(ZoneId.systemDefault()).format(STAMP_FMT)}"
        } else {
            "❌ ${result.short}"
        }
        Prefs.setNotice(context, Notice(text, failed = !result.ok))
        WidgetProvider.updateAll(context)

        return result
    }
}

/**
 * 「抓取」的排期。
 *
 * 和 [RefreshScheduler] 分开、用各自的名字，是因为两者毫不相干：
 * 一个去 GitHub 要数据，一个让 GitHub 干活。取消其中一个不该影响另一个，
 * 用户连着点两下不同的按钮也不该互相顶掉。
 */
object TriggerScheduler {

    private const val WORK = "sports_trigger"

    private val networkConstraint =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /**
     * REPLACE：连着点两下「抓取」时，取消还在排队的那次、重新排一个。
     *
     * 这里可以放心用 REPLACE（不像以前那个延时任务必须躲开它）—— 没有任何东西
     * 依赖它的时序，后一次触发本来就覆盖前一次的效果。
     */
    fun schedule(context: Context) {
        val work = OneTimeWorkRequestBuilder<TriggerWorker>()
            .setConstraints(networkConstraint)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, work)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }
}
