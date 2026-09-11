package com.dailywork.sportswidget

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/**
 * 把后端那份 calendar.json 解析成模型，并归并出小组件要显示的卡片。
 *
 * ── 这里有两件事和「照着 JSON 读出来」不一样 ────────────────────────────
 *
 * 1. **只缓存裁剪过的窗口，不缓存整份 60 天赛程**
 *    见 [CACHE_WINDOW_DAYS]。onUpdate 在主线程，SharedPreferences 首次访问是同步
 *    全量解析，塞几百 KB 进去会 ANR。
 *
 * 2. **一个比赛周末归并成一张卡，不是一场一张**
 *    见 [buildCards]。F1 西班牙站的 FP1/FP2/排位/正赛是一张卡，
 *    拆成四张的话列表会被撑得很长，还看不出它们本来是一回事。
 */
object CalendarParser {

    /**
     * 本地缓存保留多少天。
     *
     * 后端一次发布 60 天，但全存进 SharedPreferences 会让 onUpdate 变慢（见上）。
     * 存 14 天够了：即使连着几天没刷新成功，「今天」往前滚也还在窗口里。
     * 窗口从**昨天**开始 —— 今天已经打完的场次也要留在今天的列里。
     */
    const val CACHE_WINDOW_DAYS = 14

    /** 小组件显示几天。 */
    const val WIDGET_DAYS = 7

