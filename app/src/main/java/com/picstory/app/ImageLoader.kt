package com.picstory.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import okhttp3.Request

/** Tiny image loader: OkHttp download + in-memory cache + downsampling. */
object ImageLoader {
    private val cache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun load(url: String, view: ImageView, targetPx: Int = 800) {
        val abs = Api.absUrl(url)
        if (abs.isEmpty()) {
            view.setImageBitmap(null)
            return
        }
        val cached = cache.get(abs)
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }
        view.setImageBitmap(null)
        view.tag = abs
        Thread {
            try {
                val req = Request.Builder().url(abs).build()
                Api.http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val bytes = resp.body?.bytes() ?: return@use
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    var sample = 1
                    val maxDim = maxOf(opts.outWidth, opts.outHeight)
                    while (maxDim / sample > targetPx) sample *= 2
                    val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
                    if (bmp != null) {
                        cache.put(abs, bmp)
                        view.post {
                            if (view.tag == abs) view.setImageBitmap(bmp)
                        }
                    }
                }
            } catch (e: Exception) {
                // Leave the view empty; a missing image must never crash the app.
            }
        }.start()
    }

    fun clear() = cache.evictAll()
}
