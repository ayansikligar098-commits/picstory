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
 * Simple user list: followers / following of a user, or viewers of a story.
 * mode = "followers" | "following" | "viewers"
 */
class UserListActivity : Activity() {
    private var users: List<User> = emptyList()
    private lateinit var lvUsers: ListView
    private lateinit var tvEmpty: TextView
    private lateinit var tvTitle: TextView

    private val adapter = object : BaseAdapter() {
        override fun getCount() = users.size
        override fun getItem(p: Int) = users[p]
        override fun getItemId(p: Int) = users[p].id.toLong()
        override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
            val v = cv ?: LayoutInflater.from(this@UserListActivity)
                .inflate(R.layout.item_user, parent, false)
            val u = users[pos]
            val tvAvatar = v.findViewById<TextView>(R.id.tvAvatar)
            val ivAvatar = v.findViewById<ImageView>(R.id.ivAvatar)
            val tvName = v.findViewById<TextView>(R.id.tvName)
            val tvSub = v.findViewById<TextView>(R.id.tvSub)
            Ui.setAvatarPhoto(ivAvatar, tvAvatar, u)
            tvName.text = u.displayName
            tvSub.text = "@${u.username}"
            return v
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        ThemeHelper.apply(this)
        setContentView(R.layout.activity_user_list)

        lvUsers = findViewById(R.id.lvUsers)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvTitle = findViewById(R.id.tvTitle)
        lvUsers.adapter = adapter

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        lvUsers.setOnItemClickListener { _, _, pos, _ ->
            startActivity(Intent(this, ProfileActivity::class.java)
                .putExtra("user_id", users[pos].id))
        }

        val mode = intent.getStringExtra("mode") ?: "followers"
        val userId = intent.getIntExtra("user_id", 0)
        val storyId = intent.getIntExtra("story_id", 0)
        val (path, title, emptyText) = when (mode) {
            "following" -> Triple("/api/users/$userId/following", "Following", "Not following anyone yet.")
            "viewers" -> Triple("/api/stories/$storyId/viewers", "Viewed by", "No one has viewed this story yet.")
            else -> Triple("/api/users/$userId/followers", "Followers", "No followers yet.")
        }
        tvTitle.text = title
        load(path, emptyText)
    }

    private fun load(path: String, emptyText: String) {
        Api.callArray("GET", path) { ok, arr, err ->
            runOnUiThread {
                if (!ok || arr == null) {
                    tvEmpty.visibility = View.VISIBLE
                    tvEmpty.text = err ?: "Couldn't load the list."
                    return@runOnUiThread
                }
                val list = mutableListOf<User>()
                for (i in 0 until arr.length()) {
                    try { list.add(User.fromJson(arr.getJSONObject(i))) } catch (e: Exception) { }
                }
                users = list
                adapter.notifyDataSetChanged()
                tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                if (list.isEmpty()) tvEmpty.text = emptyText
            }
        }
    }
}
