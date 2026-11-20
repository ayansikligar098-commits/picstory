package com.picstory.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import org.json.JSONObject

/** 1:1 DM chat with one user. Polls the thread every 5s while open. */
class DmChatActivity : Activity() {
    private var userId: Int = 0
    private var displayName: String = ""
    private var username: String = ""
    private var avatarUrl: String = ""
    private var messages: List<DmMessage> = emptyList()
    private var sendBusy = false
    private var lastCount = -1
    private lateinit var lvMessages: ListView
    private lateinit var etMessage: EditText
    private val pollHandler = Handler(Looper.getMainLooper())
    private var pollRunnable: Runnable? = null

    private val adapter = object : BaseAdapter() {
        override fun getCount() = messages.size
        override fun getItem(p: Int) = messages[p]
        override fun getItemId(p: Int) = messages[p].id.toLong()
        override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
            val v = cv ?: LayoutInflater.from(this@DmChatActivity)
                .inflate(R.layout.item_dm_message, parent, false)
            val m = messages[pos]
            val row = v.findViewById<LinearLayout>(R.id.msgRow)
            val tvBody = v.findViewById<TextView>(R.id.tvBody)
            tvBody.text = m.body
            if (m.senderId == Prefs.myUserId) {
                row.gravity = Gravity.END
                tvBody.setBackgroundResource(R.drawable.btn_primary)
                tvBody.setTextColor(resources.getColor(R.color.white, theme))
            } else {
                row.gravity = Gravity.START
                tvBody.setBackgroundResource(R.drawable.edit_bg)
                tvBody.setTextColor(resources.getColor(R.color.textPrimary, theme))
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
    }

    override fun onResume() {
        super.onResume()
        markRead()
        loadThread()
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
                pollHandler.postDelayed(this, 5_000)
            }
        }
        pollRunnable = r
        pollHandler.postDelayed(r, 5_000)
    }

    private fun stopPolling() {
        pollRunnable?.let { pollHandler.removeCallbacks(it) }
        pollRunnable = null
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

    /** Mark their messages to me as read (so the red dot clears). */
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
                    Ui.toast(this, err ?: "Message could not be sent")
                }
            }
        }
    }
}
