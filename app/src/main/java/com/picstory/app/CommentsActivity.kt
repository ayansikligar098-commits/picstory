package com.picstory.app

import android.app.Activity
import android.os.Bundle
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

/** Comments on a post (also reachable from PostDetailActivity). */
class CommentsActivity : Activity() {
    private var postId: Int = 0
    private var comments: List<Comment> = emptyList()
    private var sendBusy = false
    private lateinit var lvComments: ListView
    private lateinit var etComment: EditText

    private val adapter = object : BaseAdapter() {
        override fun getCount() = comments.size
        override fun getItem(p: Int) = comments[p]
        override fun getItemId(p: Int) = comments[p].id.toLong()
        override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
            val v = cv ?: LayoutInflater.from(this@CommentsActivity)
                .inflate(R.layout.item_comment, parent, false)
            val c = comments[pos]
            val tvAvatar = v.findViewById<TextView>(R.id.tvAvatar)
            val ivAvatar = v.findViewById<ImageView>(R.id.ivAvatar)
            val tvName = v.findViewById<TextView>(R.id.tvName)
            val tvText = v.findViewById<TextView>(R.id.tvText)
            if (c.author != null) {
                Ui.setAvatarPhoto(ivAvatar, tvAvatar, c.author)
                tvName.text = c.author.displayName
            } else {
                tvName.text = "User"
            }
            tvText.text = c.text
            return v
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        ThemeHelper.apply(this)
        setContentView(R.layout.activity_comments)

        postId = intent.getIntExtra("post_id", 0)
        if (postId == 0) {
            finish()
            return
        }
        lvComments = findViewById(R.id.lvComments)
        etComment = findViewById(R.id.etComment)
        lvComments.adapter = adapter

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnSendComment).setOnClickListener { sendComment() }
        loadComments()
    }

    private fun loadComments() {
        Api.callArray("GET", "/api/posts/$postId/comments") { ok, arr, _ ->
            runOnUiThread {
                if (ok && arr != null) {
                    val list = mutableListOf<Comment>()
                    for (i in 0 until arr.length()) {
                        try { list.add(Comment.fromJson(arr.getJSONObject(i))) } catch (e: Exception) { }
                    }
                    comments = list
                    adapter.notifyDataSetChanged()
                }
            }
        }
    }

    private fun sendComment() {
        if (sendBusy) return
        val text = etComment.text.toString().trim()
        if (text.isEmpty()) return
        sendBusy = true
        etComment.setText("")
        val body = JSONObject().put("text", text)
        Api.call("POST", "/api/posts/$postId/comments", body) { ok, _, err ->
            runOnUiThread {
                sendBusy = false
                if (ok) loadComments()
                else Ui.toast(this, err ?: "Comment could not be sent")
            }
        }
    }
}
