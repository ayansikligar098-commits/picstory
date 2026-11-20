package com.picstory.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent

/**
 * Share a post: Android share sheet ("Share via…") or send it to someone
 * as a DM ("Send to someone"). Used from the feed and the post detail screen.
 */
object ShareHelper {
    fun showShareSheet(activity: Activity, post: Post) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.share_post)
            .setItems(arrayOf(activity.getString(R.string.share_via),
                activity.getString(R.string.send_to_dm))) { _, which ->
                if (which == 0) shareViaApp(activity, post) else sendToDm(activity, post)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun shareViaApp(activity: Activity, post: Post) {
        val media = post.videoUrl.ifEmpty { post.imageUrl }
        val text = buildString {
            append("70a • @${post.author?.username ?: "user"}")
            if (post.caption.isNotEmpty()) append(": ${post.caption}")
            if (media.isNotEmpty()) append("\n${Api.absUrl(media)}")
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        activity.startActivity(Intent.createChooser(intent, "Share post"))
    }

    private fun sendToDm(activity: Activity, post: Post) {
        activity.startActivity(Intent(activity, ShareToDmActivity::class.java)
            .putExtra("post_id", post.id)
            .putExtra("image_url", post.imageUrl)
            .putExtra("video_url", post.videoUrl)
            .putExtra("caption", post.caption)
            .putExtra("author_username", post.author?.username ?: ""))
    }
}
