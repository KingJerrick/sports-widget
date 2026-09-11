package com.dailywork.sportswidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 小组件的生命周期与调度。
 *
 * 这个文件里有两个顶层声明，职责严格分开：
 *   - [WidgetProvider]：只管生命周期回调、广播、调度，不碰 RemoteViews 的细节
 *   - [WidgetRenderer]：只管把数据画到 RemoteViews 上，不知道谁调用的它
 *
 * 这么分是因为渲染逻辑（尺寸分档、chip 怎么选、颜色怎么上）比生命周期长得多，
 * 混在一起以后没法读。
 */
class WidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // ⚠️ 这里**不能**因为「还没拿到数据」就提前 return。
        // 系统只会因为我们调了 updateAppWidget 才把 initialLayout 换掉，
        // 提前返回的话桌面会永远停在 initialLayout 上（一片空白），
        // 而且不报错、不崩，只是永远不显示。空态也必须画一次。
        appWidgetIds.forEach {
            WidgetRenderer.render(context, appWidgetManager, it)
        }
        // 系统拉起小组件时顺便刷一次
        RefreshScheduler.schedule(context, immediate = true)
    }

    /**
     * 用户拖动改尺寸时回调。
     *
     * 必须重画 —— 每天显示几条是按当前高度算的（见 [WidgetRenderer.maxChipsFor]），
     * 不重画的话要等下一次周期刷新才变，中间那段时间用户看到的是被裁掉一半的布局。
     */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle,
    ) {
        WidgetRenderer.render(context, appWidgetManager, appWidgetId)
    }

    override fun onEnabled(context: Context) {
        RefreshScheduler.schedule(context, immediate = true)
    }

    override fun onDisabled(context: Context) {
        // 桌面上一个小组件都不剩了，停掉后台任务省电。
        // 注意这是「最后一个被移除」时才触发，不是每次移除。
        RefreshScheduler.cancel(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH -> {
                // 先给出「刷新中…」的即时反馈，联网交给 WorkManager
                val mgr = AppWidgetManager.getInstance(context)
                val ids = mgr.getAppWidgetIds(ComponentName(context, WidgetProvider::class.java))
                ids.forEach {
                    WidgetRenderer.render(
                        context, mgr, it,
                        statusOverride = context.getString(R.string.status_loading),
                    )
                }
                RefreshScheduler.schedule(context, immediate = true)
            }

            // 跨零点 / 手动改时间 / 换时区：只按新时区把「今天」列挪对位置，**不联网**。
            // WorkManager 的周期任务在 Doze 下会被合并，息屏一整夜可能到早上才跑，
            // 中间这段时间高亮的「今天」还是昨天，光靠周期任务兜不住。
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> updateAll(context)
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.dailywork.sportswidget.ACTION_REFRESH"

        /** 把桌面上所有小组件重画一遍（不联网，只重新分组已有的数据）。 */
        fun updateAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, WidgetProvider::class.java))
            // 桌面上没有小组件时提前返回，省掉一次 SharedPreferences 读取
            if (ids.isEmpty()) return
            ids.forEach { WidgetRenderer.render(context, mgr, it) }
        }
    }
}

/**
 * 把「未来七天」画到 RemoteViews 上。
 *
 * ── 为什么 21 个 chip 是写死在布局里的，而不是 addView 动态挂 ──────────────
 * 见 widget_sports.xml 顶部注释的「RemoteViews 三条红线」第 3 条。
 * 简单说：addView 会让每次更新产生 21 次嵌套 inflate，全在启动器主线程上，
 * 而且嵌套根布局的 margin 在部分启动器上会被丢掉。
 */
object WidgetRenderer {

    /** 7 列 × 3 条，与布局里的 chip_<列>_<序> 对应。 */
    private val CHIP_IDS = arrayOf(
        intArrayOf(R.id.chip_0_0, R.id.chip_0_1, R.id.chip_0_2),
        intArrayOf(R.id.chip_1_0, R.id.chip_1_1, R.id.chip_1_2),
        intArrayOf(R.id.chip_2_0, R.id.chip_2_1, R.id.chip_2_2),
        intArrayOf(R.id.chip_3_0, R.id.chip_3_1, R.id.chip_3_2),
        intArrayOf(R.id.chip_4_0, R.id.chip_4_1, R.id.chip_4_2),
        intArrayOf(R.id.chip_5_0, R.id.chip_5_1, R.id.chip_5_2),
        intArrayOf(R.id.chip_6_0, R.id.chip_6_1, R.id.chip_6_2),
    )

