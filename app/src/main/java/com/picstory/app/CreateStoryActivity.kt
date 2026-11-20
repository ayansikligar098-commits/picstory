package com.picstory.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import org.json.JSONObject

/** Create a photo story (24h expiry, enforced by the server). */
class CreateStoryActivity : Activity() {
    private var pickedImage: ByteArray? = null
    private var posting = false

    private lateinit var ivStoryPreview: ImageView
    private lateinit var btnPostStory: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        ThemeHelper.apply(this)
        setContentView(R.layout.activity_create_story)

        ivStoryPreview = findViewById(R.id.ivStoryPreview)
        btnPostStory = findViewById(R.id.btnPostStory)

        findViewById<Button>(R.id.btnPickPhoto).setOnClickListener { pickImage() }
        btnPostStory.setOnClickListener { postStory() }
        findViewById<Button>(R.id.btnCancel).setOnClickListener { finish() }
    }

    private fun pickImage() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
        startActivityForResult(Intent.createChooser(intent, "Choose a photo"), 2001)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2001 && resultCode == RESULT_OK && data?.data != null) {
            val uri: Uri = data.data!!
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes == null || bytes.isEmpty()) {
                    Ui.toast(this, "Could not read that photo")
                    return
                }
                pickedImage = bytes
                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ivStoryPreview.visibility = View.VISIBLE
                ivStoryPreview.setImageBitmap(bmp)
            } catch (e: Exception) {
                Ui.toast(this, "Could not read that photo")
            }
        }
    }

    private fun postStory() {
        if (posting) return
        if (pickedImage == null) {
            Ui.toast(this, "Choose a photo first")
            return
        }
        posting = true
        btnPostStory.isEnabled = false
        btnPostStory.text = "Uploading…"
        Api.uploadImage(pickedImage!!, "story.jpg") { ok, url, err ->
            runOnUiThread {
                if (ok && !url.isNullOrEmpty()) {
                    sendStory(url)
                } else {
                    posting = false
                    btnPostStory.isEnabled = true
                    btnPostStory.text = getString(R.string.share_story)
                    Ui.toast(this, err ?: "Upload failed")
                }
            }
        }
    }

    private fun sendStory(imageUrl: String) {
        val body = JSONObject().apply {
            put("image_url", imageUrl)
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
                        json.optString("created_at")
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
