package com.picstory.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * Wires a profile screen (profile_content.xml): header, counts, follow,
 * edit, highlights row, posts grid, settings + logout. Shared by
 * HomeActivity's Profile tab and ProfileActivity so both stay identical.
 */
class ProfileController(
    private val activity: Activity,
    private val root: View,
    private val userId: Int
) {
    private val tvAvatar: TextView = root.findViewById(R.id.tvAvatar)
    private val tvName: TextView = root.findViewById(R.id.tvName)
    private val tvUsername: TextView = root.findViewById(R.id.tvUsername)
    private val tvBio: TextView = root.findViewById(R.id.tvBio)
    private val tvPostsCount: TextView = root.findViewById(R.id.tvPostsCount)
    private val tvFollowersCount: TextView = root.findViewById(R.id.tvFollowersCount)
    private val tvFollowingCount: TextView = root.findViewById(R.id.tvFollowingCount)
    private val btnFollow: Button = root.findViewById(R.id.btnFollow)
    private val btnEditProfile: Button = root.findViewById(R.id.btnEditProfile)
    private val btnNewHighlight: Button = root.findViewById(R.id.btnNewHighlight)
    private val btnSettings: Button = root.findViewById(R.id.btnSettings)
    private val btnLogout: Button = root.findViewById(R.id.btnLogout)
    private val highlightsRow: LinearLayout = root.findViewById(R.id.highlightsRow)
    private val tvNoHighlights: TextView = root.findViewById(R.id.tvNoHighlights)
    private val gvPosts: GridView = root.findViewById(R.id.gvPosts)
    private val tvEmptyPosts: TextView = root.findViewById(R.id.tvEmptyPosts)

    private var user: User? = null
    private var posts: List<Post> = emptyList()
    private var followBusy = false

    init {
        btnFollow.setOnClickListener { toggleFollow() }
        btnEditProfile.setOnClickListener { showEditProfile() }
        btnNewHighlight.setOnClickListener {
            activity.startActivity(Intent(activity, CreateHighlightActivity::class.java))
        }
        btnSettings.setOnClickListener { showSettings() }
        btnLogout.setOnClickListener { confirmLogout() }
        root.findViewById<View>(R.id.statFollowers).setOnClickListener {
            openUserList("followers")
        }
        root.findViewById<View>(R.id.statFollowing).setOnClickListener {
            openUserList("following")
        }
        gvPosts.setOnItemClickListener { _, _, pos, _ ->
            activity.startActivity(
                Intent(activity, PostDetailActivity::class.java)
                    .putExtra("post_id", posts[pos].id)
            )
        }
    }

    fun load() {
        loadUser()
        loadHighlights()
        loadPosts()
    }

    private fun openUserList(mode: String) {
        activity.startActivity(
            Intent(activity, UserListActivity::class.java)
                .putExtra("mode", mode)
                .putExtra("user_id", userId)
        )
    }

    private fun loadUser() {
        Api.call("GET", "/api/users/$userId") { ok, json, err ->
            activity.runOnUiThread {
                if (!ok || json == null) {
                    Ui.toast(activity, err ?: "Profile could not be opened")
                    return@runOnUiThread
                }
                val u = User.fromJson(json)
                user = u
                Ui.setAvatar(tvAvatar, u)
                tvName.text = u.displayName
                tvUsername.text = "@${u.username}"
                tvBio.text = u.bio.ifEmpty { "No bio yet" }
                tvPostsCount.text = u.postCount.toString()
                tvFollowersCount.text = u.followersCount.toString()
                tvFollowingCount.text = u.followingCount.toString()
                val own = u.id == Prefs.myUserId
                btnFollow.visibility = if (own) View.GONE else View.VISIBLE
                btnEditProfile.visibility = if (own) View.VISIBLE else View.GONE
                btnNewHighlight.visibility = if (own) View.VISIBLE else View.GONE
                btnSettings.visibility = if (own) View.VISIBLE else View.GONE
                btnLogout.visibility = if (own) View.VISIBLE else View.GONE
                updateFollowButton(u)
            }
        }
    }

    private fun updateFollowButton(u: User) {
        btnFollow.text = if (u.followedByMe) "Following" else "Follow"
        btnFollow.setBackgroundResource(
            if (u.followedByMe) R.drawable.edit_bg else R.drawable.btn_primary
        )
        btnFollow.setTextColor(
            activity.resources.getColor(
                if (u.followedByMe) R.color.textPrimary else R.color.white, activity.theme
            )
        )
    }

    private fun toggleFollow() {
        if (followBusy) return
        val u = user ?: return
        followBusy = true
        btnFollow.isEnabled = false
        Api.call("POST", "/api/users/${u.id}/follow") { ok, json, err ->
            activity.runOnUiThread {
                followBusy = false
                btnFollow.isEnabled = true
                if (ok && json != null) {
                    val following = json.optBoolean("following")
                    val count = json.optInt("followers_count", u.followersCount)
                    user = u.copy(followedByMe = following, followersCount = count)
                    tvFollowersCount.text = count.toString()
                    updateFollowButton(user!!)
                } else {
                    Ui.toast(activity, err ?: "Could not follow")
                }
            }
        }
    }

    private fun loadHighlights() {
        Api.callArray("GET", "/api/users/$userId/highlights") { ok, arr, _ ->
            activity.runOnUiThread {
                highlightsRow.removeAllViews()
                if (!ok || arr == null) {
                    tvNoHighlights.visibility = View.GONE
                    return@runOnUiThread
                }
                val list = mutableListOf<Highlight>()
                for (i in 0 until arr.length()) {
                    try { list.add(Highlight.fromJson(arr.getJSONObject(i))) } catch (e: Exception) { }
                }
                tvNoHighlights.visibility =
                    if (list.isEmpty() && userId == Prefs.myUserId) View.VISIBLE else View.GONE
                for (h in list) {
                    val v = LayoutInflater.from(activity)
                        .inflate(R.layout.item_highlight, highlightsRow, false)
                    val tvInitial = v.findViewById<TextView>(R.id.tvInitial)
                    val ivCover = v.findViewById<android.widget.ImageView>(R.id.ivCover)
                    val tvHlName = v.findViewById<TextView>(R.id.tvName)
                    tvInitial.text = Ui.initials(h.name)
                    tvHlName.text = h.name
                    if (h.coverUrl.isNotEmpty()) {
                        ivCover.visibility = View.VISIBLE
                        ImageLoader.load(h.coverUrl, ivCover, 300)
                    } else {
                        ivCover.visibility = View.GONE
                    }
                    v.setOnClickListener {
                        activity.startActivity(
                            Intent(activity, StoryViewerActivity::class.java)
                                .putExtra("highlight_id", h.id)
                        )
                    }
                    if (userId == Prefs.myUserId) {
                        v.setOnLongClickListener {
                            confirmDeleteHighlight(h)
                            true
                        }
                    }
                    highlightsRow.addView(v)
                }
            }
        }
    }

    private fun confirmDeleteHighlight(h: Highlight) {
        AlertDialog.Builder(activity)
            .setTitle("Delete highlight?")
            .setMessage("“${h.name}” will be removed. The stories stay where they are.")
            .setPositiveButton("Delete") { _, _ ->
                Api.call("DELETE", "/api/highlights/${h.id}") { ok, _, err ->
                    activity.runOnUiThread {
                        if (ok) loadHighlights()
                        else Ui.toast(activity, err ?: "Could not delete")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadPosts() {
        Api.callArray("GET", "/api/users/$userId/posts") { ok, arr, _ ->
            activity.runOnUiThread {
                if (!ok || arr == null) {
                    tvEmptyPosts.visibility = View.VISIBLE
                    tvEmptyPosts.text = "Couldn't load posts — check your connection and try again."
                    return@runOnUiThread
                }
                val list = mutableListOf<Post>()
                for (i in 0 until arr.length()) {
                    try { list.add(Post.fromJson(arr.getJSONObject(i))) } catch (e: Exception) { }
                }
                posts = list
                tvEmptyPosts.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                if (list.isEmpty()) {
                    tvEmptyPosts.text =
                        if (userId == Prefs.myUserId) "No posts yet — tap + to share your first photo."
                        else "No posts yet."
                }
                gvPosts.adapter = PostGridAdapter(activity, list)
                // GridView inside a ScrollView: size it to fit all rows.
                val cols = 3
                val rows = (list.size + cols - 1) / cols
                val cell = activity.resources.displayMetrics.widthPixels / cols
                val params = gvPosts.layoutParams
                params.height = rows * cell
                gvPosts.layoutParams = params
            }
        }
    }

    private fun showEditProfile() {
        val u = user ?: return
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_edit_profile, null)
        val etName = view.findViewById<EditText>(R.id.etName)
        val etBio = view.findViewById<EditText>(R.id.etBio)
        etName.setText(u.displayName)
        etBio.setText(u.bio)
        AlertDialog.Builder(activity)
            .setTitle("Edit profile")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val body = JSONObject().apply {
                    put("display_name", etName.text.toString().trim())
                    put("bio", etBio.text.toString().trim())
                }
                Api.call("PATCH", "/api/users/me", body) { ok, json, err ->
                    activity.runOnUiThread {
                        if (ok && json != null) {
                            Prefs.myDisplayName = json.optString("display_name", Prefs.myDisplayName)
                            Ui.toast(activity, "Profile saved")
                            loadUser()
                        } else {
                            Ui.toast(activity, err ?: "Could not save")
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSettings() {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_settings, null)
        val etServer = view.findViewById<EditText>(R.id.etServerUrl)
        etServer.setText(Prefs.baseUrl)
        val btnSystem = view.findViewById<Button>(R.id.btnThemeSystem)
        val btnLight = view.findViewById<Button>(R.id.btnThemeLight)
        val btnDark = view.findViewById<Button>(R.id.btnThemeDark)
        val btnSave = view.findViewById<Button>(R.id.btnSaveServer)
        val btnLogoutInSettings = view.findViewById<Button>(R.id.btnLogoutSettings)
        val dlg = AlertDialog.Builder(activity)
            .setTitle("Settings")
            .setView(view)
            .setNegativeButton("Close", null)
            .create()
        fun markTheme() {
            for ((b, t) in listOf(btnSystem to "system", btnLight to "light", btnDark to "dark")) {
                val active = Prefs.theme == t
                b.setBackgroundResource(if (active) R.drawable.btn_primary else R.drawable.edit_bg)
                b.setTextColor(
                    activity.resources.getColor(
                        if (active) R.color.white else R.color.textPrimary, activity.theme
                    )
                )
            }
        }
        markTheme()
        btnSystem.setOnClickListener { Prefs.theme = "system"; ThemeHelper.apply(activity); activity.recreate(); dlg.dismiss() }
        btnLight.setOnClickListener { Prefs.theme = "light"; ThemeHelper.apply(activity); activity.recreate(); dlg.dismiss() }
        btnDark.setOnClickListener { Prefs.theme = "dark"; ThemeHelper.apply(activity); activity.recreate(); dlg.dismiss() }
        btnSave.setOnClickListener {
            val url = etServer.text.toString().trim().trimEnd('/')
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                Ui.toast(activity, "URL must start with http:// or https://")
                return@setOnClickListener
            }
            Prefs.baseUrl = url
            ImageLoader.clear()
            Ui.toast(activity, "Server URL saved")
            dlg.dismiss()
            load()
        }
        btnLogoutInSettings.setOnClickListener {
            dlg.dismiss()
            confirmLogout()
        }
        dlg.show()
    }

    private fun confirmLogout() {
        AlertDialog.Builder(activity)
            .setTitle("Log out?")
            .setMessage("You'll need to log in again to use 70a.")
            .setPositiveButton("Log Out") { _, _ ->
                Prefs.clearAuth()
                val intent = Intent(activity, AuthActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                activity.startActivity(intent)
                activity.finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
