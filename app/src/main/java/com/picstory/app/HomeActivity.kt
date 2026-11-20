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
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import org.json.JSONObject

/**
 * Home screen: bottom nav Home / Explore / Create / Profile.
 * Home = stories row + feed. Explore = search + grid. Create = chooser.
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
    private lateinit var gvExplore: GridView
    private lateinit var tvEmptyExplore: TextView
    private lateinit var btnRefresh: Button

    private var storyGroups: List<StoryGroup> = emptyList()
    private var feed: List<Post> = emptyList()
    private var explorePosts: List<Post> = emptyList()
    private var searchResults: List<User> = emptyList()
    private var currentTab = 0
    private var profileController: ProfileController? = null
    private var lastBackPress = 0L
    private val likeBusyIds = mutableSetOf<Int>()
    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null

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
            val tvName = v.findViewById<TextView>(R.id.tvName)
            val tvTime = v.findViewById<TextView>(R.id.tvTime)
            val tvCaption = v.findViewById<TextView>(R.id.tvCaption)
            val ivImage = v.findViewById<android.widget.ImageView>(R.id.ivImage)
            val btnLike = v.findViewById<Button>(R.id.btnLike)
            val btnComment = v.findViewById<Button>(R.id.btnComment)
            if (author != null) {
                Ui.setAvatar(tvAvatar, author)
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
            val tvName = v.findViewById<TextView>(R.id.tvName)
            val tvSub = v.findViewById<TextView>(R.id.tvSub)
            Ui.setAvatar(tvAvatar, u)
            tvName.text = u.displayName
            tvSub.text = "@${u.username} • ${u.followersCount} followers"
            return v
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        ThemeHelper.apply(this)
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
        gvExplore = findViewById(R.id.gvExplore)
        tvEmptyExplore = findViewById(R.id.tvEmptyExplore)
        btnRefresh = findViewById(R.id.btnRefresh)

        lvFeed.adapter = feedAdapter
        lvUsers.adapter = userAdapter
        gvExplore.adapter = PostGridAdapter(this, explorePosts)

        findViewById<Button>(R.id.navHome).setOnClickListener { showTab(0) }
        findViewById<Button>(R.id.navExplore).setOnClickListener { showTab(1) }
        findViewById<Button>(R.id.navCreate).setOnClickListener { showCreateChooser() }
        findViewById<Button>(R.id.navProfile).setOnClickListener { showTab(3) }
        btnRefresh.setOnClickListener { loadHome() }

        lvFeed.setOnItemClickListener { _, _, pos, _ -> openPostDetail(feed[pos].id) }
        lvUsers.setOnItemClickListener { _, _, pos, _ ->
            startActivity(Intent(this, ProfileActivity::class.java)
                .putExtra("user_id", searchResults[pos].id))
        }
        gvExplore.setOnItemClickListener { _, _, pos, _ -> openPostDetail(explorePosts[pos].id) }

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

        profileController = ProfileController(this, tabProfile, Prefs.myUserId)

        showTab(0)
        loadHome()

        if (!Prefs.seenWelcome) {
            Prefs.seenWelcome = true
            AlertDialog.Builder(this)
                .setTitle("Welcome to 70a! 📸")
                .setMessage(
                    "• Home: stories + photos from people you follow\n" +
                    "• Explore: all photos, and search for people to follow\n" +
                    "• + : share a photo post or a 24-hour story\n" +
                    "• Profile: your photos, highlights and settings\n\n" +
                    "Tip: if the app can't reach the server, check the Server URL in Settings — your phone needs the server's public link, not localhost."
                )
                .setPositiveButton("Got it", null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        when (currentTab) {
            0 -> loadHome()
            1 -> loadExplore()
            3 -> profileController?.load()
        }
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
        tabHome.visibility = if (tab == 0) View.VISIBLE else View.GONE
        tabExplore.visibility = if (tab == 1) View.VISIBLE else View.GONE
        tabProfile.visibility = if (tab == 3) View.VISIBLE else View.GONE
        val navIds = intArrayOf(R.id.navHome, R.id.navExplore, R.id.navCreate, R.id.navProfile)
        for (i in navIds.indices) {
            val b = findViewById<Button>(navIds[i])
            b.setTextColor(
                resources.getColor(
                    if (i == tab) R.color.colorPrimary else R.color.textSecondary, theme
                )
            )
        }
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
        Ui.setAvatarText(addAvatar, Prefs.myDisplayName.ifEmpty { Prefs.myUsername }, Prefs.myUsername)
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
            val tvName = v.findViewById<TextView>(R.id.tvName)
            val ring = v.findViewById<View>(R.id.vRing)
            Ui.setAvatar(tvAvatar, group.user)
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
                    tvEmptyFeed.text = "Couldn't load your feed.\nCheck your connection or Server URL in Settings."
                    lvFeed.visibility = View.GONE
                }
            }
        }
    }

    private fun loadExplore() {
        Api.callArray("GET", "/api/explore") { ok, arr, _ ->
            runOnUiThread {
                if (ok && arr != null) {
                    val list = mutableListOf<Post>()
                    for (i in 0 until arr.length()) {
                        try { list.add(Post.fromJson(arr.getJSONObject(i))) } catch (e: Exception) { }
                    }
                    explorePosts = list
                    gvExplore.adapter = PostGridAdapter(this, list)
                    tvEmptyExplore.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                } else {
                    tvEmptyExplore.visibility = View.VISIBLE
                    tvEmptyExplore.text = "Couldn't load Explore — check your connection."
                }
            }
        }
        // Default user list (empty query = everyone).
        searchUsers(etSearch.text.toString().trim())
    }

    private fun searchUsers(q: String) {
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
                    val showList = q.isNotEmpty() || list.isNotEmpty()
                    lvUsers.visibility = if (showList) View.VISIBLE else View.GONE
                    tvNoUsers.visibility =
                        if (q.isNotEmpty() && list.isEmpty()) View.VISIBLE else View.GONE
                    gvExplore.visibility = if (q.isEmpty()) View.VISIBLE else View.GONE
                    tvEmptyExplore.visibility =
                        if (q.isEmpty() && explorePosts.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }
}
