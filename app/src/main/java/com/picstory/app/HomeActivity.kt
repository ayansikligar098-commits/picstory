package com.picstory.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
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
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
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
    private lateinit var vUnreadDot: View
    private lateinit var navProfileAvatar: TextView
    private lateinit var navProfilePhoto: ImageView

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
    // Unread-DM polling (refreshes the red dot on the Messages icon).
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
            val ivImage = v.findViewById<android.widget.ImageView>(R.id.ivImage)
            val btnLike = v.findViewById<Button>(R.id.btnLike)
            val btnComment = v.findViewById<Button>(R.id.btnComment)
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
            if (post.imageUrl.isNotEmpty()) {
                ivImage.visibility = View.VISIBLE
                ImageLoader.load(post.imageUrl, ivImage, 1000)
            } else {
                ivImage.visibility = View.GONE
            }
            btnLike.text = (if (post.likedByMe) "❤️ " else "🤍 ") + post.likeCount
            btnComment.text = "💬 " + post.commentCount
            btnLike.setOnClickListener {
                // Guard: ignore taps while a like request for this post is in flight.
                if (!likeBusyIds.add(post.id)) return@setOnClickListener
                Api.call("POST", "/api/posts/${post.id}/like") { ok, json, _ ->
                    runOnUiThread {
                        likeBusyIds.remove(post.id)
                        if (ok && json != null) {
                            post.likedByMe = json.optBoolean("liked")
                            post.likeCount = json.optInt("like_count")
                            notifyDataSetChanged()
                        }
                    }
                }
            }
            btnComment.setOnClickListener { openPostDetail(post.id) }
            ivImage.setOnClickListener { openPostDetail(post.id) }
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
        vUnreadDot = findViewById(R.id.vUnreadDot)

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
        findViewById<View>(R.id.navMessages).setOnClickListener {
            startActivity(Intent(this, DmListActivity::class.java))
        }
        findViewById<View>(R.id.navProfile).setOnClickListener { showTab(3) }
        navProfileAvatar = findViewById(R.id.navProfileAvatar)
        navProfilePhoto = findViewById(R.id.ivNavProfilePhoto)
        updateNavProfile()
        btnRefresh.setOnClickListener { loadHome() }
        findViewById<Button>(R.id.btnCreate).setOnClickListener { showCreateChooser() }

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
                .setTitle("Welcome to 70a! 📸")
                .setMessage(
                    "• Home: stories + photos from people you follow\n" +
                    "• 🔍 Search: type a username to find people\n" +
                    "• ✈ (bottom bar): your messages — tap ✉ on someone's profile to start a DM\n" +
                    "• + (top right): share a photo post or a 24-hour story\n" +
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
        startUnreadPolling()
        when (currentTab) {
            0 -> loadHome()
            1 -> loadExplore()
            3 -> profileController?.load()
        }
    }

    override fun onPause() {
        // Profile music must not keep playing once the app is left.
        profileController?.stopMusic()
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
        // Messages is an action (opens the DM list), not a tab — always muted.
        findViewById<ImageView>(R.id.ivNavMessages).setColorFilter(muted)
        navProfileAvatar.alpha = if (currentTab == 3) 1.0f else 0.55f
    }

    /** Red dot on the Messages icon while any DM is unread. */
    private fun refreshUnread() {
        Api.call("GET", "/api/dm/unread") { ok, json, _ ->
            runOnUiThread {
                val n = if (ok && json != null) json.optInt("unread") else 0
                vUnreadDot.visibility = if (n > 0) View.VISIBLE else View.GONE
            }
        }
    }

    private fun startUnreadPolling() {
        stopUnreadPolling()
        val r = object : Runnable {
            override fun run() {
                refreshUnread()
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
        if (tab != 3) profileController?.stopMusic()
        tabHome.visibility = if (tab == 0) View.VISIBLE else View.GONE
        tabExplore.visibility = if (tab == 1) View.VISIBLE else View.GONE
        tabProfile.visibility = if (tab == 3) View.VISIBLE else View.GONE
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
                }
            }
        }
    }

    private fun buildStoryRow() {
        storyRow.removeAllViews()
        // "Your story" circle first.
        val addView = LayoutInflater.from(this).inflate(R.layout.item_story_circle, storyRow, false)
        val addAvatar = addView.findViewById<TextView>(R.id.tvAvatar)
        val addPhoto = addView.findViewById<ImageView>(R.id.ivAvatar)
        Ui.setAvatarPhoto(addPhoto, addAvatar, Prefs.myAvatarUrl,
            Prefs.myDisplayName.ifEmpty { Prefs.myUsername }, Prefs.myUsername)
        addView.findViewById<TextView>(R.id.tvName).text = "Your story"
        addView.findViewById<View>(R.id.vRing)
            .setBackgroundResource(R.drawable.story_ring_seen)
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
            val unviewed = group.stories.count { !it.viewedByMe }
            ring.setBackgroundResource(
                if (group.user.id == Prefs.myUserId || unviewed == 0) R.drawable.story_ring_seen
                else R.drawable.story_ring_new
            )
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
