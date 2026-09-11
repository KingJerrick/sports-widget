package com.dailywork.sportswidget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 队标 / 赛事标的本地缓存。
 *
 * ── 为什么是「App 运行时去原站拉」而不是打包进 APK 或提交进仓库 ──────────────
 * · 队标是别人的商标，扔进公开仓库算是再分发
 * · 打进 APK 意味着构建时要联网下载，本机用 Android Studio 构建就会缺资源
 * · 运行时拉一次就存盘，之后完全离线可用
 *
 * ── 两条缓存 ────────────────────────────────────────────────────────────
 * 磁盘（filesDir/logos/）：跨进程重启还在，装完 App 拉一次就够
 * 内存（memCache）：小组件的 RemoteViewsFactory 在 binder 线程上跑，
 *                   getViewAt() 每次都要拿到图，不能每次都读盘
 *
 * 拉不到就返回 null，调用方退回字母块 —— 图标缺失不该让卡片开天窗。
 */
object LogoStore {

    /**
     * 缩放到的边长（像素）。
     *
     * 卡片上的图标是 30dp，在 3x 屏上约 90px，所以 96 足够清晰。
     * 不用原图是因为 `setImageViewBitmap` 会把**整张位图**塞进 Binder 事务
     * （全进程共享约 1MB），IG 那张原图 36KB 的 PNG 解码后是 300×300×4 = 360KB，
     * 五张一起就顶到上限了。缩到 96px 后每张约 36KB。
     */
    private const val TARGET_PX = 96

    private const val TIMEOUT_CONNECT_MS = 10_000
    private const val TIMEOUT_READ_MS = 15_000
    private const val UA = "Mozilla/5.0 (Linux; Android 13) sports-widget/1.0"

    /** url -> 已缩放好的位图。binder 线程会读，用 synchronized 保护。 */
    private val memCache = linkedMapOf<String, Bitmap>()

    /** 小组件渲染时取图。只查内存，**不读盘不联网** —— 那是 [warmUp] 的活。 */
    fun cached(url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        return synchronized(memCache) { memCache[url] }
    }

    /**
     * 把磁盘上已有的图标读进内存，缺的就去下载。
     *
     * 在后台线程调用（[CalendarRefresher] 里），别放主线程。
     * 单个失败不影响其余 —— 缺哪个就哪个退回字母块。
     */
    suspend fun warmUp(context: Context, urls: Collection<String>) {
        for (url in urls.filter { it.isNotBlank() }) {
            if (cached(url) != null) continue
            val bmp = try {
                loadFromDisk(context, url) ?: download(context, url)
            } catch (e: Exception) {
                // 图标拉不到不是错误，只是降级成字母块 —— 不要把整次刷新带崩
                null
            } ?: continue
            synchronized(memCache) { memCache[url] = bmp }
        }
    }

    // ── 内部实现 ──────────────────────────────────────────────────────

    private fun fileFor(context: Context, url: String): File =
        File(File(context.filesDir, "logos").apply { mkdirs() }, "${url.hashCode().toUInt()}.png")

    private fun loadFromDisk(context: Context, url: String): Bitmap? = runCatching {
        val f = fileFor(context, url)
        if (!f.exists()) null else BitmapFactory.decodeFile(f.absolutePath)
    }.getOrNull()

    private fun download(context: Context, url: String): Bitmap? = runCatching {
        val bytes = httpGetBytes(url)
        // 先量尺寸再解码，避免为了一张 300×300 的原图分配一大块内存
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, TARGET_PX)
        }
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return@runCatching null

        // 缩放到正方形画布，居中 —— 各家队标的宽高比不一样，
        // 不统一的话卡片左侧那一列会参差不齐
        val square = squareScale(raw)
        if (square !== raw) raw.recycle()

        runCatching {
            fileFor(context, url).outputStream().use {
                square.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        square
    }.getOrNull()

    private fun sampleSizeFor(w: Int, h: Int, target: Int): Int {
        var sample = 1
        while (w / (sample * 2) >= target && h / (sample * 2) >= target) sample *= 2
        return sample
    }

    /** 等比缩放到 target×target 的透明画布上，居中。 */
    private fun squareScale(src: Bitmap): Bitmap {
        val side = maxOf(src.width, src.height)
        if (side == 0) return src
        val scale = TARGET_PX.toFloat() / side
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(TARGET_PX, TARGET_PX, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.drawBitmap(src, (TARGET_PX - w) / 2f, (TARGET_PX - h) / 2f, null)
        return out
    }

    private fun httpGetBytes(url: String): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_CONNECT_MS
            readTimeout = TIMEOUT_READ_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "image/*,*/*;q=0.8")
            setRequestProperty("User-Agent", UA)
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }
}
