package com.picstory.app

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView

/** 3-column square grid of post thumbnails (Explore + profile). */
class PostGridAdapter(
    private val activity: Activity,
    private val posts: List<Post>
) : BaseAdapter() {
    override fun getCount() = posts.size
    override fun getItem(p: Int) = posts[p]
    override fun getItemId(p: Int) = posts[p].id.toLong()

    override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
        val v = cv ?: LayoutInflater.from(activity)
            .inflate(R.layout.item_grid_post, parent, false)
        val iv = v.findViewById<ImageView>(R.id.ivThumb)
        // Square cells: height = width (GridView column width).
        val cell = activity.resources.displayMetrics.widthPixels / 3
        iv.layoutParams.height = cell
        val post = posts[pos]
        if (post.imageUrl.isNotEmpty()) {
            ImageLoader.load(post.imageUrl, iv, 400)
        } else {
            iv.setImageBitmap(null)
        }
        return v
    }
}
