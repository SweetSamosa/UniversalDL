package com.sweetsamosa.universaldl.app

import java.net.HttpURLConnection
import java.net.URL

object LinkDetector {
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "tiff", "svg")
    private val audioExtensions = setOf("mp3", "m4a", "opus", "flac", "wav", "aac", "ogg", "m4b")
    private val videoExtensions = setOf("mp4", "mkv", "webm", "mov", "avi", "m3u8", "mpd", "flv")

    private val imageDomains = listOf(
        "pinterest.com", "pin.it", "imgur.com", "artstation.com", "unsplash.com",
        "flickr.com", "deviantart.com", "500px.com", "pixiv.net", "twimg.com",
        "i.redd.it", "instagram.com/p/", "instagr.am/p/",
        "facebook.com/photo", "facebook.com/photos"
    )

    private val audioDomains = listOf(
        "gaana.com", "jiosaavn.com", "saavn.com", "wynk.in", "hungama.com",
        "soundcloud.com", "bandcamp.com", "mixcloud.com", "audiomack.com", "spotify.com"
    )

    private val videoDomains = listOf(
        "youtube.com", "youtu.be", "tiktok.com", "v.redd.it", "facebook.com/watch",
        "facebook.com/reel", "facebook.com/videos", "facebook.com",
        "fb.watch", "vimeo.com", "twitch.tv", "dailymotion.com", "x.com/i/status",
        "twitter.com/i/status", "instagram.com/reel/", "instagram.com/reels/",
        "instagram.com/tv/", "instagr.am/reel/"
    )

    fun normalizeUrl(rawUrl: String): String {
        var clean = rawUrl.trim()
        if (clean.isEmpty()) return clean

        clean = clean.trimEnd('.', ',', ';', '!', ')', ']')

        if (clean.contains("instagram.com", ignoreCase = true) || clean.contains("instagr.am", ignoreCase = true)) {
            clean = clean.replace(Regex("""https?://(?:www\.)?instagr\.am/""", RegexOption.IGNORE_CASE), "https://www.instagram.com/")
            clean = clean.replace(Regex("""instagram\.com/share/p/""", RegexOption.IGNORE_CASE), "instagram.com/p/")
            clean = clean.replace(Regex("""instagram\.com/share/reel/""", RegexOption.IGNORE_CASE), "instagram.com/reel/")
            clean = clean.replace(Regex("""instagram\.com/share/r/""", RegexOption.IGNORE_CASE), "instagram.com/reel/")
            clean = clean.replace(Regex("""instagram\.com/reels/""", RegexOption.IGNORE_CASE), "instagram.com/reel/")
        }

        if (clean.contains("facebook.com", ignoreCase = true) || clean.contains("fb.watch", ignoreCase = true) || clean.contains("fb.me", ignoreCase = true)) {
            clean = clean.replace(Regex("""https?://(?:m|mbasic)\.facebook\.com/""", RegexOption.IGNORE_CASE), "https://www.facebook.com/")

            val sharePMatch = Regex("""facebook\.com/share/p/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE).find(clean)
            if (sharePMatch != null) {
                val id = sharePMatch.groupValues[1]
                clean = "https://www.facebook.com/permalink.php?story_fbid=$id"
            } else {
                val shareVMatch = Regex("""facebook\.com/share/v/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE).find(clean)
                if (shareVMatch != null) {
                    clean = "https://www.facebook.com/watch/?v=${shareVMatch.groupValues[1]}"
                } else {
                    val shareRMatch = Regex("""facebook\.com/share/r/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE).find(clean)
                    if (shareRMatch != null) {
                        clean = "https://www.facebook.com/reel/${shareRMatch.groupValues[1]}"
                    }
                }
            }
        }
        return clean
    }

    fun resolveRedirects(url: String): String {
        val norm = normalizeUrl(url)
        if (!norm.contains("fb.watch", ignoreCase = true) &&
            !norm.contains("fb.me", ignoreCase = true) &&
            !norm.contains("facebook.com/share/", ignoreCase = true)
        ) {
            return norm
        }

        val mobileUA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        val mobileUrl = norm.replace("www.facebook.com", "m.facebook.com")

        return runCatching {
            val conn = (URL(mobileUrl).openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", mobileUA)
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                instanceFollowRedirects = true
                connectTimeout = 8000
                readTimeout = 8000
            }
            conn.connect()
            val redirectUrl = conn.url.toString()
            val html = try { conn.inputStream.bufferedReader().use { it.readText() } } catch (_: Exception) { "" }
            conn.disconnect()

            val ogMatch = Regex("""<meta[^>]+property="og:url"[^>]+content="([^"]+)"""", RegexOption.IGNORE_CASE).find(html)
                ?: Regex("""<meta[^>]+content="([^"]+)"[^>]+property="og:url"""", RegexOption.IGNORE_CASE).find(html)
            if (ogMatch != null) {
                val resolvedOg = unescapeHtml(ogMatch.groupValues[1])
                if (resolvedOg.startsWith("http") && !resolvedOg.contains("/login")) return normalizeUrl(resolvedOg)
            }

            val canonicalMatch = Regex("""<link[^>]+rel="canonical"[^>]+href="([^"]+)"""", RegexOption.IGNORE_CASE).find(html)
            if (canonicalMatch != null) {
                val resolvedCanonical = unescapeHtml(canonicalMatch.groupValues[1])
                if (resolvedCanonical.startsWith("http") && !resolvedCanonical.contains("/login")) return normalizeUrl(resolvedCanonical)
            }

            if (redirectUrl.startsWith("http") && !redirectUrl.contains("/login")) {
                return normalizeUrl(redirectUrl)
            }

            norm
        }.getOrDefault(norm)
    }

    private fun unescapeHtml(str: String): String {
        return str.replace("&amp;", "&")
            .replace("\\/", "/")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }

    fun isInstagramUrl(url: String): Boolean {
        val clean = normalizeUrl(url).lowercase()
        return clean.contains("instagram.com") || clean.contains("instagr.am")
    }

    fun isFacebookUrl(url: String): Boolean {
        val clean = normalizeUrl(url).lowercase()
        return clean.contains("facebook.com") || clean.contains("fb.watch") || clean.contains("fb.me")
    }

    fun isGaanaUrl(url: String): Boolean {
        val clean = normalizeUrl(url).lowercase()
        return clean.contains("gaana.com") || clean.contains("gaana.page.link")
    }

    fun isImagePostUrl(url: String): Boolean {
        val clean = normalizeUrl(url).lowercase()
        return clean.contains("instagram.com/p/") ||
               clean.contains("facebook.com/photo") ||
               clean.contains("facebook.com/photos") ||
               clean.contains("pinterest.com") || clean.contains("pin.it") ||
               clean.contains("imgur.com") || clean.contains("artstation.com") ||
               clean.contains("unsplash.com") || clean.contains("flickr.com") ||
               clean.contains("deviantart.com") || clean.contains("500px.com") ||
               clean.contains("pixiv.net") || clean.contains("twimg.com") ||
               clean.contains("i.redd.it")
    }

    fun detect(url: String): Kind {
        if (url.isBlank()) return Kind.VIDEO
        val clean = normalizeUrl(url).lowercase()
        val path = clean.substringBefore('?').substringBefore('#')
        val ext = path.substringAfterLast('.', "")

        if (ext in imageExtensions) return Kind.IMAGE
        if (ext in audioExtensions) return Kind.AUDIO
        if (ext in videoExtensions) return Kind.VIDEO

        if (videoDomains.any { clean.contains(it) }) return Kind.VIDEO
        if (audioDomains.any { clean.contains(it) }) return Kind.AUDIO
        if (imageDomains.any { clean.contains(it) }) return Kind.IMAGE

        return Kind.VIDEO
    }
}