    private val WDAY_IDS = intArrayOf(
        R.id.tv_wday_0, R.id.tv_wday_1, R.id.tv_wday_2, R.id.tv_wday_3,
        R.id.tv_wday_4, R.id.tv_wday_5, R.id.tv_wday_6,
    )

    private val DATE_IDS = intArrayOf(
        R.id.tv_date_0, R.id.tv_date_1, R.id.tv_date_2, R.id.tv_date_3,
        R.id.tv_date_4, R.id.tv_date_5, R.id.tv_date_6,
    )

    /** 一列最多放几条，见 widget_sports.xml 的尺寸账。 */
    const val MAX_CHIPS_PER_DAY = 3

    /** 启动器没报高度时的兜底。宁可按小的算 —— 少了只是空一点，多了会被裁。 */
    private const val DEFAULT_HEIGHT_DP = 100

    /**
     * widget_sports.xml 里不随高度变化的部分：
     * 根 padding 16 + 顶栏 18 + 间距 4 + 星期行 10 + 日期行 14 + 间距 4 = 66dp。
     * 改布局里那些值时这里要跟着改。
     */
    private const val FIXED_HEIGHT_DP = 66

    /** 每条 chip 连间距约占的高度：13dp 高 + 2dp 间距。 */
    private const val CHIP_SLOT_DP = 15

    /** 数据超过这么久没更新，顶栏转琥珀色提醒。 */
    private const val STALE_AFTER_MS = 24 * 60 * 60 * 1000L

    /**
     * chip 上色的两条路。
     *
     * `setBackgroundResource` 走的是 RemoteViews 的 `setInt` 反射通道，
     * `View.setBackgroundResource` 带 `@RemotableViewMethod` 注解，API 26+ 上正常，
     * 而且圆角由 drawable 保证、深浅色由资源系统自动选，是最干净的做法。
     *
     * **万一**在某个 ROM 上发现 chip 没颜色、桌面显示「载入小组件时出现问题」，
     * 把这里改成 false 就会退回 `setBackgroundColor`（确定可用）——
     * 代价是丢掉 3dp 圆角，功能不受影响。改动就这一行。
     */
    private const val CHIP_USE_BACKGROUND_RESOURCE = true

    private val STAMP_FMT = DateTimeFormatter.ofPattern("M/d HH:mm", Locale.US)
    private val WDAY_NAMES = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    fun render(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        statusOverride: String? = null,
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_sports)
        val data = Prefs.loadData(context)
        val now = System.currentTimeMillis()

        applyDays(context, views, manager, widgetId, data, now)
        applyStatus(context, views, data, now, statusOverride)
        applyClicks(context, views)

