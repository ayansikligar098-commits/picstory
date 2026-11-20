package com.picstory.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import org.json.JSONObject

/** Create a photo post: pick a photo, add a caption, share. */
class CreatePostActivity : Activity() {
    private var pickedImage: ByteArray? = null
    private var posting = false

    private lateinit var etCaption: EditText
    private lateinit var ivPostPreview: ImageView
    private lateinit var btnPost: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        ThemeHelper.apply(this)
        setContentView(R.layout.activity_create_post)

        etCaption = findViewById(R.id.etCaption)
        ivPostPreview = findViewById(R.id.ivPostPreview)
        btnPost = findViewById(R.id.btnPost)

        findViewById<Button>(R.id.btnPickPhoto).setOnClickListener { pickImage() }
        btnPost.setOnClickListener { post() }
        findViewById<Button>(R.id.btnCancel).setOnClickListener { finish() }
    }

    private fun pickImage() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
        startActivityForResult(Intent.createChooser(intent, "Choose a photo"), 3001)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 3001 && resultCode == RESULT_OK && data?.data != null) {
            val uri: Uri = data.data!!
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes == null || bytes.isEmpty()) {
                    Ui.toast(this, "Could not read that photo")
                    return
                }
                pickedImage = bytes
                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ivPostPreview.visibility = View.VISIBLE
                ivPostPreview.setImageBitmap(bmp)
            } catch (e: Exception) {
                Ui.toast(this, "Could not read that photo")
            }
        }
    }

    private fun post() {
        if (posting) return
        val caption = etCaption.text.toString().trim()
        if (pickedImage == null) {
            Ui.toast(this, "Choose a photo first")
            return
        }
        posting = true
        btnPost.isEnabled = false
        btnPost.text = "Uploading…"
        Api.uploadImage(pickedImage!!, "post.jpg") { ok, url, err ->
            runOnUiThread {
                if (ok && !url.isNullOrEmpty()) {
                    sendPost(caption, url)
                } else {
                    posting = false
                    btnPost.isEnabled = true
                    btnPost.text = getString(R.string.share)
                    Ui.toast(this, err ?: "Upload failed")
                }
            }
        }
    }

    private fun sendPost(caption: String, imageUrl: String) {
        val body = JSONObject().apply {
            put("caption", caption)
            put("image_url", imageUrl)
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
