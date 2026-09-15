package com.dailywork.sportswidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
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
 *   - [WidgetRenderer]：只管把顶栏和七天条画出来、把列表接上，不知道谁调用的它
 *
 * 卡片本身的内容由 [WidgetService] / [WidgetFactory] 提供 ——
 * 因为 ListView 的行是启动器按需来取的，没法一次性画进去。
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

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle,
    ) {
        // 列表本身能滚动，高度变了不用改内容，但七天条里的「今天」和卡片上的
        // 时间文案（今天/明天/周六）要按当前时刻重算一次，所以还是重画。
        WidgetRenderer.render(context, appWidgetManager, appWidgetId)
    }

    override fun onEnabled(context: Context) {
        RefreshScheduler.schedule(context, immediate = true)
    }

    override fun onDisabled(context: Context) {
        // 桌面上一个小组件都不剩了，停掉后台任务省电。
        // 注意这是「最后一个被移除」时才触发，不是每次移除。
        // 两条线都要停：拉数据的和触发 Actions 的。
        RefreshScheduler.cancel(context)
        TriggerScheduler.cancel(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            // 刷新：只拉一份现成的 calendar.json，不碰 Actions
            ACTION_REFRESH -> {
                flashStatus(context, R.string.status_refreshing)
                RefreshScheduler.schedule(context, immediate = true)
            }

            // 抓取：只让 GitHub 现在开跑，**不拉数据**。
            // 结果（成功 / 失败原因）由 TriggerRunner 写完再重画一次状态行
            ACTION_TRIGGER -> {
                flashStatus(context, R.string.status_triggering)
                TriggerScheduler.schedule(context)
            }

            // 跨零点 / 手动改时间 / 换时区：只按新时区重算「今天」和卡片时间文案，**不联网**。
            // WorkManager 的周期任务在 Doze 下会被合并，息屏一整夜可能到早上才跑，
            // 中间这段时间七天条里的「今天」还是昨天，光靠周期任务兜不住。
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> updateAll(context)
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.dailywork.sportswidget.ACTION_REFRESH"
        const val ACTION_TRIGGER = "com.dailywork.sportswidget.ACTION_TRIGGER"

        /**
         * 点完按键先把状态行换掉，给一个即时反馈，真正的活交给 WorkManager。
         *
         * 走 [WidgetRenderer.render] 的 statusOverride 而**不是**写进 Prefs 的
         * [Notice]：这一句是瞬时的，没有任何人负责把它清掉。写进 notice 的话，
         * 一旦后台任务因为没网迟迟排不上，小组件就会永远停在「刷新中…」上。
         * override 只活在这一次渲染里 —— 下一次任何重画（Worker 跑完、跨零点、
         * 启动器重新绑上来的 onUpdate）都会自动回到正常状态。
         */
        private fun flashStatus(context: Context, stringRes: Int) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, WidgetProvider::class.java))
            ids.forEach {
                WidgetRenderer.render(
                    context, mgr, it,
                    statusOverride = context.getString(stringRes),
                )
            }
        }

        /**
         * 把桌面上所有小组件重画一遍（不联网，只重新分组已有的数据）。
         *
         * 两件事都要做：
         *   · updateAppWidget  —— 顶栏和七天条是静态控件，直接改它们
         *   · notifyAppWidgetViewDataChanged —— 列表里的卡片由 Factory 提供，
         *     不通知的话它不会重新取数，卡片会一直是旧的
         */
        fun updateAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, WidgetProvider::class.java))
            // 桌面上没有小组件时提前返回，省掉一次 SharedPreferences 读取
            if (ids.isEmpty()) return
            ids.forEach { WidgetRenderer.render(context, mgr, it) }
            notifyCardsChanged(context, mgr, ids)
        }

        /** 让启动器重新向 [WidgetService] 要一遍卡片数据。 */
        fun notifyCardsChanged(context: Context, mgr: AppWidgetManager, ids: IntArray) {
            if (ids.isEmpty()) return
            // 这个方法名长但就是它：告诉 AppWidgetManager 这个集合控件的数据变了
            mgr.notifyAppWidgetViewDataChanged(ids, android.R.id.list)
        }
    }
}

