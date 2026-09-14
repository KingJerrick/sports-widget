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

/**
 * 七个赛事类别。key 与后端 JSON 里的 cat 字段一一对应。
 *
 * 顺序 = 图例里的顺序，也和 tools/fetch_calendar.py 的 SOURCES 一致：
 * 先是赛车项目（一个周末撑起一张卡），再是队伍类。
 *
 * ⚠️ **加新类别必须同时改三处**，漏一处这个类别在手机上就整个不显示
 * （CalendarParser.parse 对认不出的 cat 是静默跳过，不报错）：
 *   1. 这里加枚举项
 *   2. res/drawable/ 加一个 bg_pill_xxx.xml
 *   3. values/colors.xml 和 values-night/colors.xml 各加一个 cat_xxx
 */
enum class Cat(
    val key: String,
    /** App 图例里显示的名字 */
    val label: String,
    /** 小组件里 chip 的背景（7 个预置 drawable，见 bg_pill_f1.xml 的说明） */
    val pillRes: Int,
    /** App 图例色点的颜色 */
    val dotColorRes: Int,
) {
    F1("f1", "F1", R.drawable.bg_pill_f1, R.color.cat_f1),
    MOTOGP("motogp", "MotoGP", R.drawable.bg_pill_mgp, R.color.cat_mgp),
    FOOTBALL("football", "足球 · 皇马", R.drawable.bg_pill_football, R.color.cat_football),
    MLB("mlb", "棒球 · 道奇", R.drawable.bg_pill_mlb, R.color.cat_mlb),
    NBA("nba", "篮球 · 勇士", R.drawable.bg_pill_nba, R.color.cat_nba),
    CS2("cs2", "CS2 · 猎鹰", R.drawable.bg_pill_cs2, R.color.cat_cs2),
    LOL("lol", "英雄联盟 · IG", R.drawable.bg_pill_lol, R.color.cat_lol),
    ;

    /**
     * 队伍类还是赛事类。
     *
     * 两者的卡片第二行写法不一样：队伍类只有一个对手（「vs 巴列卡」），
     * 赛车项目要把一个周末的各场次串起来（「FP1 · FP2 · 排位 · 正赛」）。
     *
     * 写成「除了赛车项目都是队伍类」而不是逐个列举：以后加新类别时，
     * 默认落进队伍类（显示「vs 对手」）比默认落进赛事类
     * （把一堆时间上不相干的场次用 · 串成一行）要安全得多。
     */
    val isTeamSport: Boolean
        get() = this != F1 && this != MOTOGP

    companion object {
        fun fromKey(key: String?): Cat? = entries.firstOrNull { it.key == key }
    }
}

/**
 * 一场比赛在**当前时刻**处于哪个阶段。
 *
 * ⚠️ 这个状态是 App 拿设备当前时刻**现算**的，不是后端下发的。
 * 抓取每 6 小时才跑一次，后端算好的「进行中」写进 JSON 之后，等手机读到的时候
 * 那场比赛早就打完了。所以后端只下发 [Event.durationMinutes]（典型时长），
 * 状态在这一端现推 —— 见 tools/fetch_calendar.py 里 make_event 的说明。
 *
 * 「进行中」是**近似**的：开赛 + 典型时长之前都算在打，不看实际什么时候结束。
 * 打了 12 局的棒球会超过四小时、下雨中断的足球会短于一小时，都认了 ——
 * 精确的实时状态得一场一场单独去查，为这个小组件不值当。
 */
