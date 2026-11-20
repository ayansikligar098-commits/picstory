package com.picstory.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.VideoView
import org.json.JSONObject

/**
 * Full-screen story viewer with auto-advance progress.
 * Two modes: a user's stories (user_id) or a highlight (highlight_id).
 */
class StoryViewerActivity : Activity() {
    private var userId: Int = 0
    private var highlightId: Int = 0
    private var index: Int = 0
    private var stories: List<StoryItem> = emptyList()
    private var author: User? = null
    private var title: String = ""

    private lateinit var ivStory: ImageView
    private lateinit var vvStory: VideoView
    private lateinit var pbStory: ProgressBar
    private lateinit var tvStoryName: TextView
    private lateinit var tvStoryTime: TextView
    private lateinit var tvStoryAvatar: TextView
    private lateinit var ivStoryAvatar: ImageView
    private lateinit var tvViewers: TextView
    private lateinit var btnLike: Button
    private lateinit var etStoryComment: EditText
    private lateinit var storyBottomBar: LinearLayout
    private lateinit var btnDeleteStory: Button
    private var likeBusy = false
    private var commentBusy = false

    private val handler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null
    private var autoRunnable: Runnable? = null
    private var progress = 0
    private val DURATION_MS = 5000L
    // Video stories: the timer follows the video's own length, not the
    // fixed 5s image timer. Set when the player is prepared.
    private var videoPrepared = false
    private var videoDurationMs = DURATION_MS
    // v3.4: a video that never prepares used to sit on a silent black
    // screen forever (moov-at-end file + slow network, no feedback).
    // Loading state, one raw-URL retry, a 15s prepare timeout, and an
    // error state with Retry — never a permanent silent black screen.
    private var triedRaw = false
    private var prepareTimeoutRunnable: Runnable? = null
    private lateinit var storyLoadingBox: LinearLayout
    private lateinit var storyErrorBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        setContentView(R.layout.activity_story_viewer)

        userId = intent.getIntExtra("user_id", 0)
        highlightId = intent.getIntExtra("highlight_id", 0)
        index = intent.getIntExtra("index", 0)
        if (userId == 0 && highlightId == 0) {
            finish()
            return
        }
        ivStory = findViewById(R.id.ivStory)
        vvStory = findViewById(R.id.vvStory)
        pbStory = findViewById(R.id.pbStory)
        tvStoryName = findViewById(R.id.tvStoryName)
        tvStoryTime = findViewById(R.id.tvStoryTime)
        tvStoryAvatar = findViewById(R.id.tvStoryAvatar)
        ivStoryAvatar = findViewById(R.id.ivStoryAvatar)
        tvViewers = findViewById(R.id.tvViewers)
        btnLike = findViewById(R.id.btnLike)
        etStoryComment = findViewById(R.id.etStoryComment)
        storyBottomBar = findViewById(R.id.storyBottomBar)
        btnDeleteStory = findViewById(R.id.btnDeleteStory)
        storyLoadingBox = findViewById(R.id.storyLoadingBox)
        storyErrorBox = findViewById(R.id.storyErrorBox)
        findViewById<Button>(R.id.btnRetryStory).setOnClickListener { show(index) }

