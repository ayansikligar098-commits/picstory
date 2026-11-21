package com.picstory.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * 1:1 DM chat with one user. Polls the thread every 5s while open.
 * Text, photo and video messages; message times; typing indicator;
 * online/offline presence dot. No Seen/Sent receipts (removed in v2.1
 * at the user's request) — opening a chat still marks its messages read
 * internally, so unread counts keep working.
 */
class DmChatActivity : Activity() {
    private var userId: Int = 0
    private var displayName: String = ""
    private var username: String = ""
    private var avatarUrl: String = ""
    private var messages: List<DmMessage> = emptyList()
    private var sendBusy = false
    private var uploadBusy = false
    private var lastCount = -1
    private var lastTypingSentAt = 0L
    private lateinit var lvMessages: ListView
    private lateinit var etMessage: EditText
    private lateinit var vPresenceDot: View
    private lateinit var tvTyping: TextView
    private lateinit var tvBlockedNotice: TextView
    private lateinit var dmInputRow: View
    private val pollHandler = Handler(Looper.getMainLooper())
    private var pollRunnable: Runnable? = null
    private var presenceRunnable: Runnable? = null

    private val adapter = object : BaseAdapter() {
        override fun getCount() = messages.size
        override fun getItem(p: Int) = messages[p]
        override fun getItemId(p: Int) = messages[p].id.toLong()
        override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
            val v = cv ?: LayoutInflater.from(this@DmChatActivity)
                .inflate(R.layout.item_dm_message, parent, false)
            val m = messages[pos]
            val mediaFrame = v.findViewById<FrameLayout>(R.id.mediaFrame)
            val ivMedia = v.findViewById<ImageView>(R.id.ivMedia)
            val tvPlayMedia = v.findViewById<TextView>(R.id.tvPlayMedia)
            val bubbleRow = v.findViewById<LinearLayout>(R.id.msgBubbleRow)
            val tvBody = v.findViewById<TextView>(R.id.tvBody)
            val tvStatus = v.findViewById<TextView>(R.id.tvStatus)
            val mine = m.senderId == Prefs.myUserId
            val time = Ui.clockTime(m.createdAt)

            // Media (photo/video) above the text, if any.
            if (m.mediaUrl.isNotEmpty()) {
                mediaFrame.visibility = View.VISIBLE
                (mediaFrame.layoutParams as LinearLayout.LayoutParams).gravity =
                    if (mine) Gravity.END else Gravity.START
                if (m.mediaKind == "video") {
                    ivMedia.setImageBitmap(null)
                    ivMedia.setBackgroundResource(R.color.colorPrimary)
                    tvPlayMedia.visibility = View.VISIBLE
                } else {
                    ivMedia.setBackgroundResource(R.color.divider)
                    tvPlayMedia.visibility = View.GONE
                    ImageLoader.load(m.mediaUrl, ivMedia, 800)
                }
                mediaFrame.setOnClickListener {
                    startActivity(Intent(this@DmChatActivity,
                        FullscreenMediaActivity::class.java)
                        .putExtra("media_url", m.mediaUrl)
                        .putExtra("media_kind", m.mediaKind))
                }
            } else {
                mediaFrame.visibility = View.GONE
                mediaFrame.setOnClickListener(null)
            }

            if (m.body.isNotEmpty()) {
                tvBody.visibility = View.VISIBLE
                tvBody.text = m.body
            } else {
                tvBody.visibility = View.GONE
            }

            if (mine) {
                bubbleRow.gravity = Gravity.END
                tvBody.setBackgroundResource(R.drawable.btn_primary)
                tvBody.setTextColor(resources.getColor(R.color.white, theme))
                // No Seen/Sent receipts (v2.1): my messages show only the time.
                tvStatus.visibility = if (time.isNotEmpty()) View.VISIBLE else View.GONE
                (tvStatus.layoutParams as LinearLayout.LayoutParams).gravity = Gravity.END
                tvStatus.text = time
            } else {
                bubbleRow.gravity = Gravity.START
                tvBody.setBackgroundResource(R.drawable.edit_bg)
                tvBody.setTextColor(resources.getColor(R.color.textPrimary, theme))
                tvStatus.visibility = if (time.isNotEmpty()) View.VISIBLE else View.GONE
                (tvStatus.layoutParams as LinearLayout.LayoutParams).gravity = Gravity.START
                tvStatus.text = time
            }
            return v
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        ThemeHelper.apply(this)
        setContentView(R.layout.activity_dm_chat)

        userId = intent.getIntExtra("user_id", 0)
        displayName = intent.getStringExtra("display_name") ?: ""
        username = intent.getStringExtra("username") ?: ""
        avatarUrl = intent.getStringExtra("avatar_url") ?: ""
        if (userId == 0) {
            finish()
            return
        }

        lvMessages = findViewById(R.id.lvMessages)
        etMessage = findViewById(R.id.etMessage)
        vPresenceDot = findViewById(R.id.vPresenceDot)
        tvTyping = findViewById(R.id.tvTyping)
        tvBlockedNotice = findViewById(R.id.tvBlockedNotice)
        dmInputRow = findViewById(R.id.dmInputRow)
        lvMessages.adapter = adapter

        val tvAvatar = findViewById<TextView>(R.id.tvAvatar)
        val ivAvatar = findViewById<ImageView>(R.id.ivAvatar)
        Ui.setAvatarPhoto(ivAvatar, tvAvatar, avatarUrl, displayName.ifEmpty { username }, username)
        findViewById<TextView>(R.id.tvDmName).text =
            displayName.ifEmpty { username }.ifEmpty { "Chat" }

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.dmHeader).setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java)
                .putExtra("user_id", userId))
        }
        findViewById<Button>(R.id.btnSend).setOnClickListener { sendMessage() }
        findViewById<Button>(R.id.btnAttach).setOnClickListener { showAttachChooser() }

        // Typing heartbeat: while I type, tell the other side every 3s.
        etMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (s.isNullOrEmpty()) return
                val now = System.currentTimeMillis()
                if (now - lastTypingSentAt < 3000) return
                lastTypingSentAt = now
                Api.call("POST", "/api/dm/$userId/typing") { _, _, _ -> }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        markRead()
        loadThread()
        loadPresence()
        loadBlockState()
        startPolling()
    }

    override fun onPause() {
        stopPolling()
        super.onPause()
    }

    private fun startPolling() {
        stopPolling()
        val r = object : Runnable {
            override fun run() {
                loadThread()
                loadTyping()
                // v4.1: poll every 3s instead of 5s — with the faster
                // backend (batched queries) a new DM now shows in ~1-4s
                // instead of ~4-7s.
                pollHandler.postDelayed(this, 3_000)
            }
        }
        pollRunnable = r
        pollHandler.postDelayed(r, 3_000)
        val pr = object : Runnable {
            override fun run() {
                loadPresence()
                loadBlockState()
                pollHandler.postDelayed(this, 20_000)
            }
        }
        presenceRunnable = pr
        pollHandler.postDelayed(pr, 20_000)
    }

    private fun stopPolling() {
        pollRunnable?.let { pollHandler.removeCallbacks(it) }
        pollRunnable = null
        presenceRunnable?.let { pollHandler.removeCallbacks(it) }
        presenceRunnable = null
    }

    /** Green dot = the other person is online now, red = offline.
     * The backend already hides presence when a block exists in either
     * direction (v3.3), so a blocked user always shows offline here. */
    private fun loadPresence() {
        Api.call("GET", "/api/users/$userId") { ok, json, _ ->
            runOnUiThread {
                if (!ok || json == null) return@runOnUiThread
                val online = try { User.fromJson(json).online } catch (e: Exception) { return@runOnUiThread }
                vPresenceDot.visibility = View.VISIBLE
                vPresenceDot.setBackgroundResource(
                    if (online) R.drawable.dot_green else R.drawable.dot_red
                )
            }
        }
    }

    /** "Blocked" notice (v3.3): when a block exists in either direction,
     * the message input is replaced by a written Blocked notice. */
    private fun loadBlockState() {
        Api.call("GET", "/api/users/$userId") { ok, json, _ ->
            runOnUiThread {
                if (!ok || json == null) return@runOnUiThread
                val u = try { User.fromJson(json) } catch (e: Exception) { return@runOnUiThread }
                applyBlockedState(u.blockedByMe || u.blockedMe)
            }
        }
    }

    private fun applyBlockedState(blocked: Boolean) {
        tvBlockedNotice.visibility = if (blocked) View.VISIBLE else View.GONE
        dmInputRow.visibility = if (blocked) View.GONE else View.VISIBLE
        etMessage.isEnabled = !blocked
    }

    /** "typing…" under the other person's name while they type. */
    private fun loadTyping() {
        Api.call("GET", "/api/dm/$userId/typing") { ok, json, _ ->
            runOnUiThread {
                if (!ok || json == null) return@runOnUiThread
                tvTyping.visibility =
                    if (json.optBoolean("typing")) View.VISIBLE else View.GONE
            }
        }
    }

    private fun loadThread() {
        Api.callArray("GET", "/api/dm/$userId") { ok, arr, _ ->
            runOnUiThread {
                if (!ok || arr == null) return@runOnUiThread
                val list = mutableListOf<DmMessage>()
                for (i in 0 until arr.length()) {
                    try { list.add(DmMessage.fromJson(arr.getJSONObject(i))) } catch (e: Exception) { }
                }
                val grew = list.size > lastCount
                messages = list
                lastCount = list.size
                adapter.notifyDataSetChanged()
                if (grew && list.isNotEmpty()) {
                    lvMessages.setSelection(list.size - 1)
                }
            }
        }
    }

    /** Mark their messages to me as read (so the unread badge clears).
     * This stays even though Seen receipts are hidden: unread counts
     * (DM badge + notifications) depend on it. */
    private fun markRead() {
        Api.call("POST", "/api/dm/$userId/read") { _, _, _ -> }
    }

    private fun sendMessage() {
        if (sendBusy) return
        val text = etMessage.text.toString().trim()
        if (text.isEmpty()) return
        sendBusy = true
        etMessage.setText("")
        val body = JSONObject().put("body", text)
        Api.call("POST", "/api/dm/$userId", body) { ok, _, err ->
            runOnUiThread {
                sendBusy = false
                if (ok) {
                    loadThread()
                } else {
                    // A 403 here means the other side blocked (or was
                    // blocked): show the Blocked notice right away.
                    if (err?.contains("can't message") == true) applyBlockedState(true)
                    Ui.toast(this, err ?: "Message could not be sent")
                }
            }
        }
    }

    private fun showAttachChooser() {
        AlertDialog.Builder(this)
            .setTitle("Send photo or video")
            .setItems(arrayOf("📷 Photo", "🎬 Video")) { _, which ->
                pickMedia(if (which == 0) "image/*" else "video/*",
                    if (which == 0) 4001 else 4002)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickMedia(type: String, requestCode: Int) {
        try {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply { this.type = type }
            startActivityForResult(Intent.createChooser(intent, "Choose"), requestCode)
        } catch (e: Exception) {
            Ui.toast(this, "Could not open the picker")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if ((requestCode == 4001 || requestCode == 4002) &&
            resultCode == RESULT_OK && data?.data != null) {
            val uri: Uri = data.data!!
            val isVideo = requestCode == 4002
            val maxBytes = if (isVideo) 50L * 1024 * 1024 else 10L * 1024 * 1024
            val bytes = readCapped(uri, maxBytes)
            if (bytes == null) {
                Ui.toast(this, if (isVideo) "Video is too big (max 50 MB)"
                    else "Photo is too big (max 10 MB)")
                return
            }
            val mime = contentResolver.getType(uri)
                ?: if (isVideo) "video/mp4" else "image/jpeg"
            val kind = if (isVideo) "video" else "image"
            val name = if (isVideo) "dm_video.mp4" else "dm_photo.jpg"
            uploadBusy = true
            Ui.toast(this, "Uploading…")
            Api.uploadMedia(bytes, name, mime, kind) { ok, url, err ->
                runOnUiThread {
                    uploadBusy = false
                    if (ok && !url.isNullOrEmpty()) {
                        sendMediaMessage(url, kind)
                    } else {
                        Ui.toast(this, err ?: "Upload failed")
                    }
                }
            }
        }
    }

    /** Read a picked file, capped at maxBytes (null if bigger/unreadable). */
    private fun readCapped(uri: Uri, maxBytes: Long): ByteArray? {
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) return null
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun sendMediaMessage(mediaUrl: String, mediaKind: String) {
        val caption = etMessage.text.toString().trim()
        etMessage.setText("")
        val body = JSONObject().apply {
            put("body", caption)
            put("media_url", mediaUrl)
            put("media_kind", mediaKind)
        }
        Api.call("POST", "/api/dm/$userId", body) { ok, _, err ->
            runOnUiThread {
                if (ok) {
                    loadThread()
                } else {
                    if (err?.contains("can't message") == true) applyBlockedState(true)
                    Ui.toast(this, err ?: "Message could not be sent")
                }
            }
        }
    }
}
