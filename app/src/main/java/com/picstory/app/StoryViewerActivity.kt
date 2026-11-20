package com.picstory.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView

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
    private lateinit var pbStory: ProgressBar
    private lateinit var tvStoryName: TextView
    private lateinit var tvStoryTime: TextView
    private lateinit var tvStoryAvatar: TextView
    private lateinit var tvViewers: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null
    private var autoRunnable: Runnable? = null
    private var progress = 0
    private val DURATION_MS = 5000L

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
        pbStory = findViewById(R.id.pbStory)
        tvStoryName = findViewById(R.id.tvStoryName)
        tvStoryTime = findViewById(R.id.tvStoryTime)
        tvStoryAvatar = findViewById(R.id.tvStoryAvatar)
        tvViewers = findViewById(R.id.tvViewers)

        findViewById<Button>(R.id.btnClose).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnPrev).setOnClickListener { show(index - 1) }
        findViewById<Button>(R.id.btnNext).setOnClickListener { show(index + 1) }
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
        val a = author
        if (a != null) {
            Ui.setAvatar(tvStoryAvatar, a)
            tvStoryName.text = if (highlightId != 0) "$title • ${a.displayName}" else a.displayName
            tvStoryTime.text = Ui.timeAgo(story.createdAt)
        } else {
            tvStoryName.text = title
            tvStoryTime.text = Ui.timeAgo(story.createdAt)
        }
        ivStory.visibility = View.VISIBLE
        if (story.imageUrl.isNotEmpty()) {
            ImageLoader.load(story.imageUrl, ivStory, 1200)
        } else {
            ivStory.setImageBitmap(null)
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
        startTimers()
    }

    private fun startTimers() {
        stopTimers()
        progress = 0
        pbStory.progress = 0
        val tick = object : Runnable {
            override fun run() {
                progress += 2
                pbStory.progress = progress.coerceAtMost(100)
                if (progress < 100) handler.postDelayed(this, DURATION_MS / 50)
            }
        }
        progressRunnable = tick
        handler.postDelayed(tick, DURATION_MS / 50)
        val auto = Runnable { show(index + 1) }
        autoRunnable = auto
        handler.postDelayed(auto, DURATION_MS)
    }

    private fun stopTimers() {
        progressRunnable?.let { handler.removeCallbacks(it) }
        autoRunnable?.let { handler.removeCallbacks(it) }
    }
}
