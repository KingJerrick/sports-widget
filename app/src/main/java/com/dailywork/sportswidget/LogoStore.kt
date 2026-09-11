package com.dailywork.sportswidget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import java.io.File

/**
 * 卡片左侧的图标。**由用户自己在 App 里上传**，每个分组一张。
 *
 * ── 为什么不做成「自动去网上拉队标」──────────────────────────────────────
 * 试过，不行。三个问题凑在一起：
 *   · 拉回来的图**明暗不一定配我们的卡片底色** —— F1 和 MotoGP 的标是白色/浅色的，
 *     本来就是给深色背景用的，放到浅色卡片上就看不清；皇马队徽的白底又和卡片融在一起
 *   · 各家给的图尺寸、留白、比例都不一样，排成一列参差不齐
 *   · 来源本身不稳（TLS 指纹风控、403、格式是 SVG 解不了）
 * 猜来猜去不如让用户自己放一张他满意的图，放什么就是什么。
 *
 * ── 存哪里 ──────────────────────────────────────────────────────────────
 * `filesDir/logos/<类别 key>.png`。跟着 App 走，卸载才清；不用存储权限
 * （是 App 私有目录），也不占用户相册。
 *
 * ── 两级缓存 ────────────────────────────────────────────────────────────
 * 磁盘：跨重启还在
 * 内存：小组件的 RemoteViewsFactory 跑在 binder 线程上，getViewAt() 每次都要取图，
 *       不能每次都读盘
 */
object LogoStore {

    /**
     * 缩放到的边长（像素）。
     *
     * 卡片上的图标是 28dp，在 3x 屏上约 84px，96 足够清晰。
     * 而且 `setImageViewBitmap` 会把**整张位图**塞进 Binder 事务（全进程共享约 1MB），
     * 用户上传的可能是一张几 MB 的照片，解码后更大 —— 不缩的话五张就顶到上限，
     * 表现是小组件直接不更新（TransactionTooLargeException，而且不好查）。
     */
    const val TARGET_PX = 96

    /** cat.key -> 已缩放好的位图。binder 线程会读，用 synchronized 保护。 */
    private val memCache = mutableMapOf<String, Bitmap>()

    fun fileFor(context: Context, cat: Cat): File =
        File(File(context.filesDir, "logos").apply { mkdirs() }, "${cat.key}.png")

    /** 小组件渲染时取图。只查内存，**不读盘不联网** —— 那是 [warmUp] 的活。 */
    fun cached(cat: Cat): Bitmap? = synchronized(memCache) { memCache[cat.key] }

    fun has(context: Context, cat: Cat): Boolean = fileFor(context, cat).exists()

    /**
     * 存下用户在取景框里框好的图。
     *
     * 传进来的应该是 [IconCropView.exportSquare] 出来的正方形位图 ——
     * 「框哪儿」由用户决定（见 IconCropView 的说明），这里只负责缩到
     * [TARGET_PX] 并落盘。
     *
     * @return 成功返回 true。写盘失败返回 false。
     */
    fun save(context: Context, cat: Cat, bitmap: Bitmap): Boolean {
        val square = squareScale(bitmap)
        if (square !== bitmap) bitmap.recycle()

        val ok = runCatching {
            fileFor(context, cat).outputStream().use {
                square.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }.getOrDefault(false)

        if (ok) {
            synchronized(memCache) {
                memCache.remove(cat.key)?.recycle()
                memCache[cat.key] = square
            }
        } else {
            square.recycle()
        }
        return ok
    }

    /** 删掉用户上传的图，卡片会退回字母块。 */
    fun clear(context: Context, cat: Cat) {
        runCatching { fileFor(context, cat).delete() }
        synchronized(memCache) { memCache.remove(cat.key)?.recycle() }
    }

    /**
     * 把磁盘上已有的图标读进内存。App 启动和每次刷新时调一次。
     *
     * 在后台线程调用，别放主线程（要读盘解码）。
     */
    fun warmUp(context: Context) {
        for (cat in Cat.entries) {
            if (synchronized(memCache) { memCache.containsKey(cat.key) }) continue
            val f = fileFor(context, cat)
            if (!f.exists()) continue
            val bmp = runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull() ?: continue
            synchronized(memCache) { memCache[cat.key] = bmp }
        }
    }

    // ── 内部实现 ──────────────────────────────────────────────────────

    /** 等比缩放到 TARGET_PX 见方的透明画布上，居中。 */
    private fun squareScale(src: Bitmap): Bitmap {
        val side = maxOf(src.width, src.height)
        if (side <= 0) return src
        val scale = TARGET_PX.toFloat() / side
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(TARGET_PX, TARGET_PX, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, (TARGET_PX - w) / 2f, (TARGET_PX - h) / 2f, null)
        return out
    }
}