/**
 * 画顶栏、七天迷你条，以及把 ListView 接到 [WidgetService] 上。
 *
 * ── 可滚动的实现要点 ──────────────────────────────────────────────────
 * 小组件的集合控件不是用 addView 塞进去的，而是：
 *   1. 布局里放一个 id 为 @android:id/list 的 ListView
 *   2. setRemoteAdapter 把它的数据源指向一个 RemoteViewsService
 *   3. 行内容由那个 service 的 Factory 逐行提供
 *   4. 数据变了要调 notifyAppWidgetViewDataChanged 通知重新取
 */
object WidgetRenderer {

    private val DAY_IDS = intArrayOf(
        R.id.tv_day_0, R.id.tv_day_1, R.id.tv_day_2, R.id.tv_day_3,
        R.id.tv_day_4, R.id.tv_day_5, R.id.tv_day_6,
    )

    /** 数据超过这么久没更新，顶栏转琥珀色提醒。 */
    private const val STALE_AFTER_MS = 24 * 60 * 60 * 1000L

    private val STAMP_FMT = DateTimeFormatter.ofPattern("M/d HH:mm", Locale.US)

    fun render(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        statusOverride: String? = null,
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_sports)
        val data = Prefs.loadData(context)
        val now = System.currentTimeMillis()

        // 确保用户上传的图标在内存里 —— 卡片那边只查内存，不读盘。
        // 已经在内存里的话这里是一次空转，成本可以忽略；而少了这一句，
        // 手机重启后（进程被清掉）卡片会一直显示字母块，要等下一次周期刷新才恢复。
        LogoStore.warmUp(context)

        applyDayStrip(context, views, data)
        applyStatus(context, views, data, now, statusOverride)
        attachList(context, views, widgetId)
        applyClicks(context, views)

