package com.dailywork.sportswidget

import android.content.Context
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 拿 calendar.json。
 *
 * ── 为什么要多个端点 ────────────────────────────────────────────────────
 * `cdn.jsdelivr.net` 在国内长期有 DNS 污染 / 丢包问题，**这是「小组件不刷新」
 * 最可能的真实原因，跟渲染代码一点关系都没有**。所以不能只写一个地址。
 *
 * 三级兜底：网络（逐个端点试）-> 上次成功的本地缓存 -> APK 内置的 assets 快照。
 * 三级都拿不到时**保留已有内容**并把错误写进状态行，绝不清空。
 */
object CalendarClient {

    private const val TIMEOUT_CONNECT_MS = 10_000
    private const val TIMEOUT_READ_MS = 15_000

    /** 和 tools/fetch_calendar.py 里的 BROWSER_UA 一个道理：部分 CDN 对没有 UA 的请求直接 403。 */
    private const val UA = "Mozilla/5.0 (Linux; Android 13) sports-widget/1.0"

    /**
     * 依次尝试，第一个成功就停。
     *
     * 刻意只放三个：每多一个，最坏情况（全部超时）就多 25 秒，
     * 而 WorkManager 的后台任务是有时间预算的。三个足够覆盖「jsDelivr 挂了」这种情况。
     */
    val DEFAULT_ENDPOINTS = listOf(
        "https://cdn.jsdelivr.net/gh/KingJerrick/sports-widget@main/data/calendar.json",
        "https://raw.gitmirror.com/KingJerrick/sports-widget/main/data/calendar.json",
        // 这个在国内直连基本不通，但挂了代理就能用 —— 留着给「上面两个都挂」的时候
        "https://raw.githubusercontent.com/KingJerrick/sports-widget/main/data/calendar.json",
    )

    /** 把当前该用的端点列出来（用户填了自定义地址就只用那个）。 */
    fun endpoints(context: Context): List<String> {
        val custom = Prefs.getEndpoint(context)
        return if (custom.isNotBlank()) listOf(custom) else DEFAULT_ENDPOINTS
    }

    /**
     * 拉一份最新的原始 JSON。
     *
     * @return 响应体原文；成功但内容不合法时由调用方（[CalendarParser]）判断。
     * @throws IOException 所有端点都失败，异常消息里带了每个端点的失败原因，
     *                     会一路冒泡到小组件状态行和 App 自检页 —— 排查时不用猜。
     */
    fun fetchRaw(context: Context): String {
        val urls = endpoints(context)
        val errors = mutableListOf<String>()

        for (url in urls) {
            try {
                return httpGet(url)
            } catch (e: Exception) {
                // 只留域名和原因，完整 URL 太长，状态行放不下
                errors += "${hostOf(url)}：${e.message}"
            }
        }
        throw IOException(errors.joinToString("；"))
    }

    /**
     * APK 内置的兜底快照。由 tools/fetch_calendar.py 顺带写进 assets/，
     * 打包时就固定在 APK 里了 —— 所以它可能有点旧，但总比空白强。
     */
    fun loadBundled(context: Context): String? = runCatching {
        context.assets.open("calendar.json").bufferedReader().use { it.readText() }
    }.getOrNull()

    // ── 内部实现 ──────────────────────────────────────────────────────

    private fun hostOf(url: String): String =
        runCatching { URL(url).host }.getOrDefault(url)

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_CONNECT_MS
            readTimeout = TIMEOUT_READ_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("User-Agent", UA)
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (code !in 200..299) {
                val hint = when (code) {
                    403, 404 -> "地址不对或仓库还没公开"
                    429 -> "请求过于频繁"
                    else -> "HTTP $code"
                }
                throw IOException("$hint（$code）")
            }
            if (body.isBlank()) throw IOException("响应为空")
            return body
        } finally {
            conn.disconnect()
        }
    }
}
