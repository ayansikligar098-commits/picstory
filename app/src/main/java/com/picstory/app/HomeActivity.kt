package com.picstory.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.VideoView
import okhttp3.Request
import org.json.JSONObject

/**
 * Home screen: bottom nav Home / Explore / Create / Profile.
 * Home = stories row + feed. Explore = user search only.
 * Profile = own profile (shared ProfileController).
 */
class HomeActivity : Activity() {
    private lateinit var tabHome: View
    private lateinit var tabExplore: View
    private lateinit var tabProfile: View
    private lateinit var lvFeed: ListView
    private lateinit var tvEmptyFeed: TextView
    private lateinit var tvStatus: TextView
    private lateinit var storyRow: LinearLayout
    private lateinit var etSearch: EditText
    private lateinit var lvUsers: ListView
    private lateinit var tvNoUsers: TextView
    private lateinit var btnRefresh: Button
    private lateinit var flHeart: View
    private lateinit var tvUnreadBadge: TextView
    private lateinit var tvNotifBadge: TextView
    private lateinit var navProfileAvatar: TextView
    private lateinit var navProfilePhoto: ImageView
    // At most one feed video plays at a time; it stops when the row scrolls
    // away, the tab changes, or the screen is left.
    private var activeVideoView: VideoView? = null
    private var activeVideoIcon: View? = null
    private var activeVideoPostId: Int = -1

    private var storyGroups: List<StoryGroup> = emptyList()
    private var feed: List<Post> = emptyList()
    private var searchResults: List<User> = emptyList()
    private var currentTab = 0
    private var profileController: ProfileController? = null
    private var lastBackPress = 0L
    private val likeBusyIds = mutableSetOf<Int>()
    // Search debounce (400ms after the last keystroke).
    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null
    // Unread-DM polling (refreshes the numbered badge on the Messages icon).
    private val unreadHandler = Handler(Looper.getMainLooper())
    private var unreadRunnable: Runnable? = null