        manager.updateAppWidget(widgetId, views)
        // 顶栏更新完还要通知列表重新取卡片，否则卡片一直是旧的
        WidgetProvider.notifyCardsChanged(context, manager, intArrayOf(widgetId))
    }

    // ── 顶部七天迷你条 ────────────────────────────────────────────────

    /**
     * 「这七天里哪天有比赛」。
     *
     * 卡片流回答的是「比什么赛」，这条回答的是「哪天有比赛」—— 两个不同的问题。
     * 没有它的话，一眼看过去完全不知道哪天是空的。
     */
    private fun applyDayStrip(context: Context, views: RemoteViews, data: CalendarData) {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val hasEvents = CalendarParser.buildDayStrip(data, today, zone)

        hasEvents.forEachIndexed { i, has ->
            val date = today.plusDays(i.toLong())
            // 今天固定写「今」，其余写星期几（去掉「周」字，一格只有十几 dp）
            val label = if (i == 0) "今" else WDAY_CN[date.dayOfWeek.value - 1].removePrefix("周")

            views.setTextViewText(DAY_IDS[i], label)
            views.setInt(
                DAY_IDS[i], "setBackgroundResource",
                if (has) R.drawable.bg_daychip_on else R.drawable.bg_daychip_off,
            )
            views.setTextColor(
                DAY_IDS[i],
                ContextCompat.getColor(
                    context,
                    when {
                        // 今天最重，有比赛次之，没比赛最轻 —— 三级区分
                        i == 0 -> R.color.accent_text
                        has -> R.color.text_primary
                        else -> R.color.text_muted
                    },
                ),
            )
        }
    }

    // ── 列表接线 ──────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun attachList(context: Context, views: RemoteViews, widgetId: Int) {
        // data 设成自己的 URI，让每个小组件实例拿到独立的 service 连接。
        // 不这么做的话，桌面放了两个小组件时它们会互相串数据。
        val svcIntent = Intent(context, WidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
        }

        // setRemoteAdapter(int, Intent) 在 API 31 被标记为废弃（换成了
        // RemoteCollectionItems 那套），但**仍然可用**，而且兼容 minSdk 26。
        // 新 API 要求 31+，用了就得写两套，不值得。
        views.setRemoteAdapter(android.R.id.list, svcIntent)

        // 一张卡都没有时显示提示文字，靠系统自动切换显隐，不用手写判断
        views.setEmptyView(android.R.id.list, R.id.tv_empty)

        // 集合控件里的点击必须走「模板 + 每项填充」这条路：
        // 模板挂在 ListView 上，点哪一项都会带着那一项的填充信息发出去。
        // 这里不需要区分点了哪张卡（都是打开 App），所以不设 fillInIntent。
        views.setPendingIntentTemplate(
            android.R.id.list,
            PendingIntent.getActivity(
                context, 2,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
    }

    // ── 顶栏状态 ──────────────────────────────────────────────────────

    /**
     * ⚠️ 显示的是**数据生成时间**（后端写进 JSON 的 generated_at），不是手机最后拉取的时间。
     *
     * 这个区别很关键：Actions 挂掉之后，手机每 6 小时照样能成功拉到同一份**旧** JSON
     * （CDN 有缓存，HTTP 200，一切正常）。如果显示「最后拉取时间」，界面上永远看着是新鲜的，
     * 「后端停了」这个问题会被完全掩盖。
     */
    private fun applyStatus(
        context: Context,
        views: RemoteViews,
        data: CalendarData,
        now: Long,
        statusOverride: String?,
    ) {
        // 只有「拿到过数据、但已经过期」才算异常。
        // 从没获取过是另一回事（还没配好），那时候报警色只会让人以为是坏了。
        val hasData = data.generatedAtMs > 0L
        val stale = hasData && stalenessMs(data.generatedAtMs, now) > STALE_AFTER_MS

        // 上一次点「抓取」的结果。有它就压过时间戳 —— 它存在的意义就是告诉用户
        // 触发到底成没成，而这件事只在用户下次点「刷新」之前有意义
        // （清掉的地方在 CalendarRefresher.refresh）。
        val notice = Prefs.getNotice(context)

        // 一个文案配一个「要不要琥珀色」。琥珀色是**异常**的专用色，
        // 绿色的时间戳、灰色的「刷新中…」都不该借它用。
        val (text, amber) = when {
            // 1. 刚点完按键的瞬时反馈
            statusOverride != null -> statusOverride to false
            // 2. 上次「抓取」的结果
            notice != null -> notice.text to notice.failed
            // 3. 从没获取过数据 —— 还没配好，不报警色
            !hasData -> context.getString(R.string.status_never) to false
            // 4. 正常：数据生成时间，超过 24 小时转琥珀
            else -> {
                val stamp = Instant.ofEpochMilli(data.generatedAtMs)
                    .atZone(ZoneId.systemDefault())
                    .format(STAMP_FMT)
                "${if (stale) "⚠ " else ""}$stamp" to stale
            }
        }

        views.setTextViewText(R.id.tv_status, text)
        views.setTextColor(
            R.id.tv_status,
            ContextCompat.getColor(context, if (amber) R.color.accent_amber else R.color.text_muted),
        )
    }

    // ── 点击 ──────────────────────────────────────────────────────────

    /**
     * 两个按键各挂一个广播，中间和四周点空白处打开 App。
     *
     * 以前「刷新」是挂在**整条顶栏**上的（那时顶栏只有一个动作，整条可点等于把
     * 触控区从 18dp 放大到整条）。现在有两个动作，整条可点就等于两个都不准，
     * 所以点击收回到按钮自己身上，防误触改由「两个按钮离得远」来保证 ——
     * 中间隔着整条七天条，而它没有任何点击事件。
     */
    private fun applyClicks(context: Context, views: RemoteViews) {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        // 请求码 1 / 3 给两个广播，2 给下面那个打开 App 的 —— 不能撞，
        // 撞了的话 FLAG_UPDATE_CURRENT 会让后写的把先写的悄悄顶掉
        views.setOnClickPendingIntent(
            R.id.btn_refresh,
            PendingIntent.getBroadcast(
                context, 1,
                Intent(context, WidgetProvider::class.java)
                    .setAction(WidgetProvider.ACTION_REFRESH),
                flags,
            ),
        )
        views.setOnClickPendingIntent(
            R.id.btn_trigger,
            PendingIntent.getBroadcast(
                context, 3,
                Intent(context, WidgetProvider::class.java)
                    .setAction(WidgetProvider.ACTION_TRIGGER),
                flags,
            ),
        )

        // 按键以外的地方（七天条、四周那圈内边距）点了打开 App。
        // 卡片自己有 fill-in intent 会先接管，所以这里不会和它们抢 ——
        // 加上它是为了让「随便点小组件哪里都能进 App」，不然只有卡片那一小块有反应。
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
