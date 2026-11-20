package com.picstory.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView

/**
 * Notifications center: follows, post likes/comments, story likes,
 * story replies and DMs — newest first. Tapping one opens the
 * relevant screen and marks that notification read.
 */
class NotificationsActivity : Activity() {
    private var items: List<AppNotification> = emptyList()
    private lateinit var lvNotifications: ListView
    private lateinit var tvEmpty: TextView

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = items[p].id.toLong()
        override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
            val v = cv ?: LayoutInflater.from(this@NotificationsActivity)
                .inflate(R.layout.item_notification, parent, false)
            val n = items[pos]
            val tvAvatar = v.findViewById<TextView>(R.id.tvAvatar)
            val ivAvatar = v.findViewById<ImageView>(R.id.ivAvatar)
            val tvBody = v.findViewById<TextView>(R.id.tvBody)
            val tvTime = v.findViewById<TextView>(R.id.tvTime)
            val vUnread = v.findViewById<View>(R.id.vUnread)
            val actor = n.actor
            if (actor != null) {
                Ui.setAvatarPhoto(ivAvatar, tvAvatar, actor)
            } else {
                tvAvatar.text = "?"
                ivAvatar.visibility = View.GONE
                tvAvatar.visibility = View.VISIBLE
            }
            tvBody.text = n.body.ifEmpty { defaultBody(n) }
            tvTime.text = Ui.timeAgo(n.createdAt)
            vUnread.visibility = if (n.isRead) View.GONE else View.VISIBLE
            return v
        }
    }

    private fun defaultBody(n: AppNotification): String {
        val name = n.actor?.displayName ?: "Someone"
        return when (n.type) {
            "follow" -> "$name started following you"
            "post_like" -> "$name liked your photo"
            "post_comment" -> "$name commented on your photo"
            "story_like" -> "$name liked your story"
            "story_reply" -> "$name replied to your story"
            "dm" -> "$name sent you a message"
            else -> "$name sent you a notification"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        ThemeHelper.apply(this)
        setContentView(R.layout.activity_notifications)

        lvNotifications = findViewById(R.id.lvNotifications)
        tvEmpty = findViewById(R.id.tvEmpty)
        lvNotifications.adapter = adapter

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        lvNotifications.setOnItemClickListener { _, _, pos, _ -> openNotification(items[pos]) }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        Api.callArray("GET", "/api/notifications") { ok, arr, err ->
            runOnUiThread {
                if (!ok || arr == null) {
                    tvEmpty.visibility = View.VISIBLE
                    tvEmpty.text = err ?: "Couldn't load notifications."
                    return@runOnUiThread
                }
                val list = mutableListOf<AppNotification>()
                for (i in 0 until arr.length()) {
                    try { list.add(AppNotification.fromJson(arr.getJSONObject(i))) } catch (e: Exception) { }
                }
                items = list
                adapter.notifyDataSetChanged()
                tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                // Opening the list marks everything read, so the heart
                // badge stays cleared when the user goes back home.
                if (list.any { !it.isRead }) {
                    Api.call("POST", "/api/notifications/read") { _, _, _ -> }
                }
            }
        }
    }

    private fun openNotification(n: AppNotification) {
        Api.call("POST", "/api/notifications/${n.id}/read") { _, _, _ -> }
        val actor = n.actor
        when (n.type) {
            "follow" -> actor?.let {
                startActivity(Intent(this, ProfileActivity::class.java)
                    .putExtra("user_id", it.id))
            }
            "post_like", "post_comment" -> {
                if (n.refId != 0) {
                    startActivity(Intent(this, PostDetailActivity::class.java)
                        .putExtra("post_id", n.refId))
                }
            }
            "story_like" -> {
                // Open my own stories (the liked story is mine).
                startActivity(Intent(this, StoryViewerActivity::class.java)
                    .putExtra("user_id", Prefs.myUserId)
                    .putExtra("index", 0))
            }
            "story_reply", "dm" -> actor?.let {
                startActivity(Intent(this, DmChatActivity::class.java)
                    .putExtra("user_id", it.id)
                    .putExtra("display_name", it.displayName)
                    .putExtra("username", it.username)
                    .putExtra("avatar_url", it.avatarUrl))
            }
            else -> actor?.let {
                startActivity(Intent(this, ProfileActivity::class.java)
                    .putExtra("user_id", it.id))
            }
        }
    }
}
