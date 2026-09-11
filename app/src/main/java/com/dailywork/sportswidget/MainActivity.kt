package com.dailywork.sportswidget

import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 赛程页 / 设置页。
 *
 * 四段：① 赛程列表 ② 颜色图例 ③ 数据源状态 ④ 设置 + 自检。
 *
 * ── 这里的赛程列表和小组件上的卡片有什么关系 ────────────────────────────
 * 小组件把「一个比赛周末」归并成一张卡（见 [CalendarParser.buildCards]），
 * 适合扫一眼；这里则把每一场都平铺开，适合细看某天到底几点打什么。
 * 两者读的是同一份数据，只是切法不同。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var etEndpoint: EditText
    private lateinit var spInterval: Spinner
    private lateinit var tvStatusLine: TextView
    private lateinit var tvResult: TextView
    private lateinit var boxSchedule: LinearLayout
    private lateinit var boxLegend: LinearLayout
    private lateinit var boxSources: LinearLayout
    private lateinit var boxIcons: LinearLayout

    /** 用户点了「选图」之后，等着知道是给哪个分组选的。 */
    private var pendingCat: Cat? = null

    /**
     * 打开系统相册挑一张图。
     *
     * 用 GetContent，**不需要任何存储权限** —— 系统只把我们挑中的那一个文件
     * 临时授权给这个 App，不是把整个相册打开。挑完立刻读出来存成自己的 PNG，
     * 所以这个临时授权过期也无所谓。
     */
    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val cat = pendingCat
        pendingCat = null
        if (cat == null || uri == null) return@registerForActivityResult
        saveIcon(cat, uri)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 刷新间隔选项（分钟）。默认 6 小时与后端抓取节奏一致，见 Prefs 的说明。 */
    private val intervalOptions = listOf(60, 180, 360, 720, 1440)

    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    private val dateFmt = DateTimeFormatter.ofPattern("M/d", Locale.US)
    private val stampFmt = DateTimeFormatter.ofPattern("M/d HH:mm", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etEndpoint = findViewById(R.id.et_endpoint)
        spInterval = findViewById(R.id.sp_interval)
        tvStatusLine = findViewById(R.id.tv_status_line)
        tvResult = findViewById(R.id.tv_result)
        boxSchedule = findViewById(R.id.box_schedule)
        boxLegend = findViewById(R.id.box_legend)
        boxSources = findViewById(R.id.box_sources)
        boxIcons = findViewById(R.id.box_icons)

        spInterval.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            intervalOptions.map { minutes ->
                if (minutes < 60) "$minutes 分钟" else "${minutes / 60} 小时"
            },
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        findViewById<Button>(R.id.btn_save).setOnClickListener { saveAndRefresh() }
        findViewById<Button>(R.id.btn_test).setOnClickListener { runDiagnostics() }

        loadIntoForm()
    }

    override fun onResume() {
        super.onResume()
        renderAll()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // ── 表单 ──────────────────────────────────────────────────────────

    private fun loadIntoForm() {
        etEndpoint.setText(Prefs.getEndpoint(this))
        val minutes = Prefs.getIntervalMinutes(this)
        // 找不到就把最接近的选项选上（用户手改过 SharedPreferences 时也不会崩）
        spInterval.setSelection(intervalOptions.indexOf(minutes).takeIf { it >= 0 } ?: 2)
    }

    private fun saveAndRefresh() {
        Prefs.setEndpoint(this, etEndpoint.text.toString())
        Prefs.setIntervalMinutes(this, intervalOptions[spInterval.selectedItemPosition])
        RefreshScheduler.schedule(this, immediate = true)
        Toast.makeText(this, getString(R.string.toast_saved), Toast.LENGTH_SHORT).show()
        tvStatusLine.postDelayed({ renderAll() }, 1500)
    }

    // ── 渲染三块 ──────────────────────────────────────────────────────

    private fun renderAll() {
        val data = Prefs.loadData(this)
        renderStatusLine(data)
        renderSchedule(data)
        renderLegend(data)
        renderSources(data)
        renderIcons(data)
    }

    private fun renderStatusLine(data: CalendarData) {
        val text = when {
            data.error != null -> "⚠ ${data.error}"
            data.generatedAtMs <= 0L -> getString(R.string.status_never)
            else -> {
                val stamp = Instant.ofEpochMilli(data.generatedAtMs)
                    .atZone(ZoneId.systemDefault()).format(stampFmt)
                val fetched = Instant.ofEpochMilli(data.fetchedAtMs)
                    .atZone(ZoneId.systemDefault()).format(stampFmt)
                // 两个时间都给出来：数据是后端什么时候生成的、手机什么时候拉到的。
                // 后端的定时抓取挂掉时，只有前一个会停住，靠它才能发现问题。
                "数据生成于 $stamp ｜ 本机拉到于 $fetched"
            }
        }
        tvStatusLine.text = text
    }

    private fun renderSchedule(data: CalendarData) {
        boxSchedule.removeAllViews()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val now = System.currentTimeMillis()

        val byDay = data.events.groupBy { dayOf(it.startMs, zone) }

        var shown = 0
        for (offset in 0 until CalendarParser.WIDGET_DAYS) {
            val date = today.plusDays(offset.toLong())
            val events = byDay[date].orEmpty().sortedBy { it.startMs }
            if (events.isEmpty()) continue

            boxSchedule.addView(sectionLabel(dayLabel(today, date)))

            events.forEach { e ->
                boxSchedule.addView(eventRow(e, zone, e.isPast(now)))
                shown++
            }
        }

        if (shown == 0) {
            boxSchedule.addView(note(getString(R.string.empty_schedule)))
        }
    }

    /**
     * 图例。名字和标记都取**后端下发的**值，不是写死在代码里的 ——
     * 改了 data/config.json 换队伍之后，这里会跟着变，不用重新装 App。
     */
    private fun renderLegend(data: CalendarData) {
        boxLegend.removeAllViews()
        Cat.entries.forEach { cat ->
            val label = data.labels[cat.key] ?: cat.label
            val mark = data.marks[cat.key] ?: label.take(2)
            val icon = if (LogoStore.has(this, cat)) "你上传的图标" else "字母块「$mark」"

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = lp(matchWidth = true).apply { topMargin = dp(6f) }
            }
            row.addView(dot(cat))
            row.addView(
                TextView(this).apply {
                    text = "$label   →   卡片左侧显示$icon"
                    textSize = 12f
                    setTextColor(color(R.color.text_secondary))
                    layoutParams = lp().apply { marginStart = dp(8f) }
                }
            )
            boxLegend.addView(row)
        }
    }

    // ── 卡片图标：用户自己上传 ────────────────────────────────────────

    /**
     * 每个分组一行：预览 + 名字 + 「选图」+「清除」。
     *
     * 图标是用户自己传的，不联网拉 —— 网上拉回来的图明暗不一定配我们的卡片底色
     * （F1 和 MotoGP 的标本来就是给深色背景用的），尺寸比例也参差不齐。
     * 自己放一张满意的图，放什么就是什么。
     */
    private fun renderIcons(data: CalendarData) {
        boxIcons.removeAllViews()
        Cat.entries.forEach { cat ->
            val label = data.labels[cat.key] ?: cat.label
            val mark = data.marks[cat.key] ?: label.take(2)
            val uploaded = LogoStore.has(this, cat)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = lp(matchWidth = true).apply { topMargin = dp(10f) }
            }
            row.addView(iconPreview(cat, mark, uploaded))
            row.addView(
                TextView(this).apply {
                    text = if (uploaded) {
                        "$label  ·  ${getString(R.string.icon_uploaded)}"
                    } else {
                        "$label  ·  ${getString(R.string.icon_letter_prefix)}「$mark」"
                    }
                    textSize = 13f
                    setTextColor(color(R.color.text_secondary))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { marginStart = dp(12f) }
                }
            )
            row.addView(
                Button(this).apply {
                    text = getString(R.string.btn_pick_image)
                    setOnClickListener { startPick(cat) }
                }
            )
            row.addView(
                Button(this).apply {
                    text = getString(R.string.btn_clear_image)
                    isEnabled = uploaded
                    setOnClickListener { clearIcon(cat) }
                }
            )
            boxIcons.addView(row)
        }
    }

    /** 预览：传了图就显示图，没传就显示卡片上会用的那个字母块。 */
    private fun iconPreview(cat: Cat, mark: String, uploaded: Boolean): View {
        val size = dp(40f)
        val params = FrameLayout.LayoutParams(size, size)
        return FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            addView(
                TextView(this@MainActivity).apply {
                    text = mark
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setTextColor(color(R.color.on_pill))
                    background = ContextCompat.getDrawable(this@MainActivity, cat.pillRes)
                    visibility = if (uploaded) View.GONE else View.VISIBLE
                },
                params,
            )
            val bmp = LogoStore.cached(cat)
            addView(
                ImageView(this@MainActivity).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageBitmap(bmp)
                    visibility = if (bmp != null) View.VISIBLE else View.GONE
                },
                FrameLayout.LayoutParams(size, size),
            )
        }
    }

    private fun startPick(cat: Cat) {
        pendingCat = cat
        pickImage.launch("image/*")
    }

    private fun clearIcon(cat: Cat) {
        LogoStore.clear(this, cat)
        WidgetProvider.updateAll(this)
        renderAll()
    }

    /**
     * 存图。解码和缩放都放 IO 线程 —— 用户可能挑了一张几 MB 的照片，
     * 在主线程解码会卡住界面甚至 ANR。
     */
    private fun saveIcon(cat: Cat, uri: Uri) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { LogoStore.set(this@MainActivity, cat, uri) }
            if (ok) {
                WidgetProvider.updateAll(this@MainActivity)
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.toast_image_saved),
                    Toast.LENGTH_SHORT,
                ).show()
                renderAll()
            } else {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.toast_image_bad),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun renderSources(data: CalendarData) {
        boxSources.removeAllViews()
        if (data.sources.isEmpty()) {
            boxSources.addView(note("还没有数据。点下面的「测试连接」看能不能拉到。"))
            return
        }
        data.sources.forEach { s ->
            val ok = s.ok
            val state = when {
                !ok -> getString(R.string.source_failed)
                s.count == 0 -> "正常（暂无赛事）"
                else -> getString(R.string.source_ok)
            }
            val head = "${s.cat.label}  ·  $state" +
                    if (s.count > 0) "  ·  ${getString(R.string.source_count, s.count)}" else ""

            boxSources.addView(
                TextView(this).apply {
                    text = if (ok) head else "$head\n${s.error.orEmpty()}"
                    textSize = 12f
                    setTextColor(color(if (ok) R.color.text_secondary else R.color.accent_amber))
                    layoutParams = lp(matchWidth = true).apply { topMargin = dp(6f) }
                }
            )
        }
    }

    // ── 自检 ──────────────────────────────────────────────────────────

    /**
     * 「测试连接」：真的拉一次，把结果的关键信息打出来。
     *
     * 刻意打**结构清单**而不是原始 JSON —— 整份响应几万字，这里放不下，
     * 截前 2000 字又常常正好把关键段落切掉。清单只列路径不列数据，短得多，结构一点不丢。
     * （这个思路是从 deepseek-widget 的 UsageParser 搬过来的。）
     */
    private fun runDiagnostics() {
        tvResult.text = "测试中…"
        scope.launch {
            val report = withContext(Dispatchers.IO) { buildDiagnostics() }
            tvResult.text = report
        }
    }

    private fun buildDiagnostics(): String = buildString {
        val entered = Prefs.getEndpoint(this@MainActivity)
        val endpoints = CalendarClient.endpoints(this@MainActivity)

        if (entered.isNotBlank() && endpoints.first() != entered) {
            // 填了 GitHub 网页地址时会走到这里。不说明的话，用户看着自己填的地址
            // 和实际用的地址不一样会以为出 bug 了。
            appendLine("你填的地址是 GitHub 的网页地址，已自动换成 raw 地址：")
            appendLine("  填的：$entered")
            appendLine("  用  ：${endpoints.first()}")
            appendLine()
        }
        appendLine("尝试的地址（从上往下，第一个成功就停）：")
        endpoints.forEach { appendLine("  · $it") }
        appendLine()

        val raw = try {
            CalendarClient.fetchRaw(this@MainActivity)
        } catch (e: Exception) {
            appendLine("❌ 全部失败：")
            appendLine(e.message)
            appendLine()
            appendLine("排查顺序：")
            appendLine("1. 仓库是不是公开的（私有仓库 CDN 取不到）")
            appendLine("2. data/calendar.json 有没有生成（看 Actions 里 update-calendar 那次运行）")
            appendLine("3. 国内直连 jsdelivr 不稳，可以挂代理，或在上面的输入框里填自己的地址")
            return@buildString
        }

        appendLine("✅ 拉到 ${raw.length} 字节")
        appendLine()

        val now = System.currentTimeMillis()
        val parsed = CalendarParser.parse(raw, now, ZoneId.systemDefault())
        if (parsed == null) {
            if (CalendarClient.looksLikeHtml(raw)) {
                // 最常见的填错：从浏览器地址栏复制的 github.com 网页地址。
                // 说清楚该填什么，否则用户只会看到「拉到 78 万字节」然后一头雾水。
                appendLine("⚠ 拉到的是**网页**，不是数据。")
                appendLine()
                appendLine("多半是地址填成了 GitHub 的网页地址。要用下面这种：")
                appendLine("  ✅ https://raw.githubusercontent.com/<用户名>/<仓库>/main/data/calendar.json")
                appendLine("  ✅ https://cdn.jsdelivr.net/gh/<用户名>/<仓库>@main/data/calendar.json")
                appendLine("  ❌ https://github.com/<用户名>/<仓库>/blob/main/data/calendar.json")
                appendLine()
                appendLine("blob 形式现在会自动纠正，但只认带 /blob/ 的完整路径 ——")
                appendLine("如果你填的是仓库首页之类的地址，请换成上面两条之一。")
            } else {
                appendLine("⚠ 内容不是我们认得的形状，下面是原始结构：")
                appendLine()
                appendLine(CalendarParser.describeStructure(raw))
            }
            return@buildString
        }

        appendLine("共 ${parsed.events.size} 场，落在本地缓存窗口内")
        parsed.sources.forEach { s ->
            appendLine("  ${s.cat.label}: ${if (s.ok) "正常" else "失败"} ${s.count} 场 ${s.error.orEmpty()}")
        }
        appendLine()
        appendLine("小组件会显示成这些卡片：")
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val cards = CalendarParser.buildCards(parsed, now, 20)
        if (cards.isEmpty()) appendLine("  （没有待进行的赛事）")
        cards.forEach { c ->
            // 注意是 this@MainActivity：这整段在 buildString { } 里，
            // 裸 this 指的是 StringBuilder，不是 Activity
            val icon = if (LogoStore.has(this@MainActivity, c.cat)) "图标" else "字母块 ${c.mark}"
            appendLine("  [${c.cat.key}] ${c.title}  ${formatCardTime(c.nextStartMs, today, zone)}")
            appendLine("        ${c.detail}   （$icon）")
        }
        appendLine()
        appendLine("图标没上传就用字母块 —— 上面 ④ 里可以给每个分组传一张。")
        appendLine()
        appendLine("返回结构：")
        appendLine(CalendarParser.describeStructure(raw, limit = 25))
    }

    // ── 小组件用不到的绘制小工具 ──────────────────────────────────────

    private fun eventRow(e: Event, zone: ZoneId, past: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_card)
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            layoutParams = lp(matchWidth = true).apply { topMargin = dp(6f) }
            alpha = if (past) 0.5f else 1f
        }

        // 左侧色条：3dp 宽，颜色就是小组件里那个类别色
        row.addView(
            View(this).apply {
                setBackgroundColor(color(e.cat.dotColorRes))
                layoutParams = LinearLayout.LayoutParams(dp(3f), ViewGroup.LayoutParams.MATCH_PARENT)
            }
        )

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(10f) }
        }
        col.addView(
            TextView(this).apply {
                text = e.title
                textSize = 13f
                setTextColor(color(R.color.text_primary))
            }
        )
        val when_ = Instant.ofEpochMilli(e.startMs).atZone(zone).format(timeFmt)
        col.addView(
            TextView(this).apply {
                text = "$when_  ·  ${e.cat.label}" + if (past) "  ·  已结束" else ""
                textSize = 10f
                setTextColor(color(R.color.text_muted))
            }
        )
        row.addView(col)
        return row
    }

    private fun dot(cat: Cat): TextView = TextView(this).apply {
        text = "●"
        textSize = 14f
        setTextColor(color(cat.dotColorRes))
        gravity = Gravity.CENTER
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(color(R.color.text_secondary))
        setTypeface(typeface, Typeface.BOLD)
        layoutParams = lp(matchWidth = true).apply { topMargin = dp(12f) }
    }

    private fun note(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(color(R.color.text_muted))
        layoutParams = lp(matchWidth = true).apply { topMargin = dp(6f) }
    }

    private fun dayLabel(today: LocalDate, date: LocalDate): String = when (date) {
        today -> "今天  ${dateFmt.format(date)}  ${WDAY_CN[date.dayOfWeek.value - 1]}"
        today.plusDays(1) -> "明天  ${dateFmt.format(date)}  ${WDAY_CN[date.dayOfWeek.value - 1]}"
        else -> "${WDAY_CN[date.dayOfWeek.value - 1]}  ${dateFmt.format(date)}"
    }

    private fun color(resId: Int): Int = ContextCompat.getColor(this, resId)

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    private fun lp(matchWidth: Boolean = false): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            if (matchWidth) ViewGroup.LayoutParams.MATCH_PARENT
            else ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
}
