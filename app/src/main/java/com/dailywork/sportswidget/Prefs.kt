package com.dailywork.sportswidget

import android.content.Context

/**
 * 小组件状态行上那条「上一次触发抓取的结果」。
 *
 * 它**盖住**平时显示的「数据生成时间」。多这一条是因为：桌面上的「抓取」按键
 * 点完之后，GitHub 那边要跑一两分钟才有新数据，这期间小组件上要是毫无变化，
 * 用户根本无从判断触发到底生效没有 —— 而这正是把两个按键拆开之后最容易迷路的地方。
 *
 * 生命周期是「留到下一次拉数据为止」：用户来取数了，这条提示的使命就结束了，
 * 状态行该回去显示数据生成时间。清掉它的地方只有一个 —— [CalendarRefresher.refresh]
 * 的开头，而那个函数有两个入口：点「刷新」，以及每 6 小时的周期任务。
 *
 * 所以这条提示**最长活不过一个刷新周期**。这一点很要紧：状态行那个「数据生成时间」
 * 是发现「后端停了」的唯一途径（见 WidgetRenderer.applyStatus），要是被一条提示
 * 无限期盖住，等于把这个信号也一起盖没了。有周期任务兜底，它盖不住。
 *
 * 也正因如此，刻意**不做超时自动消失**：小组件只在被重画时才有机会更新，
 * 没有任何东西保证「过一会儿」会被重画，做超时也只会在下一次重画时突然跳一下，
 * 反而更难理解。真正在管这件事的是上面那个「refresh 时清掉」。
 */
data class Notice(val text: String, val failed: Boolean)

/**
 * 所有本地持久化都收在这里，方便排查。
 *
 * 沿用 deepseek-widget / Prefs.kt 的约定：`object` 单例 + SharedPreferences（不用 DataStore），
 * 键名统一 `K_` 前缀，取值函数自带兜底和钳制、调用方不用二次防御。
 */
object Prefs {

    private const val FILE = "sports_widget"

    private const val K_ENDPOINT = "endpoint"
    private const val K_INTERVAL = "interval_minutes"
    private const val K_DATA = "cache"
    private const val K_GH_TOKEN = "gh_token"
    private const val K_NOTICE = "notice"
    private const val K_NOTICE_FAILED = "notice_failed"

    /** WorkManager 的周期任务最短就是 15 分钟，比这更短没有意义。 */
    const val MIN_INTERVAL_MINUTES = 60

    /**
     * 默认 6 小时，跟后端抓取的节奏一致。
     *
     * 刷得更勤并不会更新鲜 —— 后端每 6 小时才重新生成一次 calendar.json，
     * 中间刷多少次拿到的都是同一份。省电优先。
     */
    const val DEFAULT_INTERVAL_MINUTES = 360

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * 用户自定义的数据地址。留空时用 [CalendarClient.DEFAULT_ENDPOINTS]。
     *
     * 留这个口子是因为 cdn.jsdelivr.net 在国内时不时抽风 —— 用户挂了代理或者
     * 自建了镜像时可以直接填自己的地址，不用改代码重新打包。
     */
    fun getEndpoint(ctx: Context): String =
        sp(ctx).getString(K_ENDPOINT, "").orEmpty().trim()

    fun setEndpoint(ctx: Context, v: String) =
        sp(ctx).edit().putString(K_ENDPOINT, v.trim()).apply()

    fun getIntervalMinutes(ctx: Context): Int =
        sp(ctx).getInt(K_INTERVAL, DEFAULT_INTERVAL_MINUTES).coerceAtLeast(MIN_INTERVAL_MINUTES)

    fun setIntervalMinutes(ctx: Context, v: Int) =
        sp(ctx).edit().putInt(K_INTERVAL, v.coerceAtLeast(MIN_INTERVAL_MINUTES)).apply()

    /**
     * 用来让后端「现在就去抓一次」的 GitHub token。留空就是不启用这个功能
     * （刷新仍然可用，只是走原来那条路：拉现成的 calendar.json）。
     *
     * ⚠️ 明文存在 app 私有的 SharedPreferences 里，**不进仓库也不进 APK**。
     *
     * 这是唯一可行的位置：workflow dispatch 必须认证，而放进构建期配置会被
     * 打进 APK（谁装了谁就有），放进仓库就直接公开了。存在设备上，泄漏面
     * 就只剩这一台手机；manifest 里 allowBackup="false"，系统备份也不会带走它。
     *
     * 建议用 fine-grained PAT：只授权这一个仓库、只给 Actions 读写。
     */
    fun getGithubToken(ctx: Context): String =
        sp(ctx).getString(K_GH_TOKEN, "").orEmpty().trim()

    fun setGithubToken(ctx: Context, v: String) =
        sp(ctx).edit().putString(K_GH_TOKEN, v.trim()).apply()

    /** 上一次「抓取」的结果，没有（或已清掉）返回 null。 */
    fun getNotice(ctx: Context): Notice? {
        val text = sp(ctx).getString(K_NOTICE, null)?.takeIf { it.isNotBlank() } ?: return null
        return Notice(text, sp(ctx).getBoolean(K_NOTICE_FAILED, false))
    }

    /** 传 null 就是清掉。两半一起写，免得留下「有文案但颜色是旧的」的中间态。 */
    fun setNotice(ctx: Context, notice: Notice?) =
        sp(ctx).edit()
            .putString(K_NOTICE, notice?.text)
            .putBoolean(K_NOTICE_FAILED, notice?.failed == true)
            .apply()

    /** 上一次成功的结果，用于刷新失败时继续展示旧值。 */
    fun loadData(ctx: Context): CalendarData =
        CalendarData.fromJson(sp(ctx).getString(K_DATA, null))

    fun saveData(ctx: Context, data: CalendarData) =
        sp(ctx).edit().putString(K_DATA, CalendarData.toJson(data)).apply()
}
