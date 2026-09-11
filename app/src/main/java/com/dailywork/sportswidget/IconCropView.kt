package com.dailywork.sportswidget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * 选图之后的取景框：拖动平移、双指缩放，框里是什么最后就存什么。
 *
 * ── 为什么需要它 ────────────────────────────────────────────────────────
 * 直接等比缩放的后果是：图里的标偏在一角、或者周围留白太多，缩到 28dp 的卡片上
 * 就只剩一团糊。而且用户拿来当图标的往往是**从网上截的图**，标根本不在正中间。
 * 所以给一个能自己挪一挪的地方，比程序去猜怎么裁靠谱。
 *
 * ── 交互约定 ────────────────────────────────────────────────────────────
 * 这个 View **本身就是取景框**：它是个正方形，屏幕上看到的就是最后的图标。
 * 图片始终铺满整个框（`cover` 缩放），拖到边上会被挡住，所以永远不会有空白边。
 * （另一种做法是显示整张图 + 中间一个框，但那样就得处理「框拖到图外面」，
 *   逻辑多一倍，而这个尺寸下也看不出什么差别。）
 */
class IconCropView(context: Context) : View(context) {

    private var source: Bitmap? = null

    /** 把原图坐标映射到 View 坐标。拖动和缩放都作用在它上面。 */
    private val matrix = Matrix()

    /** 原图的范围，(0,0,w,h)。 */
    private val srcRect = RectF()

    /** matrix 作用之后的图片范围，用来做边界钳制。 */
    private val mapped = RectF()

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint().apply { color = 0xFF10151F.toInt() }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        color = 0x66FFFFFF
    }

    private var lastX = 0f
    private var lastY = 0f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                // 以两指中心为锚点缩放，符合直觉
                matrix.postScale(detector.scaleFactor, detector.scaleFactor,
                    detector.focusX, detector.focusY)
                clampToFrame()
                invalidate()
                return true
            }
        },
    )

    /** 取景框边长（像素）。View 是正方形，所以宽高取小的那个。 */
    private fun sidePx(): Float = min(width, height).toFloat()

    fun setBitmap(bitmap: Bitmap) {
        source = bitmap
        srcRect.set(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        resetToCenter()
    }

    /** 回到「铺满取景框、居中」。用户拖歪了可以点「重置」。 */
    fun resetToCenter() {
        val b = source ?: return
        val side = sidePx()
        if (side <= 0f || b.width == 0 || b.height == 0) return

        // cover：取宽高里较大的那个比例，保证铺满、不留白边
        val s = max(side / b.width, side / b.height)
        matrix.reset()
        matrix.postScale(s, s)
        matrix.postTranslate((side - b.width * s) / 2f, (side - b.height * s) / 2f)
        invalidate()
    }

    /**
     * 导出 size×size 的图标。
     *
     * 就是把「View 坐标 -> 输出坐标」的缩放接到现有矩阵前面：
     * 输出 = 缩放(k) ∘ matrix。用 setConcat(缩放, matrix) 而不是 postConcat ——
     * postConcat 是反的，那样会先用 k 再用 matrix，结果整张图跑到框外面去。
     */
    fun exportSquare(size: Int): Bitmap? {
        val b = source ?: return null
        val side = sidePx()
        if (side <= 0f) return null

        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val k = size / side
        val scaleM = Matrix().apply { setScale(k, k) }
        val outM = Matrix().apply { setConcat(scaleM, matrix) }
        Canvas(out).drawBitmap(b, outM, bitmapPaint)
        return out
    }

    // ── 绘制 ──────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val side = sidePx()
        canvas.drawRect(0f, 0f, side, side, bgPaint)

        source?.let { canvas.drawBitmap(it, matrix, bitmapPaint) }

        // 四个角画一小段，标出取景范围。画整圈边框会显得很重
        val inset = 0.5f
        canvas.drawRect(inset, inset, side - inset, side - inset, framePaint)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 第一次拿到尺寸时才能算居中位置（在那之前 width/height 都是 0）
        resetToCenter()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val s = when {
            w > 0 && h > 0 -> min(w, h)
            w > 0 -> w
            h > 0 -> h
            else -> (240 * resources.displayMetrics.density).toInt()
        }
        setMeasuredDimension(s, s)
    }

    // ── 手势 ──────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                // 别让外面的容器把拖动当成它自己的滚动
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress) {
                    matrix.postTranslate(event.x - lastX, event.y - lastY)
                    clampToFrame()
                    invalidate()
                }
                lastX = event.x
                lastY = event.y
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    /**
     * 把图片挪回取景框内，保证任何时候都不会露出空白边。
     *
     * 因为用的是 cover 缩放，正常情况下图片本来就比框大；这里的
     * 「比框小就居中」分支是缩放边界上的兜底（浮点误差可能让它小那么一点点）。
     */
    private fun clampToFrame() {
        if (source == null) return
        val side = sidePx()
        if (side <= 0f) return

        matrix.mapRect(mapped, srcRect)
        var dx = 0f
        var dy = 0f

        if (mapped.width() <= side) {
            dx = (side - mapped.width()) / 2f - mapped.left
        } else if (mapped.left > 0f) {
            dx = -mapped.left
        } else if (mapped.right < side) {
            dx = side - mapped.right
        }

        if (mapped.height() <= side) {
            dy = (side - mapped.height()) / 2f - mapped.top
        } else if (mapped.top > 0f) {
            dy = -mapped.top
        } else if (mapped.bottom < side) {
            dy = side - mapped.bottom
        }

        if (dx != 0f || dy != 0f) matrix.postTranslate(dx, dy)
    }
}