enum class EventStatus { UPCOMING, LIVE, FINISHED }

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
     * 典型时长（分钟），后端按类别下发（赛车再按场次细分：正赛 120、排位 60）。
     *
     * 缺字段的老缓存给 0 —— 那时 [statusAt] 会退化成「一开赛就算结束」，
     * 显示得不准但不会崩。
     */
    val durationMinutes: Int,
    /**
     * 重要性，3 = 正赛/冲刺赛/追的队的比赛，2 = 排位，1 = 练习。
     *
     * 卡片第二行放不下太多条目时会按它取舍，另外用来给「下一场」排序。
     */
    val rank: Int,
    /**
     * 归属的卡片。一个比赛周末的多场次共用一个 group —— F1 西班牙站的
     * FP1/FP2/排位/正赛是**一张**卡，不是四张。棒球的一个系列赛同理
     * （3~4 连战共用一张卡，否则道奇一个人就能占满整个小组件）。
     * 其余队伍类一场比赛就是一张卡。分组在后端定，App 只按这个字段归并。
     */
    val group: String,
    /** 卡片标题的后半段：「西班牙站」「LaLiga」「LPL 淘汰赛」 */
    val groupName: String,
    /**
     * 打完之后才有：队伍类是「我们得分-对手得分」（如 `4-6`，**不跟主客变**），
     * 赛车是冠军代号（如 `ANT`）。还没打完就是 null。
     *
     * 比分固定写「我们-对手」是因为卡片标题的写法会变（`@ 红人` / `vs 巨人`），
     * 比分再跟着主客调顺序，就分不清哪个数才是我们的了。
     */
    val result: String?,
    /**
     * 这场我们赢了没。App 靠它数系列赛的「N 胜 M 负」。
     * 赛车没有胜负概念、没打完也谈不上输赢，都是 null。
     */
    val win: Boolean?,
) {
    /**
     * 结束时刻。
     *
     * ⚠️ 这**不是**后端给的结束时间，是「开赛 + 典型时长」估出来的 ——
     * 后端对足球/棒球/篮球根本不给结束时间（那些项目的结束时间本来也不确定）。
     */
    val endMs: Long get() = startMs + durationMinutes * 60_000L

    /** 这场现在处于哪个阶段。用当前时刻算，不用后端下发（理由见 [EventStatus]）。 */
    fun statusAt(nowMs: Long): EventStatus = when {
        nowMs < startMs -> EventStatus.UPCOMING
        nowMs < endMs -> EventStatus.LIVE
        else -> EventStatus.FINISHED
    }
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
     *
     * 全部打完的卡片没有「下一场」，那时它退化成**最后一场的开赛时刻** ——
     * 只用来排序，不显示。右侧那时显示的是 [result]。
     */
    val nextStartMs: Long,
    /** 第二行：赛车项目列出各场次，队伍类显示对手；全打完时列比分 */
    val detail: String,
    /**
     * 图标没上传时显示的字母块标记，如「F1」「GP」「皇马」。
     *
     * 图标是用户在 App 里自己传的（见 [LogoStore]），传了就用图，没传用这个。
     */
    val mark: String,
    /**
     * 这张卡现在处于哪个阶段。由组内所有 [Event] 的状态推出来：
     * 有进行中的就是进行中，否则有未开始的就是未开始，否则全打完了。
     *
     * 已结束的卡片会被**置顶**（见 [CalendarParser.buildCards]），
     * 并且换一套底色（见 WidgetService）。
     */
    val status: EventStatus,
    /**
     * 卡片右侧在 [status] 为 FINISHED 时显示的东西：
     * 单场是比分（`7-3`），系列赛是战绩（`2 胜 2 负`），赛车是冠军代号（`ANT`）。
     * 没打完就是 null —— 那时右侧显示时间或「进行中」。
     */
    val result: String?,
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
    /** 图标没上传时，每个类别的字母块标记 */
    val marks: Map<String, String>,
    val sources: List<SourceHealth>,
    /** 本次刷新的错误信息（网络失败等），成功后为 null */
    val error: String?,
) {
    companion object {
        const val EMPTY_TS = 0L

        fun empty(): CalendarData = CalendarData(
            EMPTY_TS, EMPTY_TS, emptyList(), emptyMap(), emptyMap(), emptyList(), null,
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
                val o = JSONObject()
                    .put("id", e.id)
                    .put("cat", e.cat.key)
                    .put("title", e.title)
                    .put("short", e.short)
                    .put("start", e.startMs)
                    // 存的是「典型时长」，不是结束时刻 —— 结束时刻由它推出来
                    .put("dur", e.durationMinutes)
                    .put("rank", e.rank)
                    .put("group", e.group)
                    .put("groupName", e.groupName)
                // 没打完的比赛这两项为空，就不写进去。
                // （org.json 的 put(key, null) 本来也会把键删掉，效果一样，
                //   但显式判空读起来更清楚：这两个字段本来就是「可能没有」的。）
                e.result?.let { o.put("result", it) }
                e.win?.let { o.put("win", it) }
                arr.put(o)
            }
            root.put("events", arr)

            root.put("labels", JSONObject(data.labels as Map<*, *>))
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
                        // 新字段，升级 App 时本地还存着老缓存。缺了给 0 ——
                        // statusAt 会退化成「一开赛就算结束」，显示不准但不会崩，
                        // 而且下一次刷新就会被覆盖掉
                        durationMinutes = o.optInt("dur", 0),
                        rank = o.optInt("rank", 1),
                        // 老缓存里没有这两个字段，兜底用 id / 空串，
                        // 这样升级 App 时旧缓存不会让整份数据解析失败
                        group = o.optString("group").ifBlank { o.optString("id") },
                        groupName = o.optString("groupName"),
                        // 没打完时这两项不存在，optString 会给空串 —— 统一归一成 null，
                        // 留着空串的话上层「result != null」的判断就永远成立了
                        result = o.optString("result").ifBlank { null },
                        win = if (o.isNull("win")) null else o.optBoolean("win"),
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
