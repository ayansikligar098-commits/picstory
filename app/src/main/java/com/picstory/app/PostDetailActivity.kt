package com.picstory.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
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
import android.widget.VideoView
import org.json.JSONObject

/** Full post view: photo, author, caption, like, comments, delete (own only). */
class PostDetailActivity : Activity() {
    private var postId: Int = 0
    private var post: Post? = null
    private var comments: List<Comment> = emptyList()
    private var likeBusy = false
    private var sendBusy = false

    private lateinit var lvComments: ListView
    private lateinit var etComment: EditText
    private lateinit var tvEmptyComments: TextView
    private lateinit var btnLike: Button
    private lateinit var btnDelete: Button
    private lateinit var vvVideo: VideoView
    private lateinit var tvPlayIcon: TextView

    private val adapter = object : BaseAdapter() {
        override fun getCount() = comments.size
        override fun getItem(p: Int) = comments[p]
        override fun getItemId(p: Int) = comments[p].id.toLong()
        override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
            val v = cv ?: LayoutInflater.from(this@PostDetailActivity)
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
        setContentView(R.layout.activity_post_detail)

        postId = intent.getIntExtra("post_id", 0)
        if (postId == 0) {
            finish()
            return
        }
        lvComments = findViewById(R.id.lvComments)
        etComment = findViewById(R.id.etComment)
        tvEmptyComments = findViewById(R.id.tvEmptyComments)
        btnLike = findViewById(R.id.btnLike)
        btnDelete = findViewById(R.id.btnDelete)
        vvVideo = findViewById(R.id.vvVideo)
        tvPlayIcon = findViewById(R.id.tvPlayIcon)
        lvComments.adapter = adapter

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        btnLike.setOnClickListener { toggleLike() }
        btnDelete.setOnClickListener { confirmDelete() }
        findViewById<Button>(R.id.btnShare).setOnClickListener {
            post?.let { ShareHelper.showShareSheet(this, it) }
        }
        findViewById<Button>(R.id.btnSendComment).setOnClickListener { sendComment() }
        vvVideo.setOnClickListener { toggleVideo() }
        tvPlayIcon.setOnClickListener { toggleVideo() }
        loadPost()
        loadComments()
    }

    override fun onPause() {
        try { vvVideo.stopPlayback() } catch (e: Exception) { }
        super.onPause()
    }

    /** Tap the Reel to play/pause it; the play icon shows while paused. */
    private fun toggleVideo() {
        try {
            if (vvVideo.isPlaying) {
                vvVideo.pause()
                tvPlayIcon.visibility = View.VISIBLE
            } else {
                vvVideo.start()
                tvPlayIcon.visibility = View.GONE
            }
        } catch (e: Exception) {
            Ui.toast(this, "Video could not play")
        }
    }

    override fun onResume() {
        super.onResume()
        loadPost()
        loadComments()
    }

