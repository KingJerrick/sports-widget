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

    /**
     * 队伍类还是赛事类。
     *
     * 两者的卡片第二行写法不一样：队伍类只有一个对手（「vs 巴列卡」），
     * 赛车项目要把一个周末的各场次串起来（「FP1 · FP2 · 排位 · 正赛」）。
     */
    val isTeamSport: Boolean
        get() = this == CS2 || this == FOOTBALL || this == LOL

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
    /** 卡片第二行上的短标签，后端已裁到 ≤3 汉字 / ≤6 拉丁字符 */
    val short: String,
    /** 开始时间，UTC epoch 毫秒 */
    val startMs: Long,
    /**
     * 重要性，3 = 正赛/冲刺赛/追的队的比赛，2 = 排位，1 = 练习。
     *
     * 卡片第二行放不下太多条目时会按它取舍，另外用来给「下一场」排序。
     */
    val rank: Int,
    /**
     * 归属的卡片。一个比赛周末的多场次共用一个 group —— F1 西班牙站的
     * FP1/FP2/排位/正赛是**一张**卡，不是四张。队伍类一场比赛就是一张卡。
     */
    val group: String,
    /** 卡片标题的后半段：「西班牙站」「LaLiga」「LPL 淘汰赛」 */
    val groupName: String,
) {
    /** 是不是已经开赛了。用当前时刻算，不用后端下发 —— 后端那份数据可能是几小时前的。 */
    fun isPast(nowMs: Long): Boolean = startMs < nowMs
}

/**
 * 小组件上的一张卡片：左侧图标 + 「类别 · 站次/赛事」+ 下一场时间 + 第二行的场次/对手。
 *
 * 由 [CalendarParser.buildCards] 从一组同 group 的 [Event] 归并出来。
 */
data class Card(
    val id: String,
    val cat: Cat,
    /** 卡片第一行的标题，如「F1 · 西班牙站」「皇马 · LaLiga」 */
    val title: String,
    /** 「西班牙站」「LaLiga」「LPL 淘汰赛」 */
    val name: String,
    /**
     * 卡片右侧显示的时间：**下一场还没开始的**那场。
     *
     * 用下一场而不是第一场，是因为一个比赛周末横跨三天 —— 周日看的时候，
     * 显示周五的 FP1 时间没有任何意义。
     */
    val nextStartMs: Long,
    /** 第二行：赛车项目列出各场次，队伍类显示对手 */
    val detail: String,
    /** 左侧图标地址，可能没有（离线或后端没给），那时退回字母块 */
    val logoUrl: String?,
    /** 图标拉不到时显示的字母块标记，如「F1」「GP」「皇马」 */
    val mark: String,
)

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
    /** 类别的显示名，如 f1→"F1"、football→"皇马"。后端从 config.json 推导，改队伍会自动跟着变 */
    val labels: Map<String, String>,
    /** 每个类别的图标地址，App 拉下来缓存到本地 */
    val logos: Map<String, String>,
    /** 图标拉不到时的字母块标记 */
    val marks: Map<String, String>,
    val sources: List<SourceHealth>,
    /** 本次刷新的错误信息（网络失败等），成功后为 null */
    val error: String?,
) {
    companion object {
        const val EMPTY_TS = 0L

        fun empty(): CalendarData = CalendarData(
            EMPTY_TS, EMPTY_TS, emptyList(), emptyMap(), emptyMap(), emptyMap(), emptyList(), null,
        )

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
                        .put("group", e.group)
                        .put("groupName", e.groupName)
                )
            }
            root.put("events", arr)

            root.put("labels", JSONObject(data.labels as Map<*, *>))
            root.put("logos", JSONObject(data.logos as Map<*, *>))
            root.put("marks", JSONObject(data.marks as Map<*, *>))

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
                        // 老缓存里没有这两个字段，兜底用 id / 空串，
                        // 这样升级 App 时旧缓存不会让整份数据解析失败
                        group = o.optString("group").ifBlank { o.optString("id") },
                        groupName = o.optString("groupName"),
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
                    labels = stringMap(root.optJSONObject("labels")),
                    logos = stringMap(root.optJSONObject("logos")),
                    marks = stringMap(root.optJSONObject("marks")),
                    sources = sources,
                    error = if (root.isNull("error")) null else root.optString("error"),
                )
            }.getOrElse { empty() }
        }
    }
}

// ── 时间工具（顶层函数，与 Stats.kt 的风格一致）──────────────────────────

/** 星期几的中文，索引 0 = 周一。Model / WidgetRenderer / MainActivity 共用一份。 */
val WDAY_CN = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/**
 * 卡片右上角的时间：「今天 19:30」「明天 17:00」「周六 03:00」。
 *
 * 带上「今天/明天」而不是只写「9/11」，是因为卡片流里没有日期列 ——
 * 光看一个 03:00 完全不知道是今天凌晨还是后天凌晨。
 */
fun formatCardTime(ms: Long, today: LocalDate, zone: ZoneId): String {
    val z = Instant.ofEpochMilli(ms).atZone(zone)
    val day = z.toLocalDate()
    val prefix = when (day) {
        today -> "今天"
        today.plusDays(1) -> "明天"
        else -> WDAY_CN[day.dayOfWeek.value - 1]
    }
    return "$prefix ${z.format(CARD_TIME_FMT)}"
}

private val CARD_TIME_FMT =
    java.time.format.DateTimeFormatter.ofPattern("HH:mm", java.util.Locale.US)

/** JSONObject -> Map<String, String>，非字符串值直接转成字符串。 */
fun stringMap(obj: JSONObject?): Map<String, String> {
    if (obj == null) return emptyMap()
    val out = mutableMapOf<String, String>()
    obj.keys().forEach { k -> out[k] = obj.optString(k) }
    return out
}

/** "2026-09-13T13:00:00Z" -> epoch 毫秒。解析不了返回 null，不抛。 */
fun parseIsoToMillis(iso: String?): Long? =
    runCatching { Instant.parse(iso!!).toEpochMilli() }.getOrNull()

/** 这个时刻落在设备时区的哪一天。分组、判断「今天」都靠它。 */
fun dayOf(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

/** 数据距今多久没更新了（毫秒）。用于顶栏的「过期变琥珀色」。 */
fun stalenessMs(generatedAtMs: Long, nowMs: Long): Long =
    if (generatedAtMs <= 0L) Long.MAX_VALUE else nowMs - generatedAtMs
