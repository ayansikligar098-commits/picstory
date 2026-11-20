package com.picstory.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import org.json.JSONObject

/**
 * Wires a profile screen (profile_content.xml): header, counts, follow,
 * edit, highlights row, posts grid. Shared by HomeActivity's Profile tab
 * and ProfileActivity so both stay identical.
 */
class ProfileController(
    private val activity: Activity,
    private val root: View,
    private val userId: Int
) {
    private val tvAvatar: TextView = root.findViewById(R.id.tvAvatar)
    private val ivAvatar: ImageView = root.findViewById(R.id.ivAvatar)
    private val tvName: TextView = root.findViewById(R.id.tvName)
    private val tvUsername: TextView = root.findViewById(R.id.tvUsername)
    private val tvBio: TextView = root.findViewById(R.id.tvBio)
    private val tvPostsCount: TextView = root.findViewById(R.id.tvPostsCount)
    private val tvFollowersCount: TextView = root.findViewById(R.id.tvFollowersCount)
    private val tvFollowingCount: TextView = root.findViewById(R.id.tvFollowingCount)
    private val btnFollow: Button = root.findViewById(R.id.btnFollow)
    private val btnMessage: Button = root.findViewById(R.id.btnMessage)
    private val btnBlocked: Button = root.findViewById(R.id.btnBlocked)
    private val btnEditProfile: Button = root.findViewById(R.id.btnEditProfile)
    private val highlightsRow: LinearLayout = root.findViewById(R.id.highlightsRow)
    private val tvNoHighlights: TextView = root.findViewById(R.id.tvNoHighlights)
    private val gvPosts: GridView = root.findViewById(R.id.gvPosts)
    private val tvEmptyPosts: TextView = root.findViewById(R.id.tvEmptyPosts)

    private var user: User? = null
    private var posts: List<Post> = emptyList()
    private var followBusy = false
    private var avatarUploading = false

    companion object {
        /** Gallery request code for picking a profile photo. */
        const val REQ_AVATAR = 4001
        /** Username: lowercase letters, numbers, underscore; 3–20 chars. */
        val USERNAME_OK = Regex("^[a-z0-9_]{3,20}$")
        /** Profile-menu (≡) item ids. */
        const val MENU_BLOCK = 1
        const val MENU_SHARE = 2
        const val MENU_BLOCKED = 3
    }

    init {
        tvAvatar.setOnClickListener { maybePickAvatar() }
        ivAvatar.setOnClickListener { maybePickAvatar() }
        btnFollow.setOnClickListener { toggleFollow() }
        btnMessage.setOnClickListener { openDm() }
        btnBlocked.setOnClickListener { onBlockedButton() }
        btnEditProfile.setOnClickListener { showEditProfile() }
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
                    // A 401 routes to the login screen via Api's global
                    // handler; any other failure just shows the error.
                    Ui.toast(activity, err ?: "Profile could not be opened")
                    return@runOnUiThread
                }
                val u = User.fromJson(json)
                user = u
                Ui.setAvatarPhoto(ivAvatar, tvAvatar, u)
                tvName.text = u.displayName
                tvUsername.text = "@${u.username}"
                tvBio.text = u.bio.ifEmpty { "No bio yet" }
                tvPostsCount.text = u.postCount.toString()
                tvFollowersCount.text = u.followersCount.toString()
                tvFollowingCount.text = u.followingCount.toString()
                val own = u.id == Prefs.myUserId
                // v3.3: a block in either direction hides Follow / Message
                // and shows one big "Blocked" button instead.
                val blockedEither = u.blockedByMe || u.blockedMe
                btnFollow.visibility = if (own || blockedEither) View.GONE else View.VISIBLE
                btnMessage.visibility = if (own || blockedEither) View.GONE else View.VISIBLE
                btnBlocked.visibility = if (own || !blockedEither) View.GONE else View.VISIBLE
                btnEditProfile.visibility = if (own) View.VISIBLE else View.GONE
                updateFollowButton(u)
            }
        }
    }

    private fun updateFollowButton(u: User) {
        // White Follow button (v3.1, no purple); "Following" stays the
        // dark edit_bg style it always had.
        btnFollow.text = if (u.followedByMe) "Following" else "Follow"
        btnFollow.setBackgroundResource(
            if (u.followedByMe) R.drawable.edit_bg else R.drawable.btn_white
        )
        btnFollow.setTextColor(
            activity.resources.getColor(
                if (u.followedByMe) R.color.textPrimary else R.color.black, activity.theme
            )
        )
    }

    // ---- Profile menu (≡, v3.1) ------------------------------------------
    // Other profiles: Block/Unblock + Share. Own profile: Share +
    // Blocked users (you cannot block yourself).

    /** Opens the hamburger menu anchored to the given view. Called from
     * ProfileActivity's header button and HomeActivity's Profile tab. */
    fun showProfileMenu(anchor: View) {
        val u = user ?: return
        val own = u.id == Prefs.myUserId
        val menu = PopupMenu(activity, anchor)
        if (own) {
            menu.menu.add(0, MENU_SHARE, 0, activity.getString(R.string.share))
            menu.menu.add(0, MENU_BLOCKED, 1, activity.getString(R.string.blocked_users))
        } else {
            menu.menu.add(
                0, MENU_BLOCK, 0,
                activity.getString(if (u.blockedByMe) R.string.unblock else R.string.block)
            )
            menu.menu.add(0, MENU_SHARE, 1, activity.getString(R.string.share))
        }
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_BLOCK -> {
                    if (u.blockedByMe) doUnblock() else confirmBlock()
                    true
                }
                MENU_SHARE -> {
                    shareProfile()
                    true
                }
                MENU_BLOCKED -> {
                    activity.startActivity(
                        Intent(activity, BlockedUsersActivity::class.java))
                    true
                }
                else -> false
            }
        }
        menu.show()
    }

    /** Big "Blocked" button (v3.3): if I blocked them, offer to unblock;
     * if they blocked me, the button is just a notice. */
    private fun onBlockedButton() {
        val u = user ?: return
        if (u.blockedByMe) {
            AlertDialog.Builder(activity)
                .setTitle("Unblock @${u.username}?")
                .setMessage(
                    "They will be able to message you and see your posts " +
                    "and stories again."
                )
                .setPositiveButton("Unblock") { _, _ -> doUnblock() }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            Ui.toast(activity, activity.getString(R.string.blocked_by_user))
        }
    }

    private fun confirmBlock() {
        val u = user ?: return
        AlertDialog.Builder(activity)
            .setTitle("Block @${u.username}?")
            .setMessage(
                "They won't be able to message you or see your posts and " +
                "stories, and you won't see theirs."
            )
            .setPositiveButton("Block") { _, _ -> doBlock() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doBlock() {
        val u = user ?: return
        Api.call("POST", "/api/users/${u.id}/block") { ok, json, err ->
            activity.runOnUiThread {
                if (ok && json != null && json.optBoolean("blocked")) {
                    user = u.copy(blockedByMe = true, followedByMe = false)
                    Ui.toast(activity, "Blocked @${u.username}")
                    loadUser()
                } else {
                    Ui.toast(activity, err ?: "Could not block")
                }
            }
        }
    }

    private fun doUnblock() {
        val u = user ?: return
        Api.call("POST", "/api/users/${u.id}/unblock") { ok, json, err ->
            activity.runOnUiThread {
                if (ok && json != null && !json.optBoolean("blocked", true)) {
                    user = u.copy(blockedByMe = false)
                    Ui.toast(activity, "Unblocked @${u.username}")
                    loadUser()
                } else {
                    Ui.toast(activity, err ?: "Could not unblock")
                }
            }
        }
    }

    /** Shares this profile as text via the Android share sheet. */
    private fun shareProfile() {
        val u = user ?: return
        val text = "finsta • @${u.username}"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        activity.startActivity(Intent.createChooser(intent, "Share profile"))
    }

    /** Open a 1:1 DM chat with the user on screen (other users only). */
    private fun openDm() {
        val u = user ?: return
        activity.startActivity(
            Intent(activity, DmChatActivity::class.java)
                .putExtra("user_id", u.id)
                .putExtra("display_name", u.displayName)
                .putExtra("username", u.username)
                .putExtra("avatar_url", u.avatarUrl)
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
        val etUsername = view.findViewById<EditText>(R.id.etUsername)
        val etBio = view.findViewById<EditText>(R.id.etBio)
        etName.setText(u.displayName)
        etUsername.setText(u.username)
        etBio.setText(u.bio)
        val btnChangePhoto = view.findViewById<Button>(R.id.btnChangePhoto)
        val btnRemovePhoto = view.findViewById<Button>(R.id.btnRemovePhoto)
        btnRemovePhoto.visibility = if (u.avatarUrl.isNotEmpty()) View.VISIBLE else View.GONE
        val dlg = AlertDialog.Builder(activity)
            .setTitle("Edit profile")
            .setView(view)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
        btnChangePhoto.setOnClickListener { dlg.dismiss(); pickAvatar() }
        btnRemovePhoto.setOnClickListener { dlg.dismiss(); removeAvatar() }
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = etName.text.toString().trim()
                val username = etUsername.text.toString().trim().lowercase()
                val bio = etBio.text.toString().trim()
                if (name.isEmpty()) {
                    Ui.toast(activity, "Please enter your name")
                    return@setOnClickListener
                }
                if (!USERNAME_OK.matches(username)) {
                    Ui.toast(activity, "Username: 3–20 letters, numbers or _ only")
                    return@setOnClickListener
                }
                val body = JSONObject().apply {
                    put("display_name", name)
                    put("username", username)
                    put("bio", bio)
                }
                Api.call("PATCH", "/api/users/me", body) { ok, json, err ->
                    activity.runOnUiThread {
                        if (ok && json != null) {
                            Prefs.myDisplayName =
                                json.optString("display_name", Prefs.myDisplayName)
                            Prefs.myUsername =
                                json.optString("username", Prefs.myUsername)
                            Ui.toast(activity, "Profile saved")
                            loadUser()
                        } else {
                            Ui.toast(activity, err ?: "Could not save")
                        }
                    }
                }
                dlg.dismiss()
            }
        }
        dlg.show()
    }

    // ---- Profile photo (DP) ----------------------------------------------

    private fun maybePickAvatar() {
        if (user?.id == Prefs.myUserId) pickAvatar()
    }

    private fun pickAvatar() {
        if (avatarUploading) return
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
        activity.startActivityForResult(
            Intent.createChooser(intent, "Choose a profile photo"), REQ_AVATAR
        )
    }

    /** Handles the gallery picker result. Returns true if handled. */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode == REQ_AVATAR) {
            if (resultCode != Activity.RESULT_OK || data?.data == null) return true
            val bytes = try {
                activity.contentResolver.openInputStream(data.data!!)?.use { it.readBytes() }
            } catch (e: Exception) {
                null
            }
            if (bytes == null || bytes.isEmpty()) {
                Ui.toast(activity, "Could not read that photo")
                return true
            }
            uploadAvatar(bytes)
            return true
        }
        return false
    }

    private fun uploadAvatar(bytes: ByteArray) {
        if (avatarUploading) return
        avatarUploading = true
        Ui.toast(activity, "Uploading photo…")
        // Instant local preview (circular crop of the chosen photo).
        try {
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp != null) {
                tvAvatar.visibility = View.GONE
                ivAvatar.visibility = View.VISIBLE
                ivAvatar.setImageBitmap(ImageLoader.circularCrop(bmp))
            }
        } catch (e: Exception) {
            // Preview is best-effort; the upload result decides.
        }
        Api.uploadImage(bytes, "avatar.jpg") { ok, url, err ->
            if (!ok || url.isNullOrEmpty()) {
                activity.runOnUiThread {
                    avatarUploading = false
                    Ui.toast(activity, err ?: "Upload failed")
                    loadUser()
                }
                return@uploadImage
            }
            val body = JSONObject().apply { put("avatar_url", url) }
            Api.call("PATCH", "/api/users/me", body) { ok2, json, err2 ->
                activity.runOnUiThread {
                    avatarUploading = false
                    if (ok2 && json != null) {
                        Prefs.myAvatarUrl = json.optString("avatar_url", url)
                        user = user?.copy(avatarUrl = Prefs.myAvatarUrl)
                        Ui.toast(activity, "Profile photo updated")
                        loadUser()
                    } else {
                        Ui.toast(activity, err2 ?: "Could not save photo")
                        loadUser()
                    }
                }
            }
        }
    }

    private fun removeAvatar() {
        val body = JSONObject().apply { put("avatar_url", "") }
        Api.call("PATCH", "/api/users/me", body) { ok, json, err ->
            activity.runOnUiThread {
                if (ok && json != null) {
                    Prefs.myAvatarUrl = ""
                    user = user?.copy(avatarUrl = "")
                    Ui.toast(activity, "Profile photo removed")
                    loadUser()
                } else {
                    Ui.toast(activity, err ?: "Could not remove photo")
                }
            }
        }
    }
}
