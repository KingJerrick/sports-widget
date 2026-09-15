package com.dailywork.sportswidget

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

/**
 * 一次触发的结局。
 *
 * 同时带长短两版文案，是因为两个显示位置能放的字数差一个数量级：
 *   · 小组件顶栏那一行总共只有 50dp 上下，[error] 那种带排查提示的长句塞不进去，
 *     塞进去也会把旁边的七天条挤没（状态行是 wrap_content）。
 *   · App 里能整段摊开，用户也正是在那里才能动手修（填 token、改地址）。
 *
 * 让触发这一层直接把两版都给出来，比在调用点按字符串长度截断可靠 ——
 * 截断出来的半句话往往是「GitHub 说找不到这个仓库」这种最关键的半句。
 */
data class TriggerResult(
    /** 成功是 null；失败是给 App 看的完整说明，人话 + 排查方向。 */
    val error: String?,
    /** 给小组件状态行用的极短版本（4~8 字），成功失败都有。 */
    val short: String,
) {
    val ok: Boolean get() = error == null
}

/**
 * 让后端「现在就去抓一次」。
 *
 * ── 为什么需要它 ──────────────────────────────────────────────────────
 * 后端每 6 小时才抓一次，所以「刷新」按钮拉到的常常还是同一份数据 ——
 * 连点十次也还是那几个小时前生成的那份。这个文件让 App 能主动让后端开跑，
 * 跑完再回来取，手动刷新才真的「新」。
 *
 * 触发之后**不在这里等结果**，也**不负责安排什么时候回来取** ——
 * 一次 Actions 跑完要一两分钟，而「过两分钟自动回来取」这件事在手机上做不到：
 * 它要靠 WorkManager 的 setInitialDelay 加网络约束，Doze 和各家的省电策略
 * 想推迟就推迟，表现是「触发了、GitHub 也真跑了，但手机永远没自动拿到新数据」。
 * 所以取数这件事交回给人：点完「抓取」，过两分钟自己点「刷新」（见 README）。
 *
 * ── token 存哪 ───────────────────────────────────────────────────────
 * 只存在设备上，见 [Prefs.getGithubToken]。不进仓库、不进 APK ——
 * 为什么这是唯一可行的位置，写在那个字段的注释里。
 *
 * ── 这是全项目唯一一处 POST ──────────────────────────────────────────
 * [CalendarClient] 那边是无认证的 GET。这里要发带 Authorization 的 POST，
 * 两件事的失败模式也完全不同（那边是「地址不通」，这边是「没配 / 没权限」），
 * 所以单独一个文件，别混进 CalendarClient。
 */
object GithubDispatch {

    /** 要触发的 workflow 文件名，必须和 .github/workflows/ 下的文件名一模一样。 */
    private const val WORKFLOW = "update-calendar.yml"

    /** 分发到哪个分支。抓取脚本和产物都在默认分支上。 */
    private const val REF = "main"

    private const val API = "https://api.github.com"

    /**
     * GitHub 认的几种数据地址写法，都从中抠出 `owner/repo`。
     *
     * 顺序有讲究：jsDelivr 先试（它的地址里不含 github.com，放后面也一样，
     * 但放前面更直观）。最后那条 `github\.com/` 不会误伤 `raw.githubusercontent.com` ——
     * 那是 `githubusercontent.com`，中间没有点号分割的 `github.com/`。
     *
     * 末尾的仓库名要用 `[^/]+` 而不是 `[^/@]+`：github 那条要能匹配到
     * `github.com/{owner}/{repo}/blob/...` 里的 repo，@ 是 jsDelivr 才有的。
     */
    private val REPO_RES = listOf(
        // https://cdn.jsdelivr.net/gh/{owner}/{repo}@main/data/calendar.json
        Regex("""jsdelivr\.net/gh/([^/]+)/([^/]+)"""),
        Regex("""raw\.githubusercontent\.com/([^/]+)/([^/]+)"""),
        Regex("""raw\.gitmirror\.com/([^/]+)/([^/]+)"""),
        // https://github.com/{owner}/{repo}/blob/main/... （CalendarClient 也会先把它规整掉）
        Regex("""github\.com/([^/]+)/([^/]+)"""),
    )

