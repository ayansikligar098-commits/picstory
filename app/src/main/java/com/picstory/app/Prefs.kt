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
            // Dark-only (v1.5): any theme stored by an older version
            // ("system" / "light") is migrated to "dark" once, so an
            // existing install also turns black. The theme buttons are
            // gone from Settings, so nothing can pick light again.
            if (sp.getString("theme", null) != "dark") {
                sp.edit().putString("theme", "dark").apply()
            }
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

    /** My own profile-photo URL ("" = no photo yet, initials are shown). */
    var myAvatarUrl: String
        get() = sp.getString("avatar_url", "") ?: ""
        set(v) = sp.edit().putString("avatar_url", v).apply()

    /**
     * Server base URL — changeable on the login screen and in Settings.
     * Default is the public Render backend so the app works directly
     * from the phone. This is the ONLY place the default URL string
     * lives; everything else goes through here.
     */
    var baseUrl: String
        get() = sp.getString("base_url", "https://picstory-backend.onrender.com") ?: "https://picstory-backend.onrender.com"
        set(v) = sp.edit().putString("base_url", v.trim().trimEnd('/')).apply()

    /** "system" | "light" | "dark" — default is dark (black theme). */
    var theme: String
        get() = sp.getString("theme", "dark") ?: "dark"
        set(v) = sp.edit().putString("theme", v).apply()

    var seenWelcome: Boolean
        get() = sp.getBoolean("seen_welcome", false)
        set(v) = sp.edit().putBoolean("seen_welcome", v).apply()

    fun saveAuth(token: String, user: User) {
        this.token = token
        myUserId = user.id
        myUsername = user.username
        myDisplayName = user.displayName
        myAvatarUrl = user.avatarUrl
    }

    fun clearAuth() {
        token = null
        myUserId = 0
        myUsername = ""
        myDisplayName = ""
        myAvatarUrl = ""
    }

    val loggedIn: Boolean get() = !token.isNullOrEmpty()

    // ---- My stories (local record) -------------------------------------
    // The API has no list-expired-stories endpoint, so every story this
    // user creates is recorded locally (id + image) so highlights can
    // still be built from it after the 24h expiry.
    data class MyStory(val id: Int, val imageUrl: String, val createdAt: String,
                      val videoUrl: String = "")

    fun recordMyStory(id: Int, imageUrl: String, createdAt: String, videoUrl: String = "") {
        val list = myStories().toMutableList()
        if (list.none { it.id == id }) {
            list.add(0, MyStory(id, imageUrl, createdAt, videoUrl))
            saveMyStories(list.take(100))
        }
    }

    fun myStories(): List<MyStory> {
        val out = mutableListOf<MyStory>()
        try {
            val arr = JSONArray(sp.getString("my_stories", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(MyStory(o.optInt("id"), o.optString("image_url"),
                    o.optString("created_at"), o.optString("video_url")))
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
                put("video_url", s.videoUrl)
            })
        }
        sp.edit().putString("my_stories", arr.toString()).apply()
    }
}
