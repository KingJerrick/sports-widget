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
     * 本地缓存向前保留多少天。
     *
     * 后端一次发布 60 天，但全存进 SharedPreferences 会让 onUpdate 变慢（见上）。
     * 存 14 天够了：即使连着几天没刷新成功，「今天」往前滚也还在窗口里。
     */
    const val CACHE_WINDOW_DAYS = 14

    /**
     * 本地缓存**向回**保留多少天。
     *
     * 原本只要 1 天，够「今天已经打完的场次留在今天的列里」用。
     * 加了赛果之后不够了：棒球一个系列赛横跨 4 天，等它打完的时候前面几场
     * 早就掉出窗口 —— 系列赛战绩会从「3 胜 1 负」退化成「1 胜 0 负」。
     *
     * 5 天能装下最长的系列赛（4 场）再加一点余量。后端那边相应地要往回发布 7 天
     * （见 tools/fetch_calendar.py 的 BACK_DAYS），否则这里想看的区间在数据里就没有。
     *
     * 多存这几天不会让 onUpdate 变慢 —— 一天也就几场，相对 14 天的正向窗口可以忽略。
     */
    const val CACHE_BACK_DAYS = 5

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
                // 缺了给 0：那时 statusAt 退化成「一开赛就算结束」。宁可显示不准，
                // 也不要因为一个字段缺失就整份数据解析失败、小组件空掉。
                durationMinutes = o.optInt("dur", 0),
                rank = o.optInt("rank", 1),
                // 后端老版本或本地旧缓存可能没有这两个字段。
                // 兜底成「一场一张卡」（用 id 当 group），
                // 总比所有比赛挤成一张卡、标题退化成「F1 · F1」强。
                group = o.optString("group").ifBlank { o.optString("id") },
                groupName = o.optString("groupName"),
                // 没打完时后端不下发，optString 会给空串 —— 归一成 null，
                // 否则上层「result != null」会一直成立，未开赛的卡片也会去显示赛果
                result = o.optString("result").ifBlank { null },
                win = if (o.isNull("win")) null else o.optBoolean("win"),
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

        // 裁窗口：只留 [今天-CACHE_BACK_DAYS, 今天+CACHE_WINDOW_DAYS] 内的
        val today = LocalDate.now(zone)
        val lo = today.minusDays(CACHE_BACK_DAYS.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
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
     * 已经打完的卡片最多再留 24 小时。
     *
     * 再往前的事不该占信息流的位置 —— 小组件每列就那么大，
     * 给上周的比赛留位置就等于把今天的挤下去。
     */
    private const val FINISHED_KEEP_MS = 24 * 60 * 60 * 1000L

    /**
     * 归并出小组件要显示的卡片。
     *
     * 一个 group 一张卡：F1 西班牙站的 FP1/FP2/排位/正赛是一张卡（标题「F1 · 西班牙站」，
     * 第二行列各场次），而不是四张各说各话的卡。棒球同理 —— 一个系列赛是一张卡
     * （后端按系列赛分组，见 tools/fetch_calendar.py 的 fetch_mlb）。
     * 其余队伍类都是一场比赛一张卡。
     *
     * **已经全部打完的卡片直接丢掉** —— 信息流里没必要给过去的事留位置，
     * 留着只会把真正要看的挤下去。
     */
    fun buildCards(data: CalendarData, nowMs: Long, limit: Int): List<Card> =
        data.events
            .groupBy { it.group }
            .mapNotNull { (gid, evs) ->
                val status = cardStatus(evs, nowMs)

                // 全打完的卡片只再留 24 小时 —— 再往前的事不该占信息流的位置。
                //
                // ⚠️ 这里以前是「没有未开始的场次就丢」，刚打完的比赛会立刻消失。
                // 而「昨晚那场谁赢了」恰恰是最该看到的，所以改成了按结束时间判。
                if (status == EventStatus.FINISHED &&
                    nowMs - evs.maxOf { it.endMs } > FINISHED_KEEP_MS
                ) return@mapNotNull null

                val head = evs.first()
                val label = data.labels[head.cat.key] ?: head.cat.label
                // config 里没写 teamLabel 时退回类别名，卡片标题至少不会空着
                val name = head.groupName.ifBlank { label }

                // 右侧那个位置：没打完显示下一场时间（WidgetService 负责格式化），
                // 打完了显示赛果。
                //
                // ⚠️ 全部打完时没有「下一场」，upcoming 是空的 ——
                // 老写法在这里 minOf 一个空集合，会抛 NoSuchElementException。
                // 已经结束的卡片退化成「最后一场的开赛时刻」，它只参与排序、不显示。
                val nextStartMs = evs.filter { it.startMs > nowMs }.minOfOrNull { it.startMs }
                    ?: evs.maxOf { it.startMs }

                Card(
                    id = gid,
                    cat = head.cat,
                    title = "$label · $name",
                    name = name,
                    nextStartMs = nextStartMs,
                    detail = buildDetail(evs, status),
                    // 后端没给 mark 时退回类别名的前两个字符，至少不是空白
                    mark = data.marks[head.cat.key] ?: head.cat.label.take(2),
                    status = status,
                    result = cardResult(evs, status),
                )
            }
            // 已结束的**置顶**（刚打完的在最前），其余的照旧按下一场时间排。
            // 键写成「先按是否结束分组，再按时间」，结束的那组用负值达到倒序。
            .sortedWith(
                compareBy<Card> { if (it.status == EventStatus.FINISHED) 0 else 1 }
                    .thenBy { if (it.status == EventStatus.FINISHED) -it.nextStartMs else it.nextStartMs }
            )
            .take(limit)

    /**
     * 这张卡现在处于哪个阶段。
     *
     * 优先级：**只要有一场在打就是「进行中」**，哪怕别的场次还没开始 ——
     * 一个 MotoGP 周末里正赛在跑的时候，卡片就该显示「进行中」，
     * 而不是因为还有热身赛没打就退成「未开始」。
     * 否则只要有没开始的就是「未开始」，全打完了才是「已结束」。
     */
    private fun cardStatus(evs: List<Event>, nowMs: Long): EventStatus {
        var anyUpcoming = false
        for (e in evs) {
            when (e.statusAt(nowMs)) {
                EventStatus.LIVE -> return EventStatus.LIVE
                EventStatus.UPCOMING -> anyUpcoming = true
                EventStatus.FINISHED -> Unit
            }
        }
        return if (anyUpcoming) EventStatus.UPCOMING else EventStatus.FINISHED
    }

    /**
     * 卡片右侧在打完之后显示什么。
     *
     *   多场（棒球的系列赛）→ 战绩「2 胜 2 负」，靠数 [Event.win]
     *   单场 / 赛车        → 最后一场有成绩的那个赛果（正赛的冠军代号 `ANT`）
     *
     * 拿不到就是 null，届时右侧退回「已结束」。
     */
    private fun cardResult(evs: List<Event>, status: EventStatus): String? {
        if (status != EventStatus.FINISHED) return null

        // 只有**多场**才报战绩。单场报「1 胜 0 负」是废话，比分本身信息量大得多。
        if (evs.size > 1) {
            val wins = evs.count { it.win == true }
            val losses = evs.count { it.win == false }
            // 胜负都为 0 说明是赛车（一个周末全是场次，没有输赢概念），往下走
            if (wins + losses > 0) return "$wins 胜 $losses 负"
        }

        // 取「最后一场**有成绩的**」而不是「最后一场」：
        // 一个 F1 周末里 FP1 没有成绩、正赛有冠军，要显示的是冠军。
        return evs.filter { it.result != null }.maxByOrNull { it.startMs }?.result
    }

    /**
     * 卡片第二行。
     *
     * 队伍类写对手（「vs 巴列卡」）；
     * 赛车项目把各场次按时间串起来（「FP1 · FP2 · 排位 · 正赛」）；
     * **全部打完**时改成列赛果。
     */
    private fun buildDetail(events: List<Event>, status: EventStatus): String {
        val first = events.first()

        // ── 全打完了 ────────────────────────────────────────────────────
        if (status == EventStatus.FINISHED) {
            val results = events.sortedBy { it.startMs }.mapNotNull { it.result }

            if (first.cat.isTeamSport) {
                // 多场（棒球的系列赛）才把每场比分列出来（「3-4 · 5-2 · 6-1 · 2-0」）。
                //
                // ⚠️ 单场**不能**也列比分 —— 右边那个位置已经显示比分了，
                // 第二行再写一遍就是同一个数字出现两次。单场写对手。
                //
                // 另外这里**不能**像赛车那样列 short：四个「红人」串成一行没有信息量。
                return if (results.size <= 1) "已结束 · vs ${first.short}"
                else results.joinToString(" · ")
            }
            // 赛车：场次名本身没有赛果（练习赛没有冠军），所以保留原来那串场次，
            // 只在前面挂一个「已结束」—— 光看场次名看不出这周末已经过完了
            return "已结束 · ${sessionLine(events)}"
        }

        // ── 还没打完 ────────────────────────────────────────────────────
        // 队伍类一个 group 要么是一场比赛，要么是棒球的一个系列赛
        // （3~4 连战共用一张卡，原因见 tools/fetch_calendar.py 的 fetch_mlb）。
        //
        // 多场时**不能**按下面赛车项目那样把 short 串起来 —— 四个「红人」
        // 串成「红人 · 红人 · 红人 · 红人」没有任何信息量。
        // 对手只在开头写一次，多出来的信息是**场次数**：
        // 看到「vs 红人 · 4 连战」就知道这周还有四场。
        if (first.cat.isTeamSport) {
            return "vs ${first.short}" +
                if (events.size > 1) " · ${events.size} 连战" else ""
        }

        return sessionLine(events)
    }

    /** 赛车项目的第二行：把各场次的 short 按时间串起来。 */
    private fun sessionLine(events: List<Event>): String {
        if (events.size == 1) return events.first().short

        // 去重：MotoGP 一个周末有 Q1/Q2 两节排位，都叫「排位」，
        // 不去重的话第二行会被两个一样的词占掉
        val unique = events.sortedBy { it.startMs }.distinctBy { it.short }

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
