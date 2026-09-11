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
        views.setTextViewText(
            R.id.tv_card_time,
            formatCardTime(card.nextStartMs, LocalDate.now(zone), zone),
        )

        bindIcon(views, card)
        return views
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

    private companion object {
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
