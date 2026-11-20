package com.picstory.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.VideoView
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Create a photo post or a video post (Reel): pick the media, add a caption, share. */
class CreatePostActivity : Activity() {
    private var pickedMedia: ByteArray? = null
    private var pickedKind: String = "image" // "image" or "video"
    private var posting = false

    private lateinit var etCaption: EditText
    private lateinit var ivPostPreview: ImageView
    private lateinit var vvPostPreview: VideoView
    private lateinit var btnPost: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        ThemeHelper.apply(this)
        setContentView(R.layout.activity_create_post)

        etCaption = findViewById(R.id.etCaption)
        ivPostPreview = findViewById(R.id.ivPostPreview)
        vvPostPreview = findViewById(R.id.vvPostPreview)
        btnPost = findViewById(R.id.btnPost)

        findViewById<Button>(R.id.btnPickPhoto).setOnClickListener { pickMedia("image/*", 3001) }
        findViewById<Button>(R.id.btnPickVideo).setOnClickListener { pickMedia("video/*", 3002) }
        btnPost.setOnClickListener { post() }
        findViewById<Button>(R.id.btnCancel).setOnClickListener { finish() }
        vvPostPreview.setOnClickListener { togglePreview() }
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
        if ((requestCode == 3001 || requestCode == 3002) &&
            resultCode == RESULT_OK && data?.data != null) {
            val uri: Uri = data.data!!
            val isVideo = requestCode == 3002
            val maxBytes = if (isVideo) 50L * 1024 * 1024 else 10L * 1024 * 1024
            val bytes = readCapped(uri, maxBytes)
            if (bytes == null || bytes.isEmpty()) {
                Ui.toast(this, if (isVideo) "Video is too big (max 50 MB)"
                    else "Photo is too big (max 10 MB)")
                return
            }
            pickedMedia = bytes
            pickedKind = if (isVideo) "video" else "image"
            if (isVideo) {
                ivPostPreview.visibility = View.GONE
                try { vvPostPreview.stopPlayback() } catch (e: Exception) { }
                vvPostPreview.visibility = View.VISIBLE
                try { vvPostPreview.setVideoURI(uri) } catch (e: Exception) { }
            } else {
                try { vvPostPreview.stopPlayback() } catch (e: Exception) { }
                vvPostPreview.visibility = View.GONE
                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ivPostPreview.visibility = View.VISIBLE
                ivPostPreview.setImageBitmap(bmp)
            }
        }
    }

    /** Tap the video preview to play/pause it. */
    private fun togglePreview() {
        try {
            if (vvPostPreview.isPlaying) vvPostPreview.pause() else vvPostPreview.start()
        } catch (e: Exception) {
            Ui.toast(this, "Video could not play")
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

    override fun onPause() {
        try { vvPostPreview.stopPlayback() } catch (e: Exception) { }
        super.onPause()
    }

    private fun post() {
        if (posting) return
        val caption = etCaption.text.toString().trim()
        val media = pickedMedia
        if (media == null) {
            Ui.toast(this, "Choose a photo or a video first")
            return
        }
        posting = true
        btnPost.isEnabled = false
        btnPost.text = "Uploading…"
        val isVideo = pickedKind == "video"
        val name = if (isVideo) "post.mp4" else "post.jpg"
        val mime = if (isVideo) "video/mp4" else "image/jpeg"
        Api.uploadMedia(media, name, mime, pickedKind) { ok, url, err ->
            runOnUiThread {
                if (ok && !url.isNullOrEmpty()) {
                    sendPost(caption, url, isVideo)
                } else {
                    posting = false
                    btnPost.isEnabled = true
                    btnPost.text = getString(R.string.share)
                    Ui.toast(this, err ?: "Upload failed")
                }
            }
        }
    }

    private fun sendPost(caption: String, mediaUrl: String, isVideo: Boolean) {
        val body = JSONObject().apply {
            put("caption", caption)
            if (isVideo) put("video_url", mediaUrl) else put("image_url", mediaUrl)
        }
        Api.call("POST", "/api/posts", body) { ok, _, err ->
            runOnUiThread {
                posting = false
                btnPost.isEnabled = true
                btnPost.text = getString(R.string.share)
                if (ok) {
                    Ui.toast(this, "Posted!")
                    setResult(RESULT_OK)
                    finish()
                } else {
                    Ui.toast(this, err ?: "Post failed")
                }
            }
        }
    }
}