        manager.updateAppWidget(widgetId, views)
    }

    /**
     * 按当前的高度决定这一列放几条 chip。
     *
     * 为什么要算而不是写死 3 条：`OPTION_APPWIDGET_MIN_HEIGHT` 在不同启动器上
     * 报的值差别很大（同一个 4×2，AOSP 报 110dp，MIUI 可能报 100 或 130），
     * 而小组件不会滚动、也不会自己长高 —— 多出来的那条就是被裁掉。
     */
    fun maxChipsFor(reportedHeightDp: Int): Int {
        val heightDp = reportedHeightDp.takeIf { it > 0 } ?: DEFAULT_HEIGHT_DP
        val room = heightDp - FIXED_HEIGHT_DP
        return (room / CHIP_SLOT_DP).coerceIn(1, MAX_CHIPS_PER_DAY)
    }

    // ── 内部实现 ──────────────────────────────────────────────────────

    private fun applyDays(
        context: Context,
        views: RemoteViews,
        manager: AppWidgetManager,
        widgetId: Int,
        data: CalendarData,
        now: Long,
    ) {
        val heightDp = manager.getAppWidgetOptions(widgetId)
            .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        val maxChips = maxChipsFor(heightDp)

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val week = CalendarParser.buildWeek(data, today, maxChips, zone)

        week.forEachIndexed { d, plan ->
            views.setTextViewText(WDAY_IDS[d], weekdayLabel(context, today, plan.date))
            // 只放日号。写成 "9/11" 的话是 5 个字符的 monospace，11dp 下要 33dp，
            // 而每列只有 31.7dp —— 必被省略号截掉。
            views.setTextViewText(DATE_IDS[d], plan.date.dayOfMonth.toString())

            var slot = 0
            plan.chips.forEach { e ->
                if (slot < MAX_CHIPS_PER_DAY) {
                    applyChip(context, views, CHIP_IDS[d][slot], e.cat, e.isPast(now))
                    views.setTextViewText(CHIP_IDS[d][slot], e.short)
                    slot++
                }
            }
            // 项目数超过槽位时，最后一条位置换成「+N」，表示还有别的项目没显示。
            // 这一条不参与类别着色，用中性灰。
            if (plan.hiddenCats > 0 && slot < MAX_CHIPS_PER_DAY) {
                val id = CHIP_IDS[d][slot]
                views.setViewVisibility(id, View.VISIBLE)
                views.setTextViewText(id, "+${plan.hiddenCats}")
                views.setInt(id, "setBackgroundResource", R.drawable.bg_pill_more)
                views.setTextColor(id, ContextCompat.getColor(context, R.color.on_pill))
                views.setFloat(id, "setAlpha", 1f)
                slot++
            }

            for (s in slot until MAX_CHIPS_PER_DAY) {
                views.setViewVisibility(CHIP_IDS[d][s], View.GONE)
            }
        }
    }

    private fun applyChip(context: Context, views: RemoteViews, id: Int, cat: Cat, past: Boolean) {
        views.setViewVisibility(id, View.VISIBLE)

        if (CHIP_USE_BACKGROUND_RESOURCE) {
            // 5 个预置 drawable 来回切。不能用运行时 tint —— 那条路（setBackgroundTintList /
            // setColorStateList）是 API 31 才可靠的，minSdk 26 上会直接抛异常。
            // 详见 bg_dot.xml 的说明。
            views.setInt(id, "setBackgroundResource", cat.pillRes)
        } else {
            // 兜底：丢圆角，但一定不抛异常
            views.setInt(id, "setBackgroundColor", ContextCompat.getColor(context, cat.dotColorRes))
        }
        views.setTextColor(id, ContextCompat.getColor(context, R.color.on_pill))

        // 今天已经打完的场次压暗，一眼区分「还有的看」和「已经过去了」。
        // setAlpha 是 @RemotableViewMethod，可以用。
        views.setFloat(id, "setAlpha", if (past) 0.45f else 1f)
    }

    /** 今天 / 明天 / 周X */
    private fun weekdayLabel(context: Context, today: LocalDate, date: LocalDate): String =
        when (date) {
            today -> context.getString(R.string.wday_today)
            today.plusDays(1) -> context.getString(R.string.wday_tomorrow)
            else -> WDAY_NAMES[date.dayOfWeek.value - 1]
        }

    /**
     * 顶栏那行状态。
     *
     * ⚠️ 显示的是**数据生成时间**（后端写进 JSON 的 generated_at），不是手机最后拉取的时间。
     *
     * 这个区别很关键：Actions 挂掉之后，手机每 6 小时照样能成功拉到同一份**旧** JSON
     * （CDN 有缓存，HTTP 200，一切正常）。如果显示「最后拉取时间」，界面上永远看着是新鲜的，
     * 「后端停了」这个问题会被完全掩盖。显示数据自己的生成时间才暴露得出来。
     */
    private fun applyStatus(
        context: Context,
        views: RemoteViews,
        data: CalendarData,
        now: Long,
        statusOverride: String?,
    ) {
        // 只有「**拿到过**数据、但数据已经过期」才算异常。
        // 从没获取过是另一回事（还没配好），那时候报警色只会让人以为是坏了。
        val hasData = data.generatedAtMs > 0L
        val stale = hasData && stalenessMs(data.generatedAtMs, now) > STALE_AFTER_MS

        val text = when {
            statusOverride != null -> statusOverride
            !hasData -> context.getString(R.string.status_never)
            else -> {
                val stamp = Instant.ofEpochMilli(data.generatedAtMs)
                    .atZone(ZoneId.systemDefault())
                    .format(STAMP_FMT)
                "${if (stale) "⚠ " else ""}$stamp 更新"
            }
        }

        views.setTextViewText(R.id.tv_status, text)
        views.setTextColor(
            R.id.tv_status,
            ContextCompat.getColor(
                context,
                if (stale && statusOverride == null) R.color.accent_amber else R.color.text_muted,
            ),
        )
    }

    private fun applyClicks(context: Context, views: RemoteViews) {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        // 刷新挂在**整条顶栏**上，不是那个 18dp 的图标 ——
        // 18dp 低于 48dp 的最小触控区，边缘点击会被启动器的缩放手柄吃掉。
        views.setOnClickPendingIntent(
            R.id.header,
            PendingIntent.getBroadcast(
                context, 1,
                Intent(context, WidgetProvider::class.java).setAction(WidgetProvider.ACTION_REFRESH),
                flags,
            ),
        )

        views.setOnClickPendingIntent(
            R.id.widget_root,
            PendingIntent.getActivity(
                context, 2,
                Intent(context, MainActivity::class.java),
                flags,
            ),
        )
    }
}
