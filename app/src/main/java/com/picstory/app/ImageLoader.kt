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

    /** Crop a bitmap to a circle (centre-crop, transparent corners) for avatars. */
    fun circularCrop(src: Bitmap): Bitmap {
        val size = minOf(src.width, src.height)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val shader = android.graphics.BitmapShader(
            src, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP
        )
        val scale = size.toFloat() / minOf(src.width, src.height)
        val matrix = android.graphics.Matrix().apply {
            setScale(scale, scale)
            postTranslate((size - src.width * scale) / 2f, (size - src.height * scale) / 2f)
        }
        shader.setLocalMatrix(matrix)
        paint.shader = shader
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        return out
    }

    /** Like load(), but delivers a circular-cropped bitmap (for profile photos). */
    fun loadCircular(url: String, view: ImageView, targetPx: Int = 200) {
        val abs = Api.absUrl(url)
        if (abs.isEmpty()) {
            view.setImageBitmap(null)
            return
        }
        val key = "$abs|circle"
        val cached = cache.get(key)
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }
        view.setImageBitmap(null)
        view.tag = key
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
                        ?: return@use
                    val circ = circularCrop(bmp)
                    cache.put(key, circ)
                    view.post {
                        if (view.tag == key) view.setImageBitmap(circ)
                    }
                }
            } catch (e: Exception) {
                // Leave the view empty; a missing image must never crash the app.
            }
        }.start()
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
