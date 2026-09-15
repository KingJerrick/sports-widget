package com.dailywork.sportswidget

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
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
import androidx.appcompat.app.AlertDialog
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
    private lateinit var etGhToken: EditText
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
        openCropper(cat, uri)
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
        etGhToken = findViewById(R.id.et_gh_token)
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

        findViewById<Button>(R.id.btn_save).setOnClickListener { saveSettings() }
        findViewById<Button>(R.id.btn_refresh).setOnClickListener { refreshNow() }
        findViewById<Button>(R.id.btn_test).setOnClickListener { runDiagnostics() }
        findViewById<Button>(R.id.btn_trigger).setOnClickListener { triggerFetch() }

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
        etGhToken.setText(Prefs.getGithubToken(this))
        val minutes = Prefs.getIntervalMinutes(this)
        // 找不到就把最接近的选项选上（用户手改过 SharedPreferences 时也不会崩）
        spInterval.setSelection(intervalOptions.indexOf(minutes).takeIf { it >= 0 } ?: 2)
    }

    /** 「保存」：只存设置 + 按新间隔重排周期任务，**不联网**。 */
    private fun saveSettings() {
        Prefs.setEndpoint(this, etEndpoint.text.toString())
        Prefs.setGithubToken(this, etGhToken.text.toString())
        Prefs.setIntervalMinutes(this, intervalOptions[spInterval.selectedItemPosition])
        // 不带 immediate：想立刻要新数据是「刷新」那个按键的事。
        // 改完间隔要重排周期任务，所以这一句不能省。
        RefreshScheduler.schedule(this)
        Toast.makeText(this, getString(R.string.toast_saved), Toast.LENGTH_SHORT).show()
    }

    /** 「刷新」：只去 GitHub 拉一份现成的 calendar.json，**不碰 Actions**。 */
    private fun refreshNow() {
        RefreshScheduler.schedule(this, immediate = true)
        Toast.makeText(this, getString(R.string.toast_refreshing), Toast.LENGTH_SHORT).show()
        // 拉完状态行上的「数据生成于…」会往前走，但这里没法知道什么时候拉完，
        // 只能到点重画一次碰运气。没赶上也不要紧，onResume 会再刷一遍。
        tvStatusLine.postDelayed({ renderAll() }, 3000)
    }

    /**
     * 「立即触发 GitHub 抓取」：只会触发，**不拉数据**。
     *
     * 以前这里还顺手做两件事 —— 立刻拉一次、再排一个「2 分钟后自动回来取」的
     * 延时任务。两个都去掉了：
     *   · 那个延时任务要靠 WorkManager 的 setInitialDelay 加网络约束来兑现，
     *     而 Doze 和各家的省电策略想推迟就推迟，实测经常根本不执行，
     *     表现是「点了触发、GitHub 也真跑了，但手机上永远没自动拿到新数据」。
     *   · 「立刻拉一次」在触发刚发出去时拉到的一定还是旧数据，本来就没用。
     *
     * 现在的约定很简单：点完这个按钮，等一两分钟，再去点「刷新」。
     * [TriggerRunner] 会把结果同时写给这里和小组件。
     */
    private fun triggerFetch() {
        // 先把 token 存下来 —— 用户多半是刚填完就直接点这个按钮的，
        // 而存 token 原本只发生在「保存」里
        Prefs.setGithubToken(this, etGhToken.text.toString())

        tvResult.text = TriggerRunner.PENDING
        scope.launch {
            val result = TriggerRunner.run(this@MainActivity)
            if (result.ok) {
                tvResult.text = getString(R.string.toast_trigger_ok)
                Toast.makeText(
                    this@MainActivity, getString(R.string.toast_trigger_ok), Toast.LENGTH_LONG,
                ).show()
            } else {
                // 失败原因是人话（没配 token / 权限不够 / 认不出仓库），直接显示
                tvResult.text = "❌ ${result.error}"
            }
        }
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
                boxSchedule.addView(eventRow(e, zone, e.statusAt(now)))
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
     * 选完图先解码出来，交给取景框让用户自己框。
     *
     * 解码放 IO 线程 —— 用户可能挑了一张几 MB 的照片，在主线程解码会卡住界面甚至 ANR。
     */
    private fun openCropper(cat: Cat, uri: Uri) {
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { decodeForCrop(uri) }
            if (bmp == null) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.toast_image_bad),
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }
            showCropDialog(cat, bmp)
        }
    }

    /**
     * 取景对话框。
     *
     * 需要它的原因：用户拿来当图标的常常是**从网上截的图**，标根本不在正中间；
     * 而且直接等比缩放的话，周围留白一多，缩到 28dp 的卡片上就只剩一团糊。
     * 挪一挪比程序去猜怎么裁靠谱。
     */
    private fun showCropDialog(cat: Cat, bmp: Bitmap) {
        val side = dp(260f)
        val crop = IconCropView(this).apply {
            layoutParams = FrameLayout.LayoutParams(side, side)
            setBitmap(bmp)
        }
        val holder = FrameLayout(this).apply {
            setPadding(dp(20f), dp(12f), dp(20f), dp(4f))
            addView(crop)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.crop_title)
            .setMessage(R.string.crop_hint)
            .setView(holder)
            .setPositiveButton(R.string.crop_ok) { _, _ ->
                commitCrop(cat, crop)
            }
            .setNeutralButton(R.string.crop_reset, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        // 「重置」不能关掉对话框（默认的 neutral 按钮会关），
        // 所以这里把它的点击抢过来自己处理
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { crop.resetToCenter() }
        }
        dialog.show()
    }

    /** 导出用户框好的区域并存下来。 */
    private fun commitCrop(cat: Cat, crop: IconCropView) {
        val square = crop.exportSquare(LogoStore.TARGET_PX)
        scope.launch {
            val ok = square != null &&
                    withContext(Dispatchers.IO) { LogoStore.save(this@MainActivity, cat, square) }
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

    /**
     * 解码出一张够用来框选的图。
     *
     * 只按上限采样，**不做正方形裁剪** —— 怎么裁由用户在取景框里决定。
     * 上限 1024 是因为框选时只需要看得清，用不着原图的分辨率；
     * 直接解一张 4000×3000 的照片进来，光位图就 48MB，很容易 OOM。
     */
    private fun decodeForCrop(uri: Uri): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= CROP_MAX_PX &&
            bounds.outHeight / (sample * 2) >= CROP_MAX_PX
        ) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        applyExifRotation(uri, raw)
    }.getOrNull()

    /**
     * 按 EXIF 里的方向信息把图转正。
     *
     * 相机拍的照片是躺着存的，方向只写在 EXIF 里，`BitmapFactory` 不会自动应用 ——
     * 不转的话用户选完图会看到自己的照片横着。
     */
    private fun applyExifRotation(uri: Uri, bmp: Bitmap): Bitmap {
        val orientation = runCatching {
            contentResolver.openInputStream(uri)?.use { stream ->
                @Suppress("DEPRECATION")
                android.media.ExifInterface(stream).getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL,
                )
            }
        }.getOrNull() ?: android.media.ExifInterface.ORIENTATION_NORMAL

        val m = Matrix()
        when (orientation) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            else -> return bmp
        }
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (out !== bmp) bmp.recycle()
        return out
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

    private fun eventRow(e: Event, zone: ZoneId, status: EventStatus): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_card)
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            layoutParams = lp(matchWidth = true).apply { topMargin = dp(6f) }
            // 打完的整行压暗。这里用的是整行 alpha，和小组件那边换卡片底色的做法
            // 不一样 —— 这个列表是自己的布局，不是 RemoteViews，没有「不能运行时着色」的限制
            alpha = if (status == EventStatus.FINISHED) 0.5f else 1f
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
        // 第二行：时间 · 类别 · 状态（+ 赛果）
        val tail = when (status) {
            EventStatus.UPCOMING -> ""
            EventStatus.LIVE -> "  ·  进行中"
            // 赛果可能没有（MotoGP 拿不到冠军、刚打完还没回填比分），那时只写「已结束」
            EventStatus.FINISHED -> "  ·  已结束" + (e.result?.let { "  $it" } ?: "")
        }
        col.addView(
            TextView(this).apply {
                text = "$when_  ·  ${e.cat.label}$tail"
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

    private companion object {
        /**
         * 取景时解码的最大边长。
         *
         * 只要看得清就行，用不着原图分辨率 —— 直接解一张 4000×3000 的照片进来，
         * 光位图就 48MB，很容易 OOM。
         */
        const val CROP_MAX_PX = 1024
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
