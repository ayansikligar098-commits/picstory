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
