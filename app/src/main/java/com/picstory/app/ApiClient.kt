package com.picstory.app

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * REST client for the PicStory backend. All calls run on a background
 * thread and invoke the callback on that same thread — callers must switch
 * to the UI thread (runOnUiThread) before touching views.
 *
 * The server base URL comes ONLY from Prefs.baseUrl — never hardcode a
 * URL here (the phone cannot reach localhost; the user sets the public
 * server URL on the login screen / in Settings).
 */
object Api {
    private val client: OkHttpClient = OkHttpClient.Builder()
        // Render Free sleeps after 15 min idle and takes ~50s to wake up,
        // so a 30s read timeout killed the very first request after a nap.
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    val http: OkHttpClient get() = client

    /**
     * Set once from MainActivity / HomeActivity onCreate: an application
     * context (never an Activity, so nothing leaks) used only to route
     * back to the login screen when the saved session is dead.
     */
    var appContext: Context? = null

    /**
     * Called on the UI thread when a saved session gets a 401 (e.g. the
     * free server's database was wiped and the account no longer exists).
     * Set from MainActivity / HomeActivity; if unset, the fallback in
     * handleUnauthorized() still routes via appContext.
     */
    var onUnauthorized: (() -> Unit)? = null

    private var lastUnauthorizedAt = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Central 401 handling, used by every request method below: clear the
     * stale auth once, then route to AuthActivity on the UI thread.
     * Guarded two ways so a storm of parallel 401s only fires once:
     * after the first call the token is gone (so later calls return
     * early), and a 5-second window swallows duplicates.
     * A failed login must NOT trigger this — it has no saved token yet,
     * so the token check returns early and the login error is shown.
     */
    private fun handleUnauthorized() {
        if (Prefs.token.isNullOrEmpty()) return
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (now - lastUnauthorizedAt < 5000) return
            lastUnauthorizedAt = now
        }
        Prefs.clearAuth()
        val cb = onUnauthorized
        val ctx = appContext
        mainHandler.post {
            try {
                if (cb != null) {
                    cb()
                } else if (ctx != null) {
                    val intent = Intent(ctx, AuthActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK
                    ctx.startActivity(intent)
                }
            } catch (e: Exception) {
                // Routing must never crash the app; MainActivity routes to
                // Auth on the next launch anyway (token is already cleared).
            }
        }
    }

    fun base(): String = Prefs.baseUrl.trimEnd('/')

    /** Resolve a backend-relative URL (e.g. /media/abc.jpg) to an absolute one. */
    fun absUrl(url: String): String {
        if (url.isEmpty()) return ""
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        return base() + if (url.startsWith("/")) url else "/$url"
    }

    private fun request(method: String, path: String, json: JSONObject? = null): Request {
        val url = base() + path
        val builder = Request.Builder().url(url)
        Prefs.token?.let { builder.header("Authorization", "Bearer $it") }
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            else -> {
                val body = (json?.toString() ?: "{}")
                    .toRequestBody("application/json".toMediaType())
                if (method == "POST") builder.post(body)
                else if (method == "PATCH") builder.patch(body)
                else if (method == "PUT") builder.put(body)
            }
        }
        return builder.build()
    }

    /** Runs on a background thread. cb(success, jsonOrNull, errorMessage). */
    fun call(
        method: String,
        path: String,
        json: JSONObject? = null,
        cb: (Boolean, JSONObject?, String?) -> Unit
    ) {
        Thread {
            try {
                client.newCall(request(method, path, json)).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    val parsed = try {
                        JSONObject(body)
                    } catch (e: Exception) {
                        JSONObject().put("raw", body)
                    }
                    if (resp.isSuccessful) {
                        cb(true, parsed, null)
                    } else {
                        if (resp.code == 401) handleUnauthorized()
                        cb(false, parsed, parsed.optString("detail", "Server error ${resp.code}"))
                    }
                }
            } catch (e: Exception) {
                cb(false, null, "Couldn't connect to the server: ${e.message}")
            }
        }.start()
    }

    /** Same as call(), but expects a JSON array response. */
    fun callArray(
        method: String,
        path: String,
        json: JSONObject? = null,
        cb: (Boolean, JSONArray?, String?) -> Unit
    ) {
        Thread {
            try {
                client.newCall(request(method, path, json)).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    if (resp.isSuccessful) {
                        cb(true, JSONArray(body), null)
                    } else {
                        if (resp.code == 401) handleUnauthorized()
                        val parsed = try { JSONObject(body) } catch (e: Exception) { JSONObject() }
                        cb(false, null, parsed.optString("detail", "Server error ${resp.code}"))
                    }
                }
            } catch (e: Exception) {
                cb(false, null, "Couldn't connect to the server: ${e.message}")
            }
        }.start()
    }

    /** Upload an image (bytes) to /api/upload. cb(success, imageUrlOrNull, error). */
    fun uploadImage(bytes: ByteArray, filename: String, cb: (Boolean, String?, String?) -> Unit) {
        Thread {
            try {
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "file", filename,
                        bytes.toRequestBody("image/jpeg".toMediaType())
                    )
                    .build()
                val reqBuilder = Request.Builder().url(base() + "/api/upload").post(body)
                Prefs.token?.let { reqBuilder.header("Authorization", "Bearer $it") }
                client.newCall(reqBuilder.build()).execute().use { resp ->
                    val text = resp.body?.string() ?: ""
                    if (resp.isSuccessful) {
                        cb(true, JSONObject(text).optString("url"), null)
                    } else {
                        if (resp.code == 401) handleUnauthorized()
                        cb(false, null, "Upload failed (${resp.code})")
                    }
                }
            } catch (e: Exception) {
                cb(false, null, "Upload failed: ${e.message}")
            }
        }.start()
    }

    /** Upload a file to /api/upload?kind=image|video|audio.
     * cb(success, fileUrlOrNull, error). Never crashes on bad files. */
    fun uploadMedia(bytes: ByteArray, filename: String, mimeType: String,
                    kind: String, cb: (Boolean, String?, String?) -> Unit) {
        Thread {
            try {
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "file", filename,
                        bytes.toRequestBody(mimeType.toMediaType())
                    )
                    .build()
                val reqBuilder = Request.Builder()
                    .url(base() + "/api/upload?kind=$kind").post(body)
                Prefs.token?.let { reqBuilder.header("Authorization", "Bearer $it") }
                client.newCall(reqBuilder.build()).execute().use { resp ->
                    val text = resp.body?.string() ?: ""
                    if (resp.isSuccessful) {
                        cb(true, JSONObject(text).optString("url"), null)
                    } else {
                        if (resp.code == 401) handleUnauthorized()
                        val detail = try {
                            JSONObject(text).optString("detail", "")
                        } catch (e: Exception) {
                            ""
                        }
                        cb(false, null, detail.ifEmpty { "Upload failed (${resp.code})" })
                    }
                }
            } catch (e: Exception) {
                cb(false, null, "Upload failed: ${e.message}")
            }
        }.start()
    }
}
