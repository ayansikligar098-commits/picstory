package com.picstory.app

import android.app.Activity
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import org.json.JSONObject

/**
 * Create a photo or video story (24h expiry, enforced by the server).
 * Video stories are capped at 59 seconds — checked here at pick time
 * with MediaMetadataRetriever, BEFORE anything is uploaded, so a long
 * video is refused with a clear message instead of a failed post.
 */
class CreateStoryActivity : Activity() {
    private var pickedImage: ByteArray? = null
    private var pickedVideo: ByteArray? = null
    private var pickedVideoMime: String = "video/mp4"
    private var posting = false

    private lateinit var ivStoryPreview: ImageView
    private lateinit var tvStoryInfo: TextView
    private lateinit var btnPostStory: Button

    companion object {
        const val MAX_STORY_VIDEO_MS = 59_000L
        const val MAX_VIDEO_BYTES = 50L * 1024 * 1024
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        ThemeHelper.apply(this)
        setContentView(R.layout.activity_create_story)

        ivStoryPreview = findViewById(R.id.ivStoryPreview)
        tvStoryInfo = findViewById(R.id.tvStoryInfo)
        btnPostStory = findViewById(R.id.btnPostStory)

        findViewById<Button>(R.id.btnPickPhoto).setOnClickListener { pickImage() }
        findViewById<Button>(R.id.btnPickVideo).setOnClickListener { pickVideo() }
        btnPostStory.setOnClickListener { postStory() }
        findViewById<Button>(R.id.btnCancel).setOnClickListener { finish() }
    }

    private fun pickImage() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
        startActivityForResult(Intent.createChooser(intent, "Choose a photo"), 2001)
    }

    private fun pickVideo() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "video/*" }
        startActivityForResult(Intent.createChooser(intent, "Choose a video"), 2002)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri: Uri = data.data!!
        if (requestCode == 2001) onPhotoPicked(uri)
        else if (requestCode == 2002) onVideoPicked(uri)
    }

    private fun onPhotoPicked(uri: Uri) {
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null || bytes.isEmpty()) {
                Ui.toast(this, "Could not read that photo")
                return
            }
            pickedImage = bytes
            pickedVideo = null
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ivStoryPreview.visibility = View.VISIBLE
            ivStoryPreview.setImageBitmap(bmp)
            tvStoryInfo.visibility = View.GONE
        } catch (e: Exception) {
            Ui.toast(this, "Could not read that photo")
        }
    }

    /** Video pick: duration is checked FIRST — over 59s is refused
     * outright, with no upload and no story created. */
    private fun onVideoPicked(uri: Uri) {
        val durationMs = videoDurationMs(uri)
        if (durationMs != null && durationMs > MAX_STORY_VIDEO_MS) {
            Ui.toast(this, getString(R.string.story_too_long))
            return
        }
        try {
            val bytes = readCapped(uri, MAX_VIDEO_BYTES)
            if (bytes == null) {
                Ui.toast(this, "Video is too big (max 50 MB)")
                return
            }
            pickedVideo = bytes
            pickedImage = null
            pickedVideoMime = contentResolver.getType(uri) ?: "video/mp4"
            // Thumbnail frame for the preview (first frame).
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(this, uri)
                val frame = retriever.frameAtTime
                if (frame != null) {
                    ivStoryPreview.visibility = View.VISIBLE
                    ivStoryPreview.setImageBitmap(frame)
                } else {
                    ivStoryPreview.visibility = View.GONE
                }
            } catch (e: Exception) {
                ivStoryPreview.visibility = View.GONE
            } finally {
                try { retriever.release() } catch (e: Exception) { }
            }
            val secs = if (durationMs != null) (durationMs / 1000).toInt() else null
            tvStoryInfo.visibility = View.VISIBLE
            tvStoryInfo.text = if (secs != null) "🎬 Video • ${secs}s (max 59s)"
                else "🎬 Video (max 59s)"
        } catch (e: Exception) {
            Ui.toast(this, "Could not read that video")
        }
    }

    /** Video length in ms, or null when it cannot be read (unknown
     * duration is allowed through — blocking it would break legitimate
     * videos from galleries that hide metadata). */
    private fun videoDurationMs(uri: Uri): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (e: Exception) {
            null
        } finally {
            try { retriever.release() } catch (e: Exception) { }
        }
    }

    /** Read a picked file, capped at maxBytes (null if bigger/unreadable). */
    private fun readCapped(uri: Uri, maxBytes: Long): ByteArray? {
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
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

    private fun postStory() {
        if (posting) return
        if (pickedImage == null && pickedVideo == null) {
            Ui.toast(this, "Choose a photo or video first")
            return
        }
        posting = true
        btnPostStory.isEnabled = false
        btnPostStory.text = "Uploading…"
        if (pickedVideo != null) {
            Api.uploadMedia(pickedVideo!!, "story.mp4", pickedVideoMime, "video") { ok, url, err ->
                runOnUiThread {
                    if (ok && !url.isNullOrEmpty()) sendStory("", url)
                    else uploadFailed(err)
                }
            }
        } else {
            Api.uploadImage(pickedImage!!, "story.jpg") { ok, url, err ->
                runOnUiThread {
                    if (ok && !url.isNullOrEmpty()) sendStory(url, "")
                    else uploadFailed(err)
                }
            }
        }
    }

    private fun uploadFailed(err: String?) {
        posting = false
        btnPostStory.isEnabled = true
        btnPostStory.text = getString(R.string.share_story)
        Ui.toast(this, err ?: "Upload failed")
    }

    private fun sendStory(imageUrl: String, videoUrl: String) {
        val body = JSONObject().apply {
            put("image_url", imageUrl)
            put("video_url", videoUrl)
        }
        Api.call("POST", "/api/stories", body) { ok, json, err ->
            runOnUiThread {
                posting = false
                btnPostStory.isEnabled = true
                btnPostStory.text = getString(R.string.share_story)
                if (ok && json != null) {
                    // Record locally so highlights can use this story
                    // even after the 24h expiry (no list-expired API).
                    Prefs.recordMyStory(
                        json.optInt("id"),
                        json.optString("image_url"),
                        json.optString("created_at"),
                        json.optString("video_url")
                    )
                    Ui.toast(this, "Story posted!")
                    setResult(RESULT_OK)
                    finish()
                } else {
                    Ui.toast(this, err ?: "Story failed")
                }
            }
        }
    }
}
