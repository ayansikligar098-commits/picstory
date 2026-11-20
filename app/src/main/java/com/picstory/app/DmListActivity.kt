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

/** Messages: my 1:1 DM conversations, newest first. Tap one to open the chat. */
class DmListActivity : Activity() {
    private var conversations: List<DmConversation> = emptyList()
    private lateinit var lvConversations: ListView
    private lateinit var tvEmpty: TextView

    private val adapter = object : BaseAdapter() {
        override fun getCount() = conversations.size
        override fun getItem(p: Int) = conversations[p]
        override fun getItemId(p: Int) = conversations[p].user.id.toLong()
        override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
            val v = cv ?: LayoutInflater.from(this@DmListActivity)
                .inflate(R.layout.item_dm_conversation, parent, false)
            val c = conversations[pos]
            val tvAvatar = v.findViewById<TextView>(R.id.tvAvatar)
            val ivAvatar = v.findViewById<ImageView>(R.id.ivAvatar)
            val tvName = v.findViewById<TextView>(R.id.tvName)
            val tvLast = v.findViewById<TextView>(R.id.tvLastMessage)
            val tvTime = v.findViewById<TextView>(R.id.tvTime)
            val tvBadge = v.findViewById<TextView>(R.id.tvUnreadBadge)
            Ui.setAvatarPhoto(ivAvatar, tvAvatar, c.user)
            tvName.text = c.user.displayName
            tvLast.text = c.lastMessage.ifEmpty { "Say hi 👋" }
            tvTime.text = Ui.timeAgo(c.lastMessageAt)
            if (c.unreadCount > 0) {
                tvBadge.visibility = View.VISIBLE
                tvBadge.text = c.unreadCount.toString()
            } else {
                tvBadge.visibility = View.GONE
            }
            return v
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        ThemeHelper.apply(this)
        setContentView(R.layout.activity_dm_list)

        lvConversations = findViewById(R.id.lvConversations)
        tvEmpty = findViewById(R.id.tvEmpty)
        lvConversations.adapter = adapter

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        lvConversations.setOnItemClickListener { _, _, pos, _ ->
            val u = conversations[pos].user
            startActivity(Intent(this, DmChatActivity::class.java)
                .putExtra("user_id", u.id)
                .putExtra("display_name", u.displayName)
                .putExtra("username", u.username)
                .putExtra("avatar_url", u.avatarUrl))
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        Api.callArray("GET", "/api/dm/conversations") { ok, arr, err ->
            runOnUiThread {
                if (!ok || arr == null) {
                    tvEmpty.visibility = View.VISIBLE
                    tvEmpty.text = err ?: "Couldn't load messages."
                    return@runOnUiThread
                }
                val list = mutableListOf<DmConversation>()
                for (i in 0 until arr.length()) {
                    try { list.add(DmConversation.fromJson(arr.getJSONObject(i))) } catch (e: Exception) { }
                }
                conversations = list
                adapter.notifyDataSetChanged()
                tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                if (list.isEmpty()) {
                    tvEmpty.text = "No messages yet — tap ✉ on someone's profile to start chatting."
                }
            }
        }
    }
}
