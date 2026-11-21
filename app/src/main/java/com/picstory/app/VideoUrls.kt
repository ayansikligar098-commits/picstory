package com.picstory.app

/**
 * Faststart playback URLs for remote videos (v3.4).
 *
 * Phone-recorded MP4s are stored with their `moov` index atom at the END
 * of the file, so Android's VideoView/MediaPlayer must fetch the whole
 * file tail before playback can start — on mobile data it stalls on a
 * black screen. Cloudinary can serve a derived copy that is re-encoded
 * (720p, auto quality, mp4) with the index up front; that file starts
 * after buffering the first bytes. This rewrites a Cloudinary video
 * delivery URL to that derived form; every other URL passes through
 * unchanged, and an already-transformed URL is never transformed twice.
 */
private const val FASTSTART_TRANSFORM = "w_720,q_auto,f_mp4"

fun playableVideoUrl(url: String): String {
    val marker = "/video/upload/"
    val idx = url.indexOf(marker)
    if (idx < 0) return url
    if (!url.contains("res.cloudinary.com")) return url
    val after = url.substring(idx + marker.length)
    // The first path segment after /video/upload/ is either a version
    // (v<digits>) or a transformation ("w_720,q_auto,f_mp4" — commas).
    // A transformation segment means this URL is already derived.
    val firstSeg = after.substringBefore("/")
    if (firstSeg.contains(",")) return url
    return url.substring(0, idx + marker.length) + FASTSTART_TRANSFORM + "/" + after
}

/**
 * Poster frame for a Cloudinary video (v3.6): a small jpg of the
 * frame at 1s, served from the same delivery URL shape. The story
 * viewer shows this still the instant a video story opens, while the
 * player is still buffering — so the user sees the story at once
 * instead of a black screen. Returns null for non-Cloudinary URLs
 * (the viewer then falls back to the loading spinner on black).
 */
private const val POSTER_TRANSFORM = "w_720,q_auto,f_jpg,so_1"

fun posterImageUrl(url: String): String? {
    val marker = "/video/upload/"
    val idx = url.indexOf(marker)
    if (idx < 0) return null
    if (!url.contains("res.cloudinary.com")) return null
    val after = url.substring(idx + marker.length)
    val segments = after.split("/").toMutableList()
    // Drop an existing transformation segment (it has commas, or is a
    // single transform token like f_mp4). A version (v<digits>) or the
    // folder (picstory) is kept.
    if (segments.isNotEmpty() && isTransformSegment(segments[0])) segments.removeAt(0)
    val last = segments.lastOrNull() ?: return null
    val dot = last.lastIndexOf('.')
    if (dot < 0) return null
    segments[segments.size - 1] = last.substring(0, dot) + ".jpg"
    return url.substring(0, idx + marker.length) + POSTER_TRANSFORM + "/" + segments.joinToString("/")
}

private fun isTransformSegment(seg: String): Boolean {
    if (seg.contains(",")) return true
    return seg.matches(Regex("^(f|w|h|q|c|so|e|ar|dpr|fl)_[A-Za-z0-9.]+$"))
}
