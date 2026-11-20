package com.picstory.app

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import org.json.JSONObject

/**
 * Share a post to someone as a DM: search for the person, tap them, and
 * the post's photo/video + caption goes to their DM as
 * "Shared by @me: <caption>". Started from the share button on a post.
 */
class ShareToDmActivity : Activity() {
    private var users: List<User> = emptyList()
    private var postId: Int = 0
    private var imageUrl: String = ""
    private var videoUrl: String = ""
    private var caption: String = ""
    private var authorUsername: String = ""
    private var sendBusy = false
    private lateinit var lvUsers: ListView
    private lateinit var tvEmpty: TextView
    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null

    private val adapter = object : BaseAdapter() {
        override fun getCount() = users.size
        override fun getItem(p: Int) = users[p]
        override fun getItemId(p: Int) = users[p].id.toLong()
        override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
            val v = cv ?: LayoutInflater.from(this@ShareToDmActivity)
                .inflate(R.layout.item_user, parent, false)
            val u = users[pos]
            val tvAvatar = v.findViewById<TextView>(R.id.tvAvatar)
            val ivAvatar = v.findViewById<ImageView>(R.id.ivAvatar)
            Ui.setAvatarPhoto(ivAvatar, tvAvatar, u)
            v.findViewById<TextView>(R.id.tvName).text = u.displayName
            v.findViewById<TextView>(R.id.tvSub).text = "@${u.username}"
            return v
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        ThemeHelper.apply(this)
        setContentView(R.layout.activity_share_dm)

        postId = intent.getIntExtra("post_id", 0)
        imageUrl = intent.getStringExtra("image_url") ?: ""
        videoUrl = intent.getStringExtra("video_url") ?: ""
        caption = intent.getStringExtra("caption") ?: ""
        authorUsername = intent.getStringExtra("author_username") ?: ""
        if (postId == 0) {
            finish()
            return
        }
        lvUsers = findViewById(R.id.lvUsers)
        tvEmpty = findViewById(R.id.tvEmpty)
        lvUsers.adapter = adapter

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<EditText>(R.id.etSearch).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchRunnable?.let { searchHandler.removeCallbacks(it) }
                val q = s?.toString()?.trim() ?: ""
                val r = Runnable { searchUsers(q) }
                searchRunnable = r
                searchHandler.postDelayed(r, 400)
            }
        })
        lvUsers.setOnItemClickListener { _, _, pos, _ -> sendTo(users[pos]) }
        searchUsers("")
    }

    private fun searchUsers(q: String) {
        val encoded = java.net.URLEncoder.encode(q, "UTF-8")
        Api.callArray("GET", "/api/users?q=$encoded") { ok, arr, _ ->
            runOnUiThread {
                if (!ok || arr == null) return@runOnUiThread
                val list = mutableListOf<User>()
                for (i in 0 until arr.length()) {
                    try { list.add(User.fromJson(arr.getJSONObject(i))) } catch (e: Exception) { }
                }
                users = list
                adapter.notifyDataSetChanged()
                tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun sendTo(u: User) {
        if (sendBusy) return
        sendBusy = true
        val mediaUrl = videoUrl.ifEmpty { imageUrl }
        val mediaKind = if (videoUrl.isNotEmpty()) "video" else "image"
        val body = JSONObject().apply {
            put("body", "Shared by @${Prefs.myUsername}: $caption")
            put("media_url", mediaUrl)
            put("media_kind", mediaKind)
        }
        Api.call("POST", "/api/dm/${u.id}", body) { ok, _, err ->
            runOnUiThread {
                sendBusy = false
                if (ok) {
                    Ui.toast(this, "Shared to ${u.displayName} ✓")
                    finish()
                } else {
                    Ui.toast(this, err ?: "Could not share")
                }
            }
        }
    }
}