    private val feedAdapter = object : BaseAdapter() {
        override fun getCount() = feed.size
        override fun getItem(p: Int) = feed[p]
        override fun getItemId(p: Int) = feed[p].id.toLong()
        override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
            val v = cv ?: LayoutInflater.from(this@HomeActivity)
                .inflate(R.layout.item_post, parent, false)
            val post = feed[pos]
            val author = post.author
            val tvAvatar = v.findViewById<TextView>(R.id.tvAvatar)
            val ivAvatar = v.findViewById<ImageView>(R.id.ivAvatar)
            val tvName = v.findViewById<TextView>(R.id.tvName)
            val tvTime = v.findViewById<TextView>(R.id.tvTime)
            val tvCaption = v.findViewById<TextView>(R.id.tvCaption)
            val mediaFrame = v.findViewById<FrameLayout>(R.id.mediaFrame)
            val ivImage = v.findViewById<ImageView>(R.id.ivImage)
            val vvVideo = v.findViewById<VideoView>(R.id.vvVideo)
            val tvPlayIcon = v.findViewById<TextView>(R.id.tvPlayIcon)
            val btnLike = v.findViewById<Button>(R.id.btnLike)
            val btnComment = v.findViewById<Button>(R.id.btnComment)
            val btnShare = v.findViewById<Button>(R.id.btnShare)
            if (author != null) {
                Ui.setAvatarPhoto(ivAvatar, tvAvatar, author)
                tvName.text = author.displayName
            } else {
                tvName.text = "User"
            }
            tvTime.text = Ui.timeAgo(post.createdAt)
            if (post.caption.isNotEmpty()) {
                tvCaption.visibility = View.VISIBLE
                tvCaption.text = post.caption
            } else {
                tvCaption.visibility = View.GONE
            }
            // Media: a Reel (video) or a photo, never both at once.
            // A recycled row's player is always stopped before rebinding,
            // so a video never keeps playing after it scrolls away.
            try { vvVideo.stopPlayback() } catch (e: Exception) { }
            if (vvVideo == activeVideoView) {
                activeVideoView = null
                activeVideoIcon = null
                activeVideoPostId = -1
            }
            val isVideo = post.videoUrl.isNotEmpty()
            if (isVideo || post.imageUrl.isNotEmpty()) {
                mediaFrame.visibility = View.VISIBLE
                if (isVideo) {
                    ivImage.visibility = View.GONE
                    vvVideo.visibility = View.VISIBLE
                    tvPlayIcon.visibility = View.VISIBLE
                    try {
                        vvVideo.setVideoURI(android.net.Uri.parse(playableVideoUrl(Api.absUrl(post.videoUrl))))
                    } catch (e: Exception) {
                        tvPlayIcon.visibility = View.GONE
                    }
                    vvVideo.setOnPreparedListener { /* stay paused until tapped */ }
                    vvVideo.setOnCompletionListener {
                        tvPlayIcon.visibility = View.VISIBLE
                        try { vvVideo.seekTo(0) } catch (e: Exception) { }
                        if (activeVideoPostId == post.id) {
                            activeVideoView = null
                            activeVideoIcon = null
                            activeVideoPostId = -1
                        }
                    }
                    vvVideo.setOnErrorListener { _, _, _ ->
                        tvPlayIcon.visibility = View.GONE
                        true
                    }
                } else {
                    vvVideo.visibility = View.GONE
                    tvPlayIcon.visibility = View.GONE
                    ivImage.visibility = View.VISIBLE
                    ImageLoader.load(post.imageUrl, ivImage, 1000)
                }
                // Single tap: photo opens, video plays/pauses.
                // Double-tap: like (Instagram-style).
                val detector = GestureDetector(this@HomeActivity,
                    object : GestureDetector.SimpleOnGestureListener() {
                        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                            if (isVideo) toggleFeedVideo(post, vvVideo, tvPlayIcon)
                            else openPostDetail(post.id)
                            return true
                        }
                        override fun onDoubleTap(e: MotionEvent): Boolean {
                            toggleFeedLike(post)
                            return true
                        }
                    })
                mediaFrame.setOnTouchListener { _, event ->
                    detector.onTouchEvent(event)
                }
            } else {
                mediaFrame.visibility = View.GONE
                mediaFrame.setOnTouchListener(null)
            }
            btnLike.text = (if (post.likedByMe) "❤️ " else "🤍 ") + post.likeCount
            btnComment.text = "💬 " + post.commentCount
            btnLike.setOnClickListener { toggleFeedLike(post) }
            btnComment.setOnClickListener { openPostDetail(post.id) }
            btnShare.setOnClickListener { ShareHelper.showShareSheet(this@HomeActivity, post) }
            tvAvatar.setOnClickListener {
                author?.let {
                    startActivity(Intent(this@HomeActivity, ProfileActivity::class.java)
                        .putExtra("user_id", it.id))
                }
            }
            ivAvatar.setOnClickListener {
                author?.let {
                    startActivity(Intent(this@HomeActivity, ProfileActivity::class.java)
                        .putExtra("user_id", it.id))
                }
            }
            tvName.setOnClickListener {
                author?.let {
                    startActivity(Intent(this@HomeActivity, ProfileActivity::class.java)
                        .putExtra("user_id", it.id))
                }
            }
            return v
        }
    }

    /** Like/unlike a feed post (also used by double-tap on the media). */
    private fun toggleFeedLike(post: Post) {
        // Guard: ignore taps while a like request for this post is in flight.
        if (!likeBusyIds.add(post.id)) return
        Api.call("POST", "/api/posts/${post.id}/like") { ok, json, _ ->
            runOnUiThread {
                likeBusyIds.remove(post.id)
                if (ok && json != null) {
                    post.likedByMe = json.optBoolean("liked")
                    post.likeCount = json.optInt("like_count")
                    feedAdapter.notifyDataSetChanged()
                }
            }
        }
    }

    /** Tap a feed Reel to play/pause it. Only one video plays at a time. */
    private fun toggleFeedVideo(post: Post, vv: VideoView, icon: View) {
        try {
            if (vv.isPlaying) {
                vv.pause()
                icon.visibility = View.VISIBLE
                activeVideoView = null
                activeVideoIcon = null
                activeVideoPostId = -1
            } else {
                stopActiveVideo()
                vv.start()
                icon.visibility = View.GONE
                activeVideoView = vv
                activeVideoIcon = icon
                activeVideoPostId = post.id
            }
        } catch (e: Exception) {
            Ui.toast(this, "Video could not play")
        }
    }

    /** Stop the currently playing feed video (if any) and reset its icon. */
    private fun stopActiveVideo() {
        try { activeVideoView?.stopPlayback() } catch (e: Exception) { }
        try { activeVideoIcon?.visibility = View.VISIBLE } catch (e: Exception) { }
        activeVideoView = null
        activeVideoIcon = null
        activeVideoPostId = -1
    }

    private val userAdapter = object : BaseAdapter() {
        override fun getCount() = searchResults.size
        override fun getItem(p: Int) = searchResults[p]
        override fun getItemId(p: Int) = searchResults[p].id.toLong()
        override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
            val v = cv ?: LayoutInflater.from(this@HomeActivity)
                .inflate(R.layout.item_user, parent, false)
            val u = searchResults[pos]
            val tvAvatar = v.findViewById<TextView>(R.id.tvAvatar)
            val ivAvatar = v.findViewById<ImageView>(R.id.ivAvatar)
            val tvName = v.findViewById<TextView>(R.id.tvName)
            val tvSub = v.findViewById<TextView>(R.id.tvSub)
            Ui.setAvatarPhoto(ivAvatar, tvAvatar, u)
            tvName.text = u.displayName
            tvSub.text = "@${u.username} • ${u.followersCount} followers"
            return v
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        ThemeHelper.apply(this)
        // Global stale-session handling (same as MainActivity): a 401
        // clears the saved token and routes to the login screen, so the
        // app never stays on a dead home/profile screen.
        Api.appContext = applicationContext
        Api.onUnauthorized = {
            val intent = Intent(applicationContext, AuthActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TASK
            applicationContext.startActivity(intent)
        }
        setContentView(R.layout.activity_home)

        tabHome = findViewById(R.id.tabHome)
        tabExplore = findViewById(R.id.tabExplore)
        tabProfile = findViewById(R.id.tabProfile)
        lvFeed = findViewById(R.id.lvFeed)
        tvEmptyFeed = findViewById(R.id.tvEmptyFeed)
        tvStatus = findViewById(R.id.tvStatus)
        storyRow = findViewById(R.id.storyRow)
        etSearch = findViewById(R.id.etSearch)
        lvUsers = findViewById(R.id.lvUsers)
        tvNoUsers = findViewById(R.id.tvNoUsers)
        btnRefresh = findViewById(R.id.btnRefresh)
        tvUnreadBadge = findViewById(R.id.tvUnreadBadge)
        tvNotifBadge = findViewById(R.id.tvNotifBadge)
        flHeart = findViewById(R.id.flHeart)
        flHeart.setOnClickListener {
            // Clear the badge at once (don't wait for the next 30s poll)
            // and mark everything read on the server, then open the list.
            tvNotifBadge.visibility = View.GONE
            Api.call("POST", "/api/notifications/read") { _, _, _ -> }
            startActivity(Intent(this, NotificationsActivity::class.java))
        }

        lvFeed.adapter = feedAdapter
        lvUsers.adapter = userAdapter

        etSearch.addTextChangedListener(object : TextWatcher {
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

        findViewById<ImageButton>(R.id.navHome).setOnClickListener { showTab(0) }
        findViewById<ImageButton>(R.id.navExplore).setOnClickListener { showTab(1) }
        // Create (+) in the bottom nav: opens the create chooser, never
        // switches tabs. (It used to live in the header.)
        findViewById<View>(R.id.navCreate).setOnClickListener { showCreateChooser() }
        findViewById<View>(R.id.navMessages).setOnClickListener {
            startActivity(Intent(this, DmListActivity::class.java))
        }
        findViewById<View>(R.id.navProfile).setOnClickListener { showTab(3) }
        navProfileAvatar = findViewById(R.id.navProfileAvatar)
        navProfilePhoto = findViewById(R.id.ivNavProfilePhoto)
        updateNavProfile()
        btnRefresh.setOnClickListener { loadHome() }

        lvFeed.setOnItemClickListener { _, _, pos, _ -> openPostDetail(feed[pos].id) }
        lvUsers.setOnItemClickListener { _, _, pos, _ ->
            startActivity(Intent(this, ProfileActivity::class.java)
                .putExtra("user_id", searchResults[pos].id))
        }

        profileController = ProfileController(this, tabProfile, Prefs.myUserId)

        showTab(0)
        loadHome()

        if (!Prefs.seenWelcome) {
            Prefs.seenWelcome = true
            AlertDialog.Builder(this)
                .setTitle("Welcome to finsta! 📸")
                .setMessage(
                    "• Home: stories + photos from people you follow\n" +
                    "• 🔍 Search people: type a username or name to find people\n" +
                    "• ❤️ (top right): your notifications — follows, likes, comments, messages\n" +
                    "• ✈ (bottom bar): your messages — tap ✉ on someone's profile to start a DM\n" +
                    "• + (bottom bar): share a photo post or a 24-hour story (photo or video, max 59 seconds)\n" +
                    "• Profile: your photos, highlights and settings\n\n" +
                    "Tip: if the app can't reach the server, check the Server URL in Settings — your phone needs the server's public link, not localhost."
                )
                .setPositiveButton("Got it", null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        updateNavProfile()
        refreshUnread()
        refreshNotifCount()
        startUnreadPolling()
        when (currentTab) {
            0 -> loadHome()
            1 -> loadExplore()
            3 -> profileController?.load()
        }
    }

    override fun onPause() {
        // A feed video must not keep playing once the screen is left.
        stopActiveVideo()
        stopUnreadPolling()
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (profileController?.onActivityResult(requestCode, resultCode, data) == true) {
            updateNavProfile()
        }
    }

    /** Paint the bottom-nav profile avatar: photo when a DP is set, initials otherwise. */
    private fun updateNavProfile() {
        Ui.setAvatarPhoto(
            navProfilePhoto, navProfileAvatar, Prefs.myAvatarUrl,
            Prefs.myDisplayName.ifEmpty { Prefs.myUsername }, Prefs.myUsername
        )
    }

    /** Tint the bottom-nav icons: selected tab bright, others muted. */
    private fun tintNav() {
        val selected = resources.getColor(R.color.textPrimary, theme)
        val muted = resources.getColor(R.color.textSecondary, theme)
        findViewById<ImageView>(R.id.navHome)
            .setColorFilter(if (currentTab == 0) selected else muted)
        findViewById<ImageView>(R.id.navExplore)
            .setColorFilter(if (currentTab == 1) selected else muted)
        // Create (+) and Messages are actions, not tabs — always muted.
        findViewById<ImageView>(R.id.ivNavCreate).setColorFilter(muted)
        // Messages is an action (opens the DM list), not a tab — always muted.
        findViewById<ImageView>(R.id.ivNavMessages).setColorFilter(muted)
        navProfileAvatar.alpha = if (currentTab == 3) 1.0f else 0.55f
    }

    /** Red number on the Messages icon: the unread DM count (hidden at 0,
     * "99+" above 99). Same style as the notifications badge. */
    private fun refreshUnread() {
        Api.call("GET", "/api/dm/unread") { ok, json, _ ->
            runOnUiThread {
                val n = if (ok && json != null) json.optInt("unread") else 0
                tvUnreadBadge.visibility = if (n > 0) View.VISIBLE else View.GONE
                tvUnreadBadge.text = if (n > 99) "99+" else n.toString()
            }
        }
    }

    /** Red number on the bell while any notification is unread. */
    private fun refreshNotifCount() {
        Api.call("GET", "/api/notifications/unread-count") { ok, json, _ ->
            runOnUiThread {
                val n = if (ok && json != null) json.optInt("unread") else 0
                tvNotifBadge.visibility = if (n > 0) View.VISIBLE else View.GONE
                tvNotifBadge.text = if (n > 99) "99+" else n.toString()
            }
        }
    }

    private fun startUnreadPolling() {
        stopUnreadPolling()
        val r = object : Runnable {
            override fun run() {
                refreshUnread()
                refreshNotifCount()
                unreadHandler.postDelayed(this, 30_000)
            }
        }
        unreadRunnable = r
        unreadHandler.postDelayed(r, 30_000)
    }

    private fun stopUnreadPolling() {
        unreadRunnable?.let { unreadHandler.removeCallbacks(it) }
        unreadRunnable = null
    }

    override fun onBackPressed() {
        // Double-press to exit from home; a single Back from Explore/Profile goes Home first.
        if (currentTab != 0) {
            showTab(0)
            return
        }
        if (System.currentTimeMillis() - lastBackPress > 2000) {
            lastBackPress = System.currentTimeMillis()
            Ui.toast(this, "Press Back again to exit")
        } else {
            super.onBackPressed()
        }
    }

    private fun showTab(tab: Int) {
        currentTab = tab
        if (tab != 0) stopActiveVideo()
        tabHome.visibility = if (tab == 0) View.VISIBLE else View.GONE
        tabExplore.visibility = if (tab == 1) View.VISIBLE else View.GONE
        tabProfile.visibility = if (tab == 3) View.VISIBLE else View.GONE
        // The notifications heart shows on Home only: the Search and
        // Profile pages stay clean. (Messages opens the DM list, a
        // separate screen, not a tab — so there is no header there.)
        flHeart.visibility = if (tab == 0) View.VISIBLE else View.GONE
        tintNav()
        when (tab) {
            0 -> loadHome()
            1 -> loadExplore()
            3 -> profileController?.load()
        }
    }

    private fun showCreateChooser() {
        AlertDialog.Builder(this)
            .setTitle("Create")
            .setItems(arrayOf("📷 Photo Post", "✨ Story (24 hours)")) { _, which ->
                if (which == 0) {
                    startActivity(Intent(this, CreatePostActivity::class.java))
                } else {
                    startActivity(Intent(this, CreateStoryActivity::class.java))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openPostDetail(postId: Int) {
        startActivity(Intent(this, PostDetailActivity::class.java).putExtra("post_id", postId))
    }

    private fun loadHome() {
        setStatus("Connecting…")
        loadStories()
        loadFeed()
    }

    private fun setStatus(text: String?) {
        if (text.isNullOrEmpty()) {
            tvStatus.visibility = View.GONE
        } else {
            tvStatus.visibility = View.VISIBLE
            tvStatus.text = text
        }
    }

    private fun loadStories() {
        Api.callArray("GET", "/api/stories") { ok, arr, _ ->
            runOnUiThread {
                if (ok && arr != null) {
                    val list = mutableListOf<StoryGroup>()
                    for (i in 0 until arr.length()) {
                        try { list.add(StoryGroup.fromJson(arr.getJSONObject(i))) } catch (e: Exception) { }
                    }
                    storyGroups = list
                    buildStoryRow()
                    warmUpStoryVideos(list)
                }
            }
        }
    }

    /**
     * v3.8: warm up Cloudinary transforms in the background. The first
     * play of a video story was slow or failed outright (Retry) because
     * the derived faststart video (and its poster jpg) had to be
     * generated on first request — a full re-encode takes 5–30s, longer
     * than the player's prepare timeout. A HEAD request triggers the
     * generation without downloading the body, so by the time the user
     * taps a story ring the files are ready and playback starts at
     * once. Best-effort: any failure is silently ignored, playback
     * still works, just slower on first play. Capped at 6 videos.
     */
    private fun warmUpStoryVideos(groups: List<StoryGroup>) {
        Thread {
            try {
                var warmed = 0
                for (g in groups) {
                    for (s in g.stories) {
                        if (warmed >= 6) return@Thread
                        if (s.videoUrl.isEmpty()) continue
                        val abs = Api.absUrl(s.videoUrl)
                        warmUrl(playableVideoUrl(abs))
                        posterImageUrl(abs)?.let { warmUrl(it) }
                        warmed++
                    }
                }
            } catch (e: Exception) {
            }
        }.start()
    }

    private fun warmUrl(url: String) {
        try {
            val req = Request.Builder().url(url).head().build()
            Api.http.newCall(req).execute().use { }
        } catch (e: Exception) {
        }
    }

    private fun buildStoryRow() {
        storyRow.removeAllViews()
        // "Your story" circle first. Ring follows the same rules as every
        // other circle: red while I have an unseen story, white once I have
        // watched all of mine, NO ring at all when I have no active story.
        val myGroup = storyGroups.firstOrNull { it.user.id == Prefs.myUserId }
        val addView = LayoutInflater.from(this).inflate(R.layout.item_story_circle, storyRow, false)
        val addAvatar = addView.findViewById<TextView>(R.id.tvAvatar)
        val addPhoto = addView.findViewById<ImageView>(R.id.ivAvatar)
        Ui.setAvatarPhoto(addPhoto, addAvatar, Prefs.myAvatarUrl,
            Prefs.myDisplayName.ifEmpty { Prefs.myUsername }, Prefs.myUsername)
        addView.findViewById<TextView>(R.id.tvName).text = "Your story"
        val addRing = addView.findViewById<View>(R.id.vRing)
        if (myGroup == null || myGroup.stories.isEmpty()) {
            addRing.visibility = View.GONE
        } else {
            addRing.visibility = View.VISIBLE
            val unviewed = myGroup.stories.count { !it.viewedByMe }
            addRing.setBackgroundResource(
                if (unviewed == 0) R.drawable.story_ring_seen
                else R.drawable.story_ring_new
            )
        }
        addView.setOnClickListener {
            startActivity(Intent(this, CreateStoryActivity::class.java))
        }
        storyRow.addView(addView)
        for (group in storyGroups) {
            val v = LayoutInflater.from(this).inflate(R.layout.item_story_circle, storyRow, false)
            val tvAvatar = v.findViewById<TextView>(R.id.tvAvatar)
            val ivAvatar = v.findViewById<ImageView>(R.id.ivAvatar)
            val tvName = v.findViewById<TextView>(R.id.tvName)
            val ring = v.findViewById<View>(R.id.vRing)
            Ui.setAvatarPhoto(ivAvatar, tvAvatar, group.user)
            tvName.text = if (group.user.id == Prefs.myUserId) "Your story" else group.user.displayName
            // Instagram-style ring, from the server's viewed_by_me flag:
            // red = at least one story unseen, white = all seen,
            // no ring = no active story (cannot normally happen here —
            // the list only carries groups with active stories — but
            // be safe and hide it rather than showing a stale ring).
            if (group.stories.isEmpty()) {
                ring.visibility = View.GONE
            } else {
                ring.visibility = View.VISIBLE
                val unviewed = group.stories.count { !it.viewedByMe }
                ring.setBackgroundResource(
                    if (unviewed == 0) R.drawable.story_ring_seen
                    else R.drawable.story_ring_new
                )
            }
            v.setOnClickListener {
                startActivity(
                    Intent(this, StoryViewerActivity::class.java)
                        .putExtra("user_id", group.user.id)
                        .putExtra("index", 0)
                )
            }
            storyRow.addView(v)
        }
    }

    private fun loadFeed() {
        Api.callArray("GET", "/api/posts?limit=20") { ok, arr, err ->
            runOnUiThread {
                if (ok && arr != null) {
                    setStatus(null)
                    val list = mutableListOf<Post>()
                    for (i in 0 until arr.length()) {
                        try { list.add(Post.fromJson(arr.getJSONObject(i))) } catch (e: Exception) { }
                    }
                    feed = list
                    feedAdapter.notifyDataSetChanged()
                    tvEmptyFeed.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                    lvFeed.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
                } else {
                    setStatus("Couldn't connect — tap ↻ to retry")
                    tvEmptyFeed.visibility = View.VISIBLE
                    tvEmptyFeed.text = "Couldn't load your feed.\nServer jag raha hai, 30–60 second me retry karo."
                    lvFeed.visibility = View.GONE
                }
            }
        }
    }

    /** Explore is pure user search: re-run the current query if any,
     * otherwise show the hint (no default list, no photo grid). */
    private fun loadExplore() {
        searchUsers(etSearch.text.toString().trim())
    }

    private fun searchUsers(q: String) {
        // Empty query: no default content — just the hint.
        if (q.isEmpty()) {
            searchResults = emptyList()
            userAdapter.notifyDataSetChanged()
            lvUsers.visibility = View.GONE
            tvNoUsers.visibility = View.VISIBLE
            tvNoUsers.text = getString(R.string.search_hint)
            return
        }
        val encoded = java.net.URLEncoder.encode(q, "UTF-8")
        Api.callArray("GET", "/api/users?q=$encoded") { ok, arr, _ ->
            runOnUiThread {
                if (ok && arr != null) {
                    val list = mutableListOf<User>()
                    for (i in 0 until arr.length()) {
                        try { list.add(User.fromJson(arr.getJSONObject(i))) } catch (e: Exception) { }
                    }
                    searchResults = list
                    userAdapter.notifyDataSetChanged()
                    lvUsers.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
                    if (list.isEmpty()) {
                        tvNoUsers.visibility = View.VISIBLE
                        tvNoUsers.text = getString(R.string.no_users_found)
                    } else {
                        tvNoUsers.visibility = View.GONE
                    }
                }
            }
        }
    }
}
