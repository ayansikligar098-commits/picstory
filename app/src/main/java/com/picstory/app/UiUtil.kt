package com.picstory.app

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Small UI helpers: toasts, avatar initials/colors, relative time. */
object Ui {
    fun toast(ctx: Context, msg: String) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }

    private val AVATAR_COLORS = intArrayOf(
        0xFF5B21B6.toInt(), 0xFFD97706.toInt(), 0xFFBE185D.toInt(),
        0xFF4338CA.toInt(), 0xFF15803D.toInt(), 0xFFC2410C.toInt(),
        0xFF0E7490.toInt(), 0xFF7E22CE.toInt(), 0xFFB91C1C.toInt(),
        0xFF1D4ED8.toInt()
    )

    fun initials(name: String): String {
        val parts = name.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return "?"
        val first = parts[0].first().uppercaseChar()
        val second = if (parts.size > 1) parts[1].first().uppercaseChar() else null
        return if (second != null) "$first$second" else "$first"
    }

    fun avatarColor(key: String): Int {
        val h = key.hashCode().let { if (it < 0) -it else it }
        return AVATAR_COLORS[h % AVATAR_COLORS.size]
    }

    /** Paint a TextView as a circular avatar with initials. */
    fun setAvatar(tv: TextView, user: User) {
        tv.text = initials(user.displayName.ifEmpty { user.username })
        val d = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(avatarColor(user.username))
        }
        tv.background = d
    }

    fun setAvatarText(tv: TextView, name: String, key: String) {
        tv.text = initials(name)
        val d = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(avatarColor(key))
        }
        tv.background = d
    }

    /**
     * Show a user's profile photo as a circular avatar; fall back to the
     * initials circle whenever no photo is set. Call this everywhere an
     * avatar appears so photos and initials never disagree.
     */
    fun setAvatarPhoto(iv: ImageView, tv: TextView, user: User) {
        if (user.avatarUrl.isNotEmpty()) {
            tv.visibility = View.GONE
            iv.visibility = View.VISIBLE
            ImageLoader.loadCircular(user.avatarUrl, iv, 200)
        } else {
            iv.visibility = View.GONE
            tv.visibility = View.VISIBLE
            setAvatar(tv, user)
        }
    }

    /** Same as setAvatarPhoto, for places that only have a name/key (own profile, nav). */
    fun setAvatarPhoto(iv: ImageView, tv: TextView, avatarUrl: String, name: String, key: String) {
        if (avatarUrl.isNotEmpty()) {
            tv.visibility = View.GONE
            iv.visibility = View.VISIBLE
            ImageLoader.loadCircular(avatarUrl, iv, 200)
        } else {
            iv.visibility = View.GONE
            tv.visibility = View.VISIBLE
            setAvatarText(tv, name, key)
        }
    }

    private val isoFormats = listOf(
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.US),
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US),
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    ).onEach { it.timeZone = TimeZone.getTimeZone("UTC") }

    fun parseIso(iso: String?): Date? {
        if (iso.isNullOrEmpty()) return null
        for (f in isoFormats) {
            try {
                return f.parse(iso)
            } catch (e: Exception) {
                // try next format
            }
        }
        return null
    }

    /** "just now", "5 min ago", "2 hours ago", "Yesterday", "12 Nov". */
    fun timeAgo(iso: String?): String {
        val d = parseIso(iso) ?: return ""
        val diff = System.currentTimeMillis() - d.time
        if (diff < 60_000) return "just now"
        val mins = diff / 60_000
        if (mins < 60) return "$mins min ago"
        val hours = mins / 60
        if (hours < 24) return "$hours hours ago"
        val cal = Calendar.getInstance()
        val today = cal.get(Calendar.DAY_OF_YEAR)
        cal.time = d
        return if (cal.get(Calendar.DAY_OF_YEAR) == today - 1) "Yesterday"
        else SimpleDateFormat("d MMM", Locale.US).format(d)
    }

    /** "14:32" (device local time) for a message timestamp. */
    fun clockTime(iso: String?): String {
        val d = parseIso(iso) ?: return ""
        return SimpleDateFormat("HH:mm", Locale.US).format(d)
    }

    /** Parse a "#RRGGBB" color string safely. */
    fun parseColor(hex: String, fallback: Int): Int {
        return try {
            Color.parseColor(hex)
        } catch (e: Exception) {
            fallback
        }
    }
}
