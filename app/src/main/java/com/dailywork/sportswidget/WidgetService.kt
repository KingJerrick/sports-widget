package com.dailywork.sportswidget

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.ZoneId

/**
 * 给小组件里的 ListView 供数据。
 *
 * 安卓的小组件只有 ListView / GridView 这类「集合控件」能滚动，而集合控件的数据
 * 不能直接塞进 RemoteViews，得由一个 RemoteViewsService 提供 —— 就是这里。
 *
 * 这个 service 跑在**我们自己的进程**里，由启动器通过 Binder 调用，
 * 所以 getViewAt() 里能读 SharedPreferences、能用缓存好的位图。
 */
class WidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        WidgetFactory(applicationContext)
}

/**
 * 一行一张卡片。
 *
 * ⚠️ 这个类的方法是在 **binder 线程**上被调用的，不是主线程：
 *   · 可以读 SharedPreferences（数据量很小，见 Prefs 的说明）
 *   · **但绝不能联网** —— 启动器在等这一行，联网会把它卡住
 * 所以图标只查内存缓存（[LogoStore.cached]），真正的下载在 [CalendarRefresher] 里做。
 */
class WidgetFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {

    private var cards: List<Card> = emptyList()

    override fun onCreate() {
        // 不用做什么，第一次 onDataSetChanged 会把数据填上
    }

    /**
     * 数据变了（或者启动器要重新拉）时被调用。
     *
     * 每次刷新都是**整份重建** —— 卡片数量本来就只有几十张，
     * 重建比维护增量便宜得多，也少一类 bug。
     */
    override fun onDataSetChanged() {
        val data = Prefs.loadData(context)
        cards = CalendarParser.buildCards(data, System.currentTimeMillis(), MAX_CARDS)
    }

    override fun onDestroy() {
        cards = emptyList()
    }

    override fun getCount(): Int = cards.size

    override fun getViewAt(position: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_card)
        val card = cards.getOrNull(position) ?: return views

        val zone = ZoneId.systemDefault()
        views.setTextViewText(R.id.tv_card_title, card.title)
        views.setTextViewText(R.id.tv_card_detail, card.detail)
        views.setTextViewText(R.id.tv_card_time, rightText(card, zone))

        // 三档底色按时态切。**只能切预置 drawable**，不能用运行时 tint ——
        // RemoteViews 的着色 API 是 API 31 才公开的，minSdk 26 上编译过得去、
        // 运行期直接抛异常，表现是桌面显示「载入小组件时出现问题」。
        // 色值怎么定的见 drawable/bg_card.xml。
        views.setInt(R.id.card_root, "setBackgroundResource", cardBgRes(card.status))
        applyTextTone(views, card.status)

        bindIcon(views, card)

        // ⚠️ 必须**每一行**都设一个 fill-in intent，卡片才点得动。
        //
        // 光在 WidgetRenderer 里给 ListView 设 setPendingIntentTemplate 是不够的 ——
        // 那个模板只说明「点了之后发什么」，真正让这一行可点击的是这里的 fill-in。
        // 少了它，模板永远不会被触发，表现就是「点卡片毫无反应」，
        // 而且不报错、不崩溃，很难往这个方向想。
        //
        // 具体点哪张卡不影响行为（都是打开 App），extras 留着是为了以后能深链到某一场。
        views.setOnClickFillInIntent(
            R.id.card_root,
            Intent().putExtra(EXTRA_CARD_ID, card.id),
        )

        return views
    }

    /**
     * 右侧那个位置写什么。
     *
     * 它原本只放「下一场时间」。已经打完的比赛再显示一个未来的时间就没意义了 ——
     * 而比分 / 系列赛战绩 / 分站冠军正是这时候最该看到的，所以按时态分三种。
     */
    private fun rightText(card: Card, zone: ZoneId): String = when (card.status) {
        EventStatus.UPCOMING -> formatCardTime(card.nextStartMs, LocalDate.now(zone), zone)
        EventStatus.LIVE -> "进行中"
        // 赛果可能没有（MotoGP 拿不到冠军、刚打完还没回填比分），那时退回「已结束」
        EventStatus.FINISHED -> card.result ?: "已结束"
    }

    /** 卡片底色按时态切三档。色值依据见 drawable/bg_card.xml。 */
    private fun cardBgRes(status: EventStatus): Int = when (status) {
        EventStatus.UPCOMING -> R.drawable.bg_card
        EventStatus.LIVE -> R.drawable.bg_card_live
        EventStatus.FINISHED -> R.drawable.bg_card_done
    }

    /**
     * 底色变深之后，第二行原来的 text_muted 对比度会掉到 4.5:1 以下
     * （实测浅色模式下：进行中 4.00、已结束 3.44），所以这两档换成深一级的
     * text_secondary，把对比度拉回 5.5 以上。
     *
     * 所以**没有**给已结束的卡片做「文字降淡」—— 那和底色变深是互相抵消的，
     * 两个一起上必然跌破这个项目给 7~9dp 小字定的 4.5:1 底线（见 colors.xml）。
     * 状态靠底色区分就够了。
     *
     * 注：每一次 getViewAt 都是**新建**的 RemoteViews，所以这里不设颜色时
     * 布局里的 XML 默认值自然会生效，不需要在 else 分支里把默认值再写一遍。
     */
    private fun applyTextTone(views: RemoteViews, status: EventStatus) {
        if (status == EventStatus.UPCOMING) return
        views.setTextColor(
            R.id.tv_card_detail,
            ContextCompat.getColor(context, R.color.text_secondary),
        )
    }

    /**
     * 左侧图标：用户传了就用图，没传就退回分类色字母块。
     *
     * 图标是用户在 App 里自己上传的（见 [LogoStore]）。没传不是异常状态，
     * 是默认状态 —— 字母块用的是分类色，一眼还是能认出是哪一类。
     */
    private fun bindIcon(views: RemoteViews, card: Card) {
        val bmp = LogoStore.cached(card.cat)
        if (bmp != null) {
            views.setImageViewBitmap(R.id.iv_logo, bmp)
            views.setViewVisibility(R.id.iv_logo, View.VISIBLE)
            views.setViewVisibility(R.id.tv_mark, View.GONE)
        } else {
            views.setViewVisibility(R.id.iv_logo, View.GONE)
            views.setViewVisibility(R.id.tv_mark, View.VISIBLE)
            views.setTextViewText(R.id.tv_mark, card.mark)
            views.setInt(R.id.tv_mark, "setBackgroundResource", card.cat.pillRes)
            views.setTextColor(R.id.tv_mark, ContextCompat.getColor(context, R.color.on_pill))
        }
    }

    /** 返回 null 用默认的加载视图。我们没什么可加载的，第一帧就有数据。 */
    override fun getLoadingView(): RemoteViews? = null

    /** 必须 ≥1，返回 0 的话列表只会显示加载视图（这是个经典坑）。 */
    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long =
        cards.getOrNull(position)?.id?.hashCode()?.toLong() ?: position.toLong()

    override fun hasStableIds(): Boolean = true

    companion object {
        /** 点击卡片时带过去的卡片 id，目前没用到，留给以后深链到某一场。 */
        const val EXTRA_CARD_ID = "com.dailywork.sportswidget.CARD_ID"

        /**
         * 最多放多少张卡。
         *
         * 本地缓存窗口是 14 天（见 CACHE_WINDOW_DAYS），卡片数一般在 30 上下。
         * 设个上限是防止哪天后端数据异常暴涨，把启动器拖死 ——
         * 列表再长用户也只会看最上面几张。
         */
        const val MAX_CARDS = 40
    }
}
