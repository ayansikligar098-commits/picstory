package com.picstory.app

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
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    val http: OkHttpClient get() = client

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
                        cb(false, null, "Upload failed (${resp.code})")
                    }
                }
            } catch (e: Exception) {
                cb(false, null, "Upload failed: ${e.message}")
            }
        }.start()
    }
}
