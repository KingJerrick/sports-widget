package com.dailywork.sportswidget

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 数据模型与格式化。
 *
 * 沿用 deepseek-widget / Stats.kt 的写法：`data class` + **计算属性**派生展示值
 * （不在解析期算），格式化函数是顶层函数，所有 String.format 都带 Locale.US。
 *
 * ── 时间为什么一律存 UTC 的 epoch 毫秒 ──────────────────────────────────
 * 后端发的是 UTC 的 ISO8601。这里立刻转成 epoch 毫秒存着，展示时再按设备时区换算 ——
 * F1 欧洲站在北京时间是晚上 21:00，MotoGP 可能在凌晨，不换算会分错天。
 * 存 epoch 而不是 LocalDateTime，是因为跨时区、跨夏令时比较大小永远是对的。
 */

/** 五个赛事类别。key 与后端 JSON 里的 cat 字段一一对应。 */
enum class Cat(
    val key: String,
    /** App 图例里显示的名字 */
    val label: String,
    /** 小组件里 chip 的背景（5 个预置 drawable，见 bg_pill_f1.xml 的说明） */
    val pillRes: Int,
    /** App 图例色点的颜色 */
    val dotColorRes: Int,
) {
    F1("f1", "F1", R.drawable.bg_pill_f1, R.color.cat_f1),
    MOTOGP("motogp", "MotoGP", R.drawable.bg_pill_mgp, R.color.cat_mgp),
    CS2("cs2", "CS2 · 猎鹰", R.drawable.bg_pill_cs2, R.color.cat_cs2),
    FOOTBALL("football", "足球 · 皇马", R.drawable.bg_pill_football, R.color.cat_football),
    LOL("lol", "英雄联盟 · IG", R.drawable.bg_pill_lol, R.color.cat_lol),
    ;

    companion object {
        fun fromKey(key: String?): Cat? = entries.firstOrNull { it.key == key }
    }
}

/** 单场赛事。只保留小组件和列表页真正用得到的字段。 */
data class Event(
    val id: String,
    val cat: Cat,
    /** 完整标题，App 列表页用，如「F1 西班牙站 · 正赛」 */
    val title: String,
    /** 小组件 chip 上的短标签，后端已裁到 ≤3 汉字 / ≤6 拉丁字符 */
    val short: String,
    /** 开始时间，UTC epoch 毫秒 */
    val startMs: Long,
    /**
     * 重要性，3 = 正赛/冲刺赛/追的队的比赛，2 = 排位，1 = 练习。
     *
     * 小组件每列只放得下 3 条，而实测一个周六能有 7 场。**按时间截断是错的** ——
     * 会把 F1 正赛挤掉。所以按这个字段决定留下谁，见 [CalendarParser.selectChips]。
     */
    val rank: Int,
) {
    /** 是不是已经开赛了。用当前时刻算，不用后端下发 —— 后端那份数据可能是几小时前的。 */
    fun isPast(nowMs: Long): Boolean = startMs < nowMs
}

/** 某个数据源这次的抓取结果，App 的「数据源状态」直接显示这个。 */
data class SourceHealth(
    val cat: Cat,
    val ok: Boolean,
    val count: Int,
    val error: String?,
)

/**
 * 一次刷新的完整结果，也是本地缓存的东西。
 *
 * ⚠️ 只存**裁剪过的**事件集合，不存后端那份完整的 60 天赛程：
 * `AppWidgetProvider.onUpdate` 跑在主线程，而 SharedPreferences 首次访问是
 * 同步全量加载 + 解析 XML。存几百 KB 进去会 ANR（用户看到「小组件无响应」然后被系统杀掉）。
 * 见 [CalendarParser.CACHE_WINDOW_DAYS] 的说明。
 */
