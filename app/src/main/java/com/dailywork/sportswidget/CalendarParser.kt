package com.dailywork.sportswidget

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/** 一天在小组件里占的那一列：日期 + 选中的几条 + 还有几个项目没显示出来。 */
data class DayPlan(
    val date: LocalDate,
    val chips: List<Event>,
    /** >0 时最后一条位置显示「+N」。表示还有别的**项目**没挤进来（不是场次数）。 */
    val hiddenCats: Int,
)

/**
 * 把后端那份 calendar.json 解析成模型，并算出小组件的七列怎么排。
 *
 * ── 这里有两件事和「照着 JSON 读出来」不一样 ────────────────────────────
 *
 * 1. **只缓存裁剪过的窗口，不缓存整份 60 天赛程**
 *    见 [CACHE_WINDOW_DAYS]。onUpdate 在主线程，SharedPreferences 首次访问是同步
 *    全量解析，塞几百 KB 进去会 ANR。
 *
 * 2. **每天显示哪几条是「按重要性选」而不是「按时间截」**
 *    见 [selectChips]。这是整个小组件最要紧的一条规则。
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
            sources = sources,
            error = null,
        )
    }

    /**
     * 排出小组件的七列。
     *
     * 「今天」是按**设备时区**算的，所以 F1 欧洲站（UTC 13:00 = 北京 21:00）会落在
     * 当地的那一天，而不是 UTC 的那一天。
     */
    fun buildWeek(
        data: CalendarData,
        today: LocalDate,
        maxChips: Int,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<DayPlan> {
        // 先按当地日期分桶。窗口从昨天开始，所以今天已经打完的场次也在
        val byDay = data.events.groupBy { dayOf(it.startMs, zone) }

        return (0 until WIDGET_DAYS).map { offset ->
            val date = today.plusDays(offset.toLong())
            val dayEvents = byDay[date].orEmpty().sortedBy { it.startMs }
            val (chips, hiddenCats) = selectChips(dayEvents, maxChips)
            DayPlan(date = date, chips = chips, hiddenCats = hiddenCats)
        }
    }

    /**
     * 一天里选出要显示的那几条。
     *
     * ── 为什么不能按时间顺序取前 N 条 ────────────────────────────────────
     * 实测一个周六能有 7 场（F1 两节 + MotoGP 四节 + 一场 LoL），而一列只放得下 3 条。
     * 按时间截断的话，周日那天会显示成：
     *
     *     皇马 vs 巴列卡诺 | MotoGP 热身赛 | MotoGP 正赛     ← F1 正赛被挤掉了
     *
     * 整个周末最该看到的那一场没了，而且不报错、不崩，只是那一格空着。
     *
     * ── 规则 ──────────────────────────────────────────────────────────
     * 第一轮：**每个项目先各占一条**（取该项目里最重要的，同分取最早的）。
     *         这样「今天有哪几个项目」不会被某一类的练习赛淹没。
     * 第二轮：还有空位就用剩下的按重要性补满（练习赛在空档的日子就能露出来）。
     * 项目数本身就超过槽位时，留一条显示「+N」告诉用户还有别的项目。
     *
     * 返回值里的列表按时间排好序 —— 选是按重要性，显示还是按时间，读起来才像日程。
     */
    fun selectChips(events: List<Event>, maxChips: Int): Pair<List<Event>, Int> {
        if (events.isEmpty()) return emptyList<Event>() to 0
        if (events.size <= maxChips) return events.sortedBy { it.startMs } to 0

        // 带着下标走，避免 id 重复时把两场不同的比赛当成同一场
        val indexed = events.withIndex().toList()

        val reps = indexed
            .groupBy { it.value.cat }
            .map { (_, list) ->
                list.sortedWith(
                    compareByDescending<IndexedValue<Event>> { it.value.rank }
                        .thenBy { it.value.startMs }
                ).first()
            }
            .sortedBy { it.value.startMs }

        val chosenIdx = mutableListOf<Int>()
        var hiddenCats = 0

        if (reps.size <= maxChips) {
            chosenIdx += reps.map { it.index }
            val rest = indexed
                .filter { it.index !in chosenIdx }
                .sortedWith(
                    compareByDescending<IndexedValue<Event>> { it.value.rank }
                        .thenBy { it.value.startMs }
                )
            var i = 0
            while (chosenIdx.size < maxChips && i < rest.size) {
                chosenIdx += rest[i].index
                i++
            }
        } else {
            // 项目数超过槽位：留最后一条给「+N」
            chosenIdx += reps.take(maxChips - 1).map { it.index }
            hiddenCats = reps.size - (maxChips - 1)
        }

        return events.filterIndexed { i, _ -> i in chosenIdx }.sortedBy { it.startMs } to hiddenCats
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
