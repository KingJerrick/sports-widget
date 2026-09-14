package com.dailywork.sportswidget

import android.content.Context

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

    /** 上一次成功的结果，用于刷新失败时继续展示旧值。 */
    fun loadData(ctx: Context): CalendarData =
        CalendarData.fromJson(sp(ctx).getString(K_DATA, null))

    fun saveData(ctx: Context, data: CalendarData) =
        sp(ctx).edit().putString(K_DATA, CalendarData.toJson(data)).apply()
}