data class CalendarData(
    /** 后端生成这份数据的时间。顶栏显示的是**这个**，不是手机拉取的时间 —— 见 WidgetRenderer */
    val generatedAtMs: Long,
    /** 手机最后一次成功拉到数据的时间 */
    val fetchedAtMs: Long,
    val events: List<Event>,
    val sources: List<SourceHealth>,
    /** 本次刷新的错误信息（网络失败等），成功后为 null */
    val error: String?,
) {
    companion object {
        const val EMPTY_TS = 0L

        fun empty(): CalendarData = CalendarData(EMPTY_TS, EMPTY_TS, emptyList(), emptyList(), null)

        // ── 手写 org.json 序列化，不引第三方库 ──────────────────────────
        // 沿用 deepseek-widget / Snapshot 的做法：整个对象存成一个 JSON 字符串，
        // 塞进 SharedPreferences 的一个 key。

        fun toJson(data: CalendarData): String {
            val root = JSONObject()
            root.put("generatedAt", data.generatedAtMs)
            root.put("fetchedAt", data.fetchedAtMs)
            data.error?.let { root.put("error", it) }

            val arr = JSONArray()
            data.events.forEach { e ->
                arr.put(
                    JSONObject()
                        .put("id", e.id)
                        .put("cat", e.cat.key)
                        .put("title", e.title)
                        .put("short", e.short)
                        .put("start", e.startMs)
                        .put("rank", e.rank)
                )
            }
            root.put("events", arr)

            val src = JSONArray()
            data.sources.forEach { s ->
                src.put(
                    JSONObject()
                        .put("cat", s.cat.key)
                        .put("ok", s.ok)
                        .put("count", s.count)
                        .put("error", s.error)
                )
            }
            root.put("sources", src)

            return root.toString()
        }

        fun fromJson(raw: String?): CalendarData {
            if (raw.isNullOrBlank()) return empty()
            return runCatching {
                val root = JSONObject(raw)

                val events = mutableListOf<Event>()
                val arr = root.optJSONArray("events")
                for (i in 0 until (arr?.length() ?: 0)) {
                    val o = arr?.optJSONObject(i) ?: continue
                    // 认不出的类别直接跳过 —— 后端加了新类别而 App 还没更新时，
                    // 旧版 App 应该照常显示其他四类，而不是整个列表崩掉
                    val cat = Cat.fromKey(o.optString("cat")) ?: continue
                    events += Event(
                        id = o.optString("id"),
                        cat = cat,
                        title = o.optString("title"),
                        short = o.optString("short"),
                        startMs = o.optLong("start"),
                        rank = o.optInt("rank", 1),
                    )
                }

                val sources = mutableListOf<SourceHealth>()
                val src = root.optJSONArray("sources")
                for (i in 0 until (src?.length() ?: 0)) {
                    val o = src?.optJSONObject(i) ?: continue
                    val cat = Cat.fromKey(o.optString("cat")) ?: continue
                    sources += SourceHealth(
                        cat = cat,
                        ok = o.optBoolean("ok", false),
                        count = o.optInt("count", 0),
                        error = if (o.isNull("error")) null else o.optString("error"),
                    )
                }

                CalendarData(
                    generatedAtMs = root.optLong("generatedAt", EMPTY_TS),
                    fetchedAtMs = root.optLong("fetchedAt", EMPTY_TS),
                    events = events,
                    sources = sources,
                    error = if (root.isNull("error")) null else root.optString("error"),
                )
            }.getOrElse { empty() }
        }
    }
}

// ── 时间工具（顶层函数，与 Stats.kt 的风格一致）──────────────────────────

/** "2026-09-13T13:00:00Z" -> epoch 毫秒。解析不了返回 null，不抛。 */
fun parseIsoToMillis(iso: String?): Long? =
    runCatching { Instant.parse(iso!!).toEpochMilli() }.getOrNull()

/** 这个时刻落在设备时区的哪一天。分组、判断「今天」都靠它。 */
fun dayOf(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

/** 数据距今多久没更新了（毫秒）。用于顶栏的「过期变琥珀色」。 */
fun stalenessMs(generatedAtMs: Long, nowMs: Long): Long =
    if (generatedAtMs <= 0L) Long.MAX_VALUE else nowMs - generatedAtMs