    /**
     * 从数据地址里把 `owner/repo` 抠出来，抠不出返回 null。
     *
     * 刻意**不新增一个「仓库地址」输入框** —— 用户已经填过数据地址了，
     * 仓库信息本来就在里面。猜一个错的仓库去 POST 比直接说「认不出」更糟，
     * 所以推不出来就返回 null，让调用方给一句能看懂的提示。
     */
    fun repoOf(endpoint: String): String? {
        val url = endpoint.trim().substringBefore('?').substringBefore('#')
        for (re in REPO_RES) {
            val m = re.find(url) ?: continue
            val (owner, repo) = m.destructured
            // jsDelivr 的仓库名后面缀着 @main 这类 ref，要去掉；
            // 有人也会粘 .git 结尾的地址
            val clean = repo.substringBefore('@').removeSuffix(".git")
            if (owner.isNotBlank() && clean.isNotBlank()) return "$owner/$clean"
        }
        return null
    }

    /**
     * 触发一次抓取。
     *
     * **不抛异常**：调用点在协程里，而失败是常态（没配 token、token 过期、
     * 没给够权限、网络不通），每一种都得翻译成人话 —— 抛异常的话最后只会显示
     * 一个 `IOException`，用户没法据此做任何事。
     *
     * 返回的 [TriggerResult] 长短两版都给，理由见那个类的说明。
     */
    fun trigger(ctx: Context): TriggerResult {
        val token = Prefs.getGithubToken(ctx)
        if (token.isBlank()) {
            return TriggerResult(
                error = "没配 GitHub token。想用「抓取」得先在设置里填上，见下面的说明。",
                short = "没配 token",
            )
        }

        // 用当前生效的第一个端点推仓库：用户填了自定义地址就推它，
        // 没填就是默认的 jsDelivr 那条
        val endpoint = CalendarClient.endpoints(ctx).firstOrNull().orEmpty()
        val repo = repoOf(endpoint) ?: return TriggerResult(
            error = "从数据地址里认不出是哪个仓库，触发不了抓取：\n$endpoint",
            short = "认不出仓库",
        )

        var conn: HttpURLConnection? = null
        return try {
            // ⚠️ 必须 openConnection() 之后再转 —— 别写成 `URL(...) as HttpURLConnection`。
            // Kotlin 的 `as` 是不检查的转换，那样写**编译期完全过得去**，
            // 到运行期才抛「java.net.URL cannot be cast to java.net.HttpURLConnection」。
            // CalendarClient.httpGet 里也是这个写法，保持一致。
            val c = URL("$API/repos/$repo/actions/workflows/$WORKFLOW/dispatches")
                .openConnection() as HttpURLConnection
            conn = c
            c.requestMethod = "POST"
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.doOutput = true
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            // GitHub 对没有 UA 的请求直接拒，和抓取那边的 CDN 一个道理
            c.setRequestProperty("User-Agent", "sports-widget/1.0")
            c.outputStream.use { it.write("""{"ref":"$REF"}""".toByteArray(Charsets.UTF_8)) }

            // 成功是 204 No Content（有些代理会改写成 200，一并认）
            when (val code = c.responseCode) {
                in 200..299 -> TriggerResult(null, "已触发")
                401 -> TriggerResult("token 无效或已过期，重新建一个填进来", "token 无效")
                403 -> TriggerResult(
                    "token 权限不够 —— 需要这个仓库的 Actions 读写权限",
                    "权限不够",
                )
                404 -> TriggerResult(
                    "GitHub 说找不到这个仓库或 workflow（认出来的是 $repo）",
                    "找不到仓库",
                )
                else -> TriggerResult(
                    "GitHub 返回 HTTP $code" + errorDetail(c),
                    "HTTP $code",
                )
            }
        } catch (e: Exception) {
            // 超时 / DNS / 连不上都会落到这里。缩写不写具体的异常名 ——
            // 小组件那一行放不下，而且对用户来说都归到「网络」这一档
            TriggerResult(
                error = "触发失败：${e.message ?: e.javaClass.simpleName}",
                short = "网络异常",
            )
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 把 GitHub 的错误响应体截一段附上。
     *
     * 它的报错信息写得很具体（「Resource not accessible by personal access token」
     * 之类），比光一个状态码有用得多 —— 排查权限问题时基本靠它。
     */
    private fun errorDetail(c: HttpURLConnection): String = runCatching {
        c.errorStream?.bufferedReader()?.use { it.readText() }?.take(300)
    }.getOrNull()?.takeIf { it.isNotBlank() }?.let { "：$it" } ?: ""
}
