package com.picstory.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** App preferences: auth token, server URL, theme, first-run flag, my stories. */
object Prefs {
    private const val NAME = "picstory"
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        if (!::sp.isInitialized) {
            sp = ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        }
    }

    var token: String?
        get() = sp.getString("token", null)
        set(v) = sp.edit().putString("token", v).apply()

    var myUserId: Int
        get() = sp.getInt("user_id", 0)
        set(v) = sp.edit().putInt("user_id", v).apply()

    var myUsername: String
        get() = sp.getString("username", "") ?: ""
        set(v) = sp.edit().putString("username", v).apply()

    var myDisplayName: String
        get() = sp.getString("display_name", "") ?: ""
        set(v) = sp.edit().putString("display_name", v).apply()

    /**
     * Server base URL — changeable on the login screen and in Settings
     * (the phone cannot reach localhost). This is the ONLY place the
     * default URL string lives; everything else goes through here.
     */
    var baseUrl: String
        get() = sp.getString("base_url", "http://127.0.0.1:8000") ?: "http://127.0.0.1:8000"
        set(v) = sp.edit().putString("base_url", v.trim().trimEnd('/')).apply()

    /** "system" | "light" | "dark" */
    var theme: String
        get() = sp.getString("theme", "system") ?: "system"
        set(v) = sp.edit().putString("theme", v).apply()

    var seenWelcome: Boolean
        get() = sp.getBoolean("seen_welcome", false)
        set(v) = sp.edit().putBoolean("seen_welcome", v).apply()

    fun saveAuth(token: String, user: User) {
        this.token = token
        myUserId = user.id
        myUsername = user.username
        myDisplayName = user.displayName
    }

    fun clearAuth() {
        token = null
        myUserId = 0
        myUsername = ""
        myDisplayName = ""
    }

    val loggedIn: Boolean get() = !token.isNullOrEmpty()

    // ---- My stories (local record) -------------------------------------
    // The API has no list-expired-stories endpoint, so every story this
    // user creates is recorded locally (id + image) so highlights can
    // still be built from it after the 24h expiry.
    data class MyStory(val id: Int, val imageUrl: String, val createdAt: String)

    fun recordMyStory(id: Int, imageUrl: String, createdAt: String) {
        val list = myStories().toMutableList()
        if (list.none { it.id == id }) {
            list.add(0, MyStory(id, imageUrl, createdAt))
            saveMyStories(list.take(100))
        }
    }

    fun myStories(): List<MyStory> {
        val out = mutableListOf<MyStory>()
        try {
            val arr = JSONArray(sp.getString("my_stories", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(MyStory(o.optInt("id"), o.optString("image_url"), o.optString("created_at")))
            }
        } catch (e: Exception) {
            // Corrupt record: start fresh rather than crash.
        }
        return out
    }

    private fun saveMyStories(list: List<MyStory>) {
        val arr = JSONArray()
        for (s in list) {
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("image_url", s.imageUrl)
                put("created_at", s.createdAt)
            })
        }
        sp.edit().putString("my_stories", arr.toString()).apply()
    }
}
