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
 * Blocked users (v3.1): everyone I blocked, each with an Unblock button.
 * Opened from the profile menu (≡) on my own profile. Unblocking removes
 * the row at once; tapping a row opens that user's profile.
 */
class BlockedUsersActivity : Activity() {
    private var users: MutableList<User> = mutableListOf()
    private lateinit var lvUsers: ListView
    private lateinit var tvEmpty: TextView

    private val adapter = object : BaseAdapter() {
        override fun getCount() = users.size
        override fun getItem(p: Int) = users[p]
        override fun getItemId(p: Int) = users[p].id.toLong()
        override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
            val v = cv ?: LayoutInflater.from(this@BlockedUsersActivity)
                .inflate(R.layout.item_blocked_user, parent, false)
            val u = users[pos]
            val tvAvatar = v.findViewById<TextView>(R.id.tvAvatar)
            val ivAvatar = v.findViewById<ImageView>(R.id.ivAvatar)
            val tvName = v.findViewById<TextView>(R.id.tvName)
            val tvSub = v.findViewById<TextView>(R.id.tvSub)
            Ui.setAvatarPhoto(ivAvatar, tvAvatar, u)
            tvName.text = u.displayName
            tvSub.text = "@${u.username}"
            v.findViewById<Button>(R.id.btnUnblock).setOnClickListener {
                unblock(u)
            }
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
        findViewById<TextView>(R.id.tvTitle).text = getString(R.string.blocked_users)
        lvUsers.adapter = adapter

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        lvUsers.setOnItemClickListener { _, _, pos, _ ->
            startActivity(Intent(this, ProfileActivity::class.java)
                .putExtra("user_id", users[pos].id))
        }
        load()
    }

    private fun load() {
        Api.callArray("GET", "/api/users/blocked") { ok, arr, err ->
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
                if (list.isEmpty()) tvEmpty.text = "No one blocked yet."
            }
        }
    }

    private fun unblock(u: User) {
        Api.call("POST", "/api/users/${u.id}/unblock") { ok, json, err ->
            runOnUiThread {
                if (ok && json != null && !json.optBoolean("blocked", true)) {
                    users.removeAll { it.id == u.id }
                    adapter.notifyDataSetChanged()
                    tvEmpty.visibility = if (users.isEmpty()) View.VISIBLE else View.GONE
                    if (users.isEmpty()) tvEmpty.text = "No one blocked yet."
                    Ui.toast(this, "Unblocked @${u.username}")
                } else {
                    Ui.toast(this, err ?: "Could not unblock")
                }
            }
        }
    }
}