    /** 解析后端返回的完整 JSON。失败返回 null，调用方保留旧数据。 */
    fun parse(raw: String, nowMs: Long, zone: ZoneId): CalendarData? {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null

        val generatedAt = parseIsoToMillis(root.optString("generated_at")) ?: return null

        val events = mutableListOf<Event>()
        val arr = root.optJSONArray("events")
        for (i in 0 until (arr?.length() ?: 0)) {
            val o = arr?.optJSONObject(i) ?: continue
            // 认不出的类别跳过而不是崩掉：后端加了新类别时，旧版 App 应该照常显示其余几类
            val cat = Cat.fromKey(o.optString("cat")) ?: continue
            val startMs = parseIsoToMillis(o.optString("start")) ?: continue

            events += Event(
                id = o.optString("id"),
                cat = cat,
                title = o.optString("title"),
                short = o.optString("short"),
                startMs = startMs,
                rank = o.optInt("rank", 1),
                // 后端老版本或本地旧缓存可能没有这两个字段。
                // 兜底成「一场一张卡」（用 id 当 group），
                // 总比所有比赛挤成一张卡、标题退化成「F1 · F1」强。
                group = o.optString("group").ifBlank { o.optString("id") },
                groupName = o.optString("groupName"),
            )
        }

        val sources = mutableListOf<SourceHealth>()
        val srcObj = root.optJSONObject("sources")
        for (cat in Cat.entries) {
            val o = srcObj?.optJSONObject(cat.key) ?: continue
            sources += SourceHealth(
                cat = cat,
                ok = o.optBoolean("ok", false),
                count = o.optInt("count", 0),
                error = if (o.isNull("error")) null else o.optString("error"),
            )
        }

        // 裁窗口：只留 [昨天, 昨天+CACHE_WINDOW_DAYS] 内的
        val today = LocalDate.now(zone)
        val lo = today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val hi = today.plusDays(CACHE_WINDOW_DAYS.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()

        return CalendarData(
            generatedAtMs = generatedAt,
            fetchedAtMs = nowMs,
            events = events.filter { it.startMs in lo until hi }.sortedBy { it.startMs },
            labels = stringMap(root.optJSONObject("labels")),
            marks = stringMap(root.optJSONObject("marks")),
            sources = sources,
            error = null,
        )
    }

    /** 第二行最多列几个条目。再多就按重要性取舍 —— 列不下时省略号比取舍更糟。 */
    private const val MAX_DETAIL_ITEMS = 6

    /**
     * 归并出小组件要显示的卡片。
     *
     * 一个 group 一张卡：F1 西班牙站的 FP1/FP2/排位/正赛是一张卡（标题「F1 · 西班牙站」，
     * 第二行列各场次），而不是四张各说各话的卡。队伍类一场比赛就是一张卡。
     *
     * **已经全部打完的卡片直接丢掉** —— 信息流里没必要给过去的事留位置，
     * 留着只会把真正要看的挤下去。
     */
    fun buildCards(data: CalendarData, nowMs: Long, limit: Int): List<Card> =
        data.events
            .groupBy { it.group }
            .mapNotNull { (gid, evs) ->
                val upcoming = evs.filter { it.startMs >= nowMs }
                if (upcoming.isEmpty()) return@mapNotNull null

                val head = evs.first()
                val label = data.labels[head.cat.key] ?: head.cat.label
                // config 里没写 teamLabel 时退回类别名，卡片标题至少不会空着
                val name = head.groupName.ifBlank { label }
                Card(
                    id = gid,
                    cat = head.cat,
                    title = "$label · $name",
                    name = name,
                    nextStartMs = upcoming.minOf { it.startMs },
                    detail = buildDetail(evs),
                    // 后端没给 mark 时退回类别名的前两个字符，至少不是空白
                    mark = data.marks[head.cat.key] ?: head.cat.label.take(2),
                )
            }
            .sortedBy { it.nextStartMs }
            .take(limit)

    /**
     * 卡片第二行。
     *
     * 队伍类只有一场，直接写对手（「vs 巴列卡」）；
     * 赛车项目把各场次按时间串起来（「FP1 · FP2 · 排位 · 正赛」）。
     */
    private fun buildDetail(events: List<Event>): String {
        if (events.size == 1) {
            val only = events.first()
            return if (only.cat.isTeamSport) "vs ${only.short}" else only.short
        }

        val sorted = events.sortedBy { it.startMs }
        // 去重：MotoGP 一个周末有 Q1/Q2 两节排位，都叫「排位」，
        // 不去重的话第二行会被两个一样的词占掉
        val unique = sorted.distinctBy { it.short }

        val picked = if (unique.size <= MAX_DETAIL_ITEMS) {
            unique
        } else {
            // 放不下时保正赛/排位，练习赛让位 —— 和卡片取舍同一个道理
            unique.sortedWith(compareByDescending<Event> { it.rank }.thenBy { it.startMs })
                .take(MAX_DETAIL_ITEMS)
                .sortedBy { it.startMs }
        }
        return picked.joinToString(" · ") { it.short }
    }

    /**
     * 顶部七天迷你条：这七天里哪天有比赛。
     *
     * 「哪天有比赛」和「比什么赛」是两个不同的问题 —— 卡片流回答后一个，
     * 这条回答前一个。没有它的话，一眼看过去完全不知道哪天是空的。
     */
    fun buildDayStrip(data: CalendarData, today: LocalDate, zone: ZoneId): List<Boolean> {
        val hasEvents = data.events.map { dayOf(it.startMs, zone) }.toHashSet()
        return (0 until WIDGET_DAYS).map { today.plusDays(it.toLong()) in hasEvents }
    }

    /**
     * 把响应的「形状」打成一份路径清单，给 App 的「测试连接」用。
     *
     * 为什么不用原始 JSON：整份响应几万字，自检页放不下；截前 2000 字又常常正好把
     * 关键段落切掉。这份清单只列路径、不列数据，短得多，而结构一点不丢。
     * （这个思路是从 deepseek-widget 的 UsageParser 直接搬过来的。）
     */
    fun describeStructure(raw: String, limit: Int = 60): String {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return "（不是合法的 JSON 对象）"
        val out = mutableListOf<String>()
        walk("", root, out, limit)
        return out.take(limit).joinToString("\n")
    }

    private fun walk(path: String, node: Any?, out: MutableList<String>, limit: Int) {
        if (out.size >= limit) return
        when (node) {
            is JSONObject -> {
                if (path.isNotEmpty()) out += "$path  {${node.length()} 项}"
                node.keys().forEach { k ->
                    val child = if (path.isEmpty()) k else "$path.$k"
                    walk(child, node.opt(k), out, limit)
                }
            }

            is JSONArray -> {
                out += "$path  [${node.length()} 项]"
                if (node.length() > 0) walk("$path[0]", node.opt(0), out, limit)
            }

            else -> out += "$path = ${node?.toString()?.take(40) ?: "null"}"
        }
    }
}