    private fun loadPost() {
        Api.call("GET", "/api/posts/$postId") { ok, json, err ->
            runOnUiThread {
                if (!ok || json == null) {
                    Ui.toast(this, err ?: "Post could not be opened")
                    finish()
                    return@runOnUiThread
                }
                val p = Post.fromJson(json)
                post = p
                val author = p.author
                val tvAvatar = findViewById<TextView>(R.id.tvAvatar)
                val ivAvatar = findViewById<ImageView>(R.id.ivAvatar)
                if (author != null) {
                    Ui.setAvatarPhoto(ivAvatar, tvAvatar, author)
                    findViewById<TextView>(R.id.tvName).text = author.displayName
                } else {
                    findViewById<TextView>(R.id.tvName).text = "User"
                }
                findViewById<TextView>(R.id.tvTime).text = Ui.timeAgo(p.createdAt)
                val tvCaption = findViewById<TextView>(R.id.tvCaption)
                if (p.caption.isNotEmpty()) {
                    tvCaption.visibility = View.VISIBLE
                    tvCaption.text = p.caption
                } else {
                    tvCaption.visibility = View.GONE
                }
                val ivImage = findViewById<ImageView>(R.id.ivImage)
                if (p.videoUrl.isNotEmpty()) {
                    ivImage.visibility = View.GONE
                    vvVideo.visibility = View.VISIBLE
                    tvPlayIcon.visibility = View.VISIBLE
                    try {
                        vvVideo.setVideoURI(android.net.Uri.parse(Api.absUrl(p.videoUrl)))
                    } catch (e: Exception) {
                        tvPlayIcon.visibility = View.GONE
                    }
                    vvVideo.setOnPreparedListener { /* stay paused until tapped */ }
                    vvVideo.setOnCompletionListener {
                        tvPlayIcon.visibility = View.VISIBLE
                        try { vvVideo.seekTo(0) } catch (e: Exception) { }
                    }
                    vvVideo.setOnErrorListener { _, _, _ ->
                        tvPlayIcon.visibility = View.GONE
                        true
                    }
                } else if (p.imageUrl.isNotEmpty()) {
                    try { vvVideo.stopPlayback() } catch (e: Exception) { }
                    vvVideo.visibility = View.GONE
                    tvPlayIcon.visibility = View.GONE
                    ivImage.visibility = View.VISIBLE
                    ImageLoader.load(p.imageUrl, ivImage, 1200)
                } else {
                    try { vvVideo.stopPlayback() } catch (e: Exception) { }
                    vvVideo.visibility = View.GONE
                    tvPlayIcon.visibility = View.GONE
                    ivImage.visibility = View.GONE
                }
                updateLikeButton()
                findViewById<TextView>(R.id.tvCommentCount).text = "💬 ${p.commentCount} comments"
                btnDelete.visibility =
                    if (p.userId == Prefs.myUserId) View.VISIBLE else View.GONE
                tvAvatar.setOnClickListener {
                    author?.let {
                        startActivity(Intent(this, ProfileActivity::class.java)
                            .putExtra("user_id", it.id))
                    }
                }
                ivAvatar.setOnClickListener {
                    author?.let {
                        startActivity(Intent(this, ProfileActivity::class.java)
                            .putExtra("user_id", it.id))
                    }
                }
            }
        }
    }

    private fun updateLikeButton() {
        val p = post ?: return
        btnLike.text = (if (p.likedByMe) "❤️ " else "🤍 ") + p.likeCount
    }

    private fun toggleLike() {
        if (likeBusy) return
        val p = post ?: return
        likeBusy = true
        Api.call("POST", "/api/posts/${p.id}/like") { ok, json, _ ->
            runOnUiThread {
                likeBusy = false
                if (ok && json != null) {
                    p.likedByMe = json.optBoolean("liked")
                    p.likeCount = json.optInt("like_count")
                    updateLikeButton()
                }
            }
        }
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
                    tvEmptyComments.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                    post?.let { findViewById<TextView>(R.id.tvCommentCount).text = "💬 ${list.size} comments" }
                    // ListView inside a ScrollView: size it to fit all rows.
                    lvComments.post { fitCommentsList() }
                }
            }
        }
    }

    private fun fitCommentsList() {
        if (adapter.count == 0 || lvComments.width == 0) return
        var total = 0
        for (i in 0 until adapter.count) {
            val item = adapter.getView(i, null, lvComments)
            item.measure(
                View.MeasureSpec.makeMeasureSpec(lvComments.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            total += item.measuredHeight
        }
        val params = lvComments.layoutParams
        params.height = total + lvComments.dividerHeight * maxOf(0, adapter.count - 1)
        lvComments.layoutParams = params
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

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Delete this post?")
            .setMessage("This can't be undone.")
            .setPositiveButton("Delete") { _, _ ->
                Api.call("DELETE", "/api/posts/$postId") { ok, _, err ->
                    runOnUiThread {
                        if (ok) {
                            Ui.toast(this, "Post deleted")
                            finish()
                        } else {
                            Ui.toast(this, err ?: "Could not delete")
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
