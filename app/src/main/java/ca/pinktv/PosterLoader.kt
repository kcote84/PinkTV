package ca.pinktv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Small, bounded thumbnails only; catalogue and playback work never wait for artwork. */
class PosterLoader {
    private val main = Handler(Looper.getMainLooper())
    private val pool = ThreadPoolExecutor(3, 3, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue<Runnable>(24))
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val waiting = mutableMapOf<String, MutableList<WeakReference<ImageView>>>()
    private var generation = 0
    private var closed = false
    fun bind(image: ImageView, source: String) {
        image.tag = source
        if(source.isBlank()) return
        cache.get(source)?.let { display(image, it); return }
        val reference = WeakReference(image)
        waiting[source]?.let { it.add(reference); return }
        waiting[source] = mutableListOf(reference)
        val token = generation
        try {
            pool.execute {
                val bitmap = runCatching { fetch(source) }.getOrNull()
                if(bitmap != null) cache.put(source, bitmap)
                main.post {
                    if(closed || generation != token) return@post
                    waiting.remove(source)?.forEach { ref ->
                        ref.get()?.takeIf { it.tag == source }?.let { if(bitmap != null) display(it, bitmap) }
                    }
                }
            }
        } catch (_: RejectedExecutionException) { waiting.remove(source) }
    }
    private fun display(image: ImageView, bitmap: Bitmap) {
        image.clearColorFilter(); image.scaleType = ImageView.ScaleType.CENTER_CROP; image.setImageBitmap(bitmap)
    }
    private fun fetch(source: String): Bitmap? {
        if(ArtworkRules.url(source) != source) return null
        val connection = Network.connection(source).apply {
            connectTimeout = 5000; readTimeout = 5000
            setRequestProperty("User-Agent", "Pink TV/${BuildConfig.VERSION_NAME}")
        }
        val bytes = try {
            if(connection.responseCode !in 200..299 || connection.contentLengthLong > 4 * 1024 * 1024) return null
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                while(true) {
                    if(Thread.currentThread().isInterrupted || System.nanoTime() > deadline) return null
                    val count = input.read(buffer); if(count < 0) break
                    if(output.size() + count > 4 * 1024 * 1024) return null
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally { connection.disconnect() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if(bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 40_000_000) return null
        var scale = 1
        while(bounds.outWidth / scale > 360 || bounds.outHeight / scale > 540) scale *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = scale })
    }
    fun cancelPending() { generation++; waiting.clear(); pool.queue.clear() }
    fun close() { closed = true; cancelPending(); pool.shutdownNow(); main.removeCallbacksAndMessages(null); cache.evictAll() }
}