        findViewById<Button>(R.id.btnClose).setOnClickListener { finish() }
        // Prev/next: tap zones on the story media itself (left third =
        // previous, right third = next). The header and bottom bar sit
        // above the media and take their own taps first, so this can
        // never swallow a tap meant for the like button. Same listener
        // on the photo view and the video view.
        val mediaTouch = View.OnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> true
                MotionEvent.ACTION_UP -> {
                    val w = v.width
                    if (w > 0) {
                        if (ev.x < w * 0.35f) show(index - 1)
                        else if (ev.x > w * 0.65f) show(index + 1)
                    }
                    true
                }
                else -> false
            }
        }
        ivStory.setOnTouchListener(mediaTouch)
        vvStory.setOnTouchListener(mediaTouch)
        btnLike.setOnClickListener { toggleLike() }
        findViewById<Button>(R.id.btnSendStoryComment).setOnClickListener { sendStoryReply() }
        btnDeleteStory.setOnClickListener { confirmDeleteStory() }
        // Don't auto-advance while the user is typing a reply.
        etStoryComment.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) stopTimers() else startTimers()
        }
        tvViewers.setOnClickListener {
            val story = stories.getOrNull(index) ?: return@setOnClickListener
            if (author?.id == Prefs.myUserId) {
                startActivity(
                    Intent(this, UserListActivity::class.java)
                        .putExtra("mode", "viewers")
                        .putExtra("story_id", story.id)
                )
            }
        }

        if (highlightId != 0) loadHighlight() else loadStories()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTimers()
        cancelPrepareTimeout()
        stopStoryVideo()
    }

    private fun loadStories() {
        Api.callArray("GET", "/api/stories") { ok, arr, err ->
            runOnUiThread {
                if (!ok || arr == null) {
                    Ui.toast(this, err ?: "Stories could not be loaded")
                    finish()
                    return@runOnUiThread
                }
                for (i in 0 until arr.length()) {
                    try {
                        val g = StoryGroup.fromJson(arr.getJSONObject(i))
                        if (g.user.id == userId) {
                            author = g.user
                            stories = g.stories
                            break
                        }
                    } catch (e: Exception) {
                    }
                }
                if (stories.isEmpty()) {
                    Ui.toast(this, "No story found")
                    finish()
                    return@runOnUiThread
                }
                title = author?.displayName ?: ""
                show(index.coerceIn(0, stories.size - 1))
            }
        }
    }

    private fun loadHighlight() {
        Api.call("GET", "/api/highlights/$highlightId") { ok, json, err ->
            runOnUiThread {
                if (!ok || json == null) {
                    Ui.toast(this, err ?: "Highlight could not be opened")
                    finish()
                    return@runOnUiThread
                }
                val h = Highlight.fromJson(json)
                stories = h.stories
                title = h.name
                if (stories.isEmpty()) {
                    Ui.toast(this, "This highlight is empty")
                    finish()
                    return@runOnUiThread
                }
                Api.call("GET", "/api/users/${h.userId}") { ok2, json2, _ ->
                    runOnUiThread {
                        if (ok2 && json2 != null) author = User.fromJson(json2)
                        show(index.coerceIn(0, stories.size - 1))
                    }
                }
            }
        }
    }

    private fun show(i: Int) {
        if (i < 0) {
            finish()
            return
        }
        if (i >= stories.size) {
            finish()
            return
        }
        index = i
        val story = stories[i]
        stopTimers()
        stopStoryVideo()
        cancelPrepareTimeout()
        storyLoadingBox.visibility = View.GONE
        storyErrorBox.visibility = View.GONE
        val a = author
        if (a != null) {
            Ui.setAvatarPhoto(ivStoryAvatar, tvStoryAvatar, a)
            tvStoryName.text = if (highlightId != 0) "$title • ${a.displayName}" else a.displayName
            tvStoryTime.text = Ui.timeAgo(story.createdAt)
        } else {
            tvStoryName.text = title
            tvStoryTime.text = Ui.timeAgo(story.createdAt)
        }
        // Media: a video story plays (auto-play, with sound) and the
        // timer follows the video's own length; a photo story shows
        // for the fixed 5s. The like/reply bar and delete button are
        // the same for both.
        if (story.videoUrl.isNotEmpty()) {
            ivStory.visibility = View.GONE
            vvStory.visibility = View.VISIBLE
            storyErrorBox.visibility = View.GONE
            storyLoadingBox.visibility = View.VISIBLE
            videoPrepared = false
            videoDurationMs = DURATION_MS
            triedRaw = false
            // Listeners FIRST (v3.4): registering them after setVideoURI
            // can miss a fast prepare/error on some devices.
            vvStory.setOnPreparedListener { mp ->
                // Only start if this same story is still on screen.
                if (stories.getOrNull(index)?.id != story.id) return@setOnPreparedListener
                cancelPrepareTimeout()
                storyLoadingBox.visibility = View.GONE
                storyErrorBox.visibility = View.GONE
                videoPrepared = true
                videoDurationMs = mp.duration.toLong().coerceIn(1000L, 60_000L)
                startTimers()
            }
            vvStory.setOnCompletionListener {
                if (stories.getOrNull(index)?.id == story.id) show(index + 1)
            }
            vvStory.setOnErrorListener { _, _, _ ->
                if (stories.getOrNull(index)?.id != story.id) return@setOnErrorListener true
                cancelPrepareTimeout()
                if (!triedRaw) {
                    // Retry once with the raw original URL: the derived
                    // faststart URL is preferred, the raw file is the fallback.
                    triedRaw = true
                    try {
                        vvStory.setVideoURI(Uri.parse(Api.absUrl(story.videoUrl)))
                        armPrepareTimeout(story)
                    } catch (e: Exception) {
                        showStoryError()
                    }
                } else {
                    showStoryError()
                }
                true
            }
            try {
                // Derived faststart URL first — starts after buffering
                // the first bytes instead of fetching the file tail.
                vvStory.setVideoURI(Uri.parse(playableVideoUrl(Api.absUrl(story.videoUrl))))
                armPrepareTimeout(story)
            } catch (e: Exception) {
                showStoryError()
                return
            }
        } else {
            vvStory.visibility = View.GONE
            ivStory.visibility = View.VISIBLE
            if (story.imageUrl.isNotEmpty()) {
                ImageLoader.load(story.imageUrl, ivStory, 1200)
            } else {
                ivStory.setImageBitmap(null)
            }
            startTimers()
        }
        // Mark viewed (only for live stories, not highlights).
        if (highlightId == 0) {
            Api.call("POST", "/api/stories/${story.id}/view") { _, _, _ -> }
        }
        // Viewers count for my own story.
        if (author?.id == Prefs.myUserId && highlightId == 0) {
            Api.callArray("GET", "/api/stories/${story.id}/viewers") { ok, arr, _ ->
                runOnUiThread {
                    if (ok && arr != null) {
                        tvViewers.text = "👁 ${arr.length()} viewed"
                        tvViewers.visibility = View.VISIBLE
                    }
                }
            }
        } else {
            tvViewers.visibility = View.GONE
        }
        // Own story: delete button, no like/reply. Someone else's story:
        // heart like + reply box (the reply goes to them as a DM).
        val isMine = author?.id == Prefs.myUserId
        btnDeleteStory.visibility = if (isMine) View.VISIBLE else View.GONE
        storyBottomBar.visibility = if (isMine) View.GONE else View.VISIBLE
        updateLikeButton(story)
        // Timers are started by the media branch above: at once for a
        // photo, or by the player's prepared callback for a video.
    }

    private fun updateLikeButton(story: StoryItem) {
        btnLike.text = if (story.likedByMe) "♥ ${story.likeCount}" else "♡ ${story.likeCount}"
    }

    /**
     * Heart toggle — flips instantly (optimistic UI) so the tap feels
     * instant even on a slow connection, then reconciles with the
     * server response. The 5s auto-advance is paused while the request
     * is in flight, so a slow reply can never be swallowed by the
     * viewer closing or advancing first; the response is only applied
     * if this same story is still on screen (index race guard).
     */
    private fun toggleLike() {
        if (likeBusy) return
        val story = stories.getOrNull(index) ?: return
        likeBusy = true
        val storyId = story.id
        val optimistic = story.copy(
            likeCount = if (story.likedByMe) (story.likeCount - 1).coerceAtLeast(0)
                        else story.likeCount + 1,
            likedByMe = !story.likedByMe
        )
        stories = stories.toMutableList().also { it[index] = optimistic }
        updateLikeButton(optimistic)
        stopTimers()
        // A video story pauses while the like request is in flight, so
        // the video and the timer stay in sync when they restart.
        if (story.videoUrl.isNotEmpty() && videoPrepared) {
            try { vvStory.pause() } catch (e: Exception) { }
        }
        Api.call("POST", "/api/stories/$storyId/like") { ok, json, err ->
            runOnUiThread {
                likeBusy = false
                if (!ok || json == null) {
                    // Revert the optimistic flip, if this story is still showing.
                    if (stories.getOrNull(index)?.id == storyId) {
                        stories = stories.toMutableList().also { it[index] = story }
                        updateLikeButton(story)
                    }
                    Ui.toast(this, err ?: "Like could not be saved")
                    startTimers()
                    return@runOnUiThread
                }
                val updated = story.copy(
                    likeCount = json.optInt("like_count", optimistic.likeCount),
                    likedByMe = json.optBoolean("liked", optimistic.likedByMe)
                )
                if (stories.getOrNull(index)?.id == storyId) {
                    stories = stories.toMutableList().also { it[index] = updated }
                    updateLikeButton(updated)
                }
                startTimers()
            }
        }
    }

    /** A story comment goes straight to the story owner as a DM. */
    private fun sendStoryReply() {
        if (commentBusy) return
        val a = author ?: return
        val text = etStoryComment.text.toString().trim()
        if (text.isEmpty()) return
        commentBusy = true
        val body = JSONObject().put("body", "📸 Story reply: $text")
        Api.call("POST", "/api/dm/${a.id}", body) { ok, _, err ->
            runOnUiThread {
                commentBusy = false
                if (ok) {
                    etStoryComment.setText("")
                    etStoryComment.clearFocus()
                    (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .hideSoftInputFromWindow(etStoryComment.windowToken, 0)
                    Ui.toast(this, "Reply sent as DM ✓")
                } else {
                    Ui.toast(this, err ?: "Reply could not be sent")
                }
            }
        }
    }

    private fun confirmDeleteStory() {
        val story = stories.getOrNull(index) ?: return
        AlertDialog.Builder(this)
            .setTitle("Delete story?")
            .setMessage("Story delete karni hai? Ye wapas nahi aayegi.")
            .setPositiveButton("Delete") { _, _ -> deleteStory(story.id) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteStory(storyId: Int) {
        stopTimers()
        stopStoryVideo()
        Api.call("DELETE", "/api/stories/$storyId") { ok, _, err ->
            runOnUiThread {
                if (!ok) {
                    Ui.toast(this, err ?: "Story could not be deleted")
                    startTimers()
                    return@runOnUiThread
                }
                val remaining = stories.filter { it.id != storyId }
                stories = remaining
                if (remaining.isEmpty()) {
                    Ui.toast(this, "Story deleted")
                    finish()
                    return@runOnUiThread
                }
                Ui.toast(this, "Story deleted")
                show(index.coerceIn(0, remaining.size - 1))
            }
        }
    }

    /**
     * Progress + auto-advance. The duration follows the story on screen:
     * a photo shows for the fixed 5s, a video story for its own length
     * (capped at 60s). A video story that is not prepared yet starts no
     * timer — the player's prepared callback starts it. Restarting the
     * timers also restarts a video from the beginning, so the video and
     * the progress bar can never drift apart (after a like, or after
     * typing a reply).
     */
    private fun startTimers() {
        // Never auto-advance while the user is typing a story reply.
        if (::etStoryComment.isInitialized && etStoryComment.hasFocus()) return
        stopTimers()
        val story = stories.getOrNull(index)
        val isVideo = story != null && story.videoUrl.isNotEmpty()
        if (isVideo && !videoPrepared) return
        val durationMs = if (isVideo) videoDurationMs else DURATION_MS
        if (isVideo) {
            try {
                vvStory.seekTo(0)
                vvStory.start()
            } catch (e: Exception) { }
        }
        progress = 0
        pbStory.progress = 0
        val tick = object : Runnable {
            override fun run() {
                progress += 2
                pbStory.progress = progress.coerceAtMost(100)
                if (progress < 100) handler.postDelayed(this, durationMs / 50)
            }
        }
        progressRunnable = tick
        handler.postDelayed(tick, durationMs / 50)
        val auto = Runnable { show(index + 1) }
        autoRunnable = auto
        handler.postDelayed(auto, durationMs)
    }

    private fun stopTimers() {
        progressRunnable?.let { handler.removeCallbacks(it) }
        autoRunnable?.let { handler.removeCallbacks(it) }
    }

    /** 15s without onPrepared means the video is stuck — show the error state. */
    private fun armPrepareTimeout(story: StoryItem) {
        cancelPrepareTimeout()
        val r = Runnable {
            if (stories.getOrNull(index)?.id == story.id && !videoPrepared) {
                showStoryError()
            }
        }
        prepareTimeoutRunnable = r
        handler.postDelayed(r, 15_000L)
    }

    private fun cancelPrepareTimeout() {
        prepareTimeoutRunnable?.let { handler.removeCallbacks(it) }
        prepareTimeoutRunnable = null
    }

    /** Error state: hide the video, show "Video nahi chal paaya" + Retry. */
    private fun showStoryError() {
        cancelPrepareTimeout()
        stopTimers()
        try { vvStory.stopPlayback() } catch (e: Exception) { }
        videoPrepared = false
        storyLoadingBox.visibility = View.GONE
        vvStory.visibility = View.GONE
        storyErrorBox.visibility = View.VISIBLE
    }

    /** Stop a playing video story (no-op for photos). */
    private fun stopStoryVideo() {
        try { vvStory.stopPlayback() } catch (e: Exception) { }
        videoPrepared = false
    }
}
