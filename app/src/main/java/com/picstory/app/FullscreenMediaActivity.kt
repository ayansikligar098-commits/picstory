package com.picstory.app

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.MediaController
import android.widget.TextView
import android.widget.VideoView

/**
 * Full-screen media viewer: a photo (tap the image to close) or a video
 * (tap to play/pause, with playback controls). Never crashes on a bad
 * URL — a failed load just shows a toast and closes.
 */
class FullscreenMediaActivity : Activity() {
    private var vvVideo: VideoView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        ThemeHelper.apply(this)
        setContentView(R.layout.activity_fullscreen_media)

        val url = intent.getStringExtra("media_url") ?: ""
        val kind = intent.getStringExtra("media_kind") ?: "image"
        if (url.isEmpty()) {
            finish()
            return
        }
        val abs = Api.absUrl(url)

        findViewById<Button>(R.id.btnCloseMedia).setOnClickListener { finish() }

        if (kind == "video") {
            val vv = findViewById<VideoView>(R.id.vvFullVideo)
            vvVideo = vv
            val hint = findViewById<TextView>(R.id.tvMediaHint)
            vv.visibility = View.VISIBLE
            hint.visibility = View.VISIBLE
            try {
                val mc = MediaController(this)
                mc.setAnchorView(vv)
                vv.setMediaController(mc)
                vv.setVideoPath(abs)
                vv.setOnPreparedListener { mp ->
                    hint.visibility = View.GONE
                    vv.start()
                }
                vv.setOnErrorListener { _, _, _ ->
                    Ui.toast(this, "Video could not be played")
                    finish()
                    true
                }
                vv.setOnClickListener {
                    if (vv.isPlaying) vv.pause() else vv.start()
                }
            } catch (e: Exception) {
                Ui.toast(this, "Video could not be played")
                finish()
            }
        } else {
            val iv = findViewById<ImageView>(R.id.ivFullImage)
            iv.visibility = View.VISIBLE
            ImageLoader.load(url, iv, 1600)
            iv.setOnClickListener { finish() }
        }
    }

    override fun onPause() {
        try {
            vvVideo?.let { if (it.isPlaying) it.pause() }
        } catch (e: Exception) {
        }
        super.onPause()
    }

    override fun onDestroy() {
        try {
            vvVideo?.stopPlayback()
        } catch (e: Exception) {
        }
        super.onDestroy()
    }
}
