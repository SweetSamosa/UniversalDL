package com.sweetsamosa.universaldl.app

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

enum class Kind { VIDEO, AUDIO, GIF, IMAGE }

data class Options(
    val kind: Kind = Kind.VIDEO,
    val videoFormat: String = "mp4",   // mp4 | mkv | webm
    val maxHeight: Int = 1080,         // 0 = best available
    val audioFormat: String = "mp3",   // mp3 | m4a | opus | flac | wav | aac | vorbis
    val clipStart: String? = null,     // e.g. "00:01:30"
    val clipEnd: String? = null        // e.g. "00:03:45"
)

data class MediaInfo(
    val title: String,
    val uploader: String,
    val thumbnail: String?,
    val seconds: Int,
    val availableQualities: List<Int> = emptyList()
)

object Downloader {
    @Volatile var cancelled = false
        private set

    private val imageExt = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif")
    private val skipExt = setOf("part", "ytdl", "tmp", "lrc", "vtt", "srt", "ass", "json")
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36"

    private fun directImageExt(url: String) =
        url.substringBefore('?').substringAfterLast('.', "").lowercase().takeIf { it in imageExt }



    private fun isBotCheckError(e: Throwable): Boolean {
        val msg = e.message.orEmpty()
        return msg.contains("Sign in to confirm", ignoreCase = true) ||
               msg.contains("confirm you're not a bot", ignoreCase = true) ||
               (msg.contains("bot", ignoreCase = true) && msg.contains("sign in", ignoreCase = true)) ||
               msg.contains("HTTP Error 429", ignoreCase = true)
    }

    fun sanitizePythonEnv() {
        runCatching {
            android.system.Os.unsetenv("PYTHONHOME")
            android.system.Os.unsetenv("PYTHONPATH")
            android.system.Os.unsetenv("PYTHONEXECUTABLE")
            android.system.Os.unsetenv("PYTHONWHELPERS")
        }
    }

    @Volatile private var cachedFFmpegLocation: File? = null

    fun getFFmpegLocation(ctx: Context): File? {
        cachedFFmpegLocation?.let { if (it.exists()) return it }
        val loc = runCatching {
            val f = FFmpeg.getInstance()
            f.javaClass.declaredFields.forEach {
                it.isAccessible = true
                val v = it.get(f)
                if (v is File && v.exists()) {
                    val bin = File(v, "ffmpeg")
                    if (bin.exists()) return bin
                    return v
                }
            }
            null
        }.getOrNull() ?: run {
            val candidates = listOf(
                File(ctx.noBackupFilesDir, "youtubedl-android/ffmpeg"),
                File(ctx.noBackupFilesDir, "youtubedl-android"),
                File(ctx.filesDir, "youtubedl-android/ffmpeg"),
                File(ctx.filesDir, "youtubedl-android"),
                File(ctx.cacheDir, "youtubedl-android")
            )
            candidates.firstOrNull { it.exists() }
        }
        if (loc != null) cachedFFmpegLocation = loc
        return loc
    }

    private fun buildYoutubeDLRequest(ctx: Context?, url: String, cookies: File?, playerClient: String? = null): YoutubeDLRequest {
        val desktopUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        val domain = runCatching { URL(url).host.removePrefix("www.") }.getOrDefault("")
        val referer = if (domain.isNotBlank()) "https://$domain/" else null

        return YoutubeDLRequest(url).apply {
            addOption("--user-agent", desktopUA)
            if (!referer.isNullOrBlank()) {
                addOption("--referer", referer)
            }
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("--geo-bypass")
            addOption("--no-check-certificates")
            addOption("--no-warnings")
            addOption("--concurrent-fragments", "4")
            ctx?.let { c ->
                getFFmpegLocation(c)?.let { loc ->
                    addOption("--ffmpeg-location", loc.absolutePath)
                }
            }
            if (!playerClient.isNullOrBlank() && playerClient != "default") {
                addOption("--extractor-args", "youtube:player_client=$playerClient")
            }
            cookies?.let { addOption("--cookies", it.absolutePath) }
        }
    }

    private fun extractInstagramCode(url: String): String? {
        val norm = LinkDetector.normalizeUrl(url)
        val match = Regex("""instagram\.com/(?:p|reel|reels|tv|share/p|share/reel)/([A-Za-z0-9_.-]+)""", RegexOption.IGNORE_CASE).find(norm)
        return match?.groupValues?.get(1)?.trimEnd('/')
    }

    fun info(url: String, cookies: File? = null): MediaInfo {
        sanitizePythonEnv()
        val normUrl = LinkDetector.resolveRedirects(url)
        if (directImageExt(normUrl) != null)
            return MediaInfo(normUrl.substringBefore('?').substringAfterLast('/'), "", normUrl, 0)

        if (LinkDetector.isInstagramUrl(normUrl)) {
            val code = extractInstagramCode(normUrl)
            val thumb = if (code != null) fetchInstagramApiThumbnail(code) ?: fetchInstagramEmbedThumbnail(normUrl) else fetchInstagramEmbedThumbnail(normUrl)
            return MediaInfo(
                title = "Instagram Post",
                uploader = "Instagram",
                thumbnail = thumb,
                seconds = 0
            )
        }

        if (LinkDetector.isGaanaUrl(normUrl) || LinkDetector.isFacebookUrl(normUrl) || LinkDetector.isImagePostUrl(normUrl)) {
            return fetchOpenGraphInfo(normUrl)
        }

        val req = buildYoutubeDLRequest(null, normUrl, cookies, null)
        val i = try {
            YoutubeDL.getInstance().getInfo(req)
        } catch (e: Exception) {
            if (isBotCheckError(e)) {
                val fallbackReq = buildYoutubeDLRequest(null, normUrl, cookies, "android,mweb,ios")
                YoutubeDL.getInstance().getInfo(fallbackReq)
            } else {
                return fetchOpenGraphInfo(normUrl)
            }
        }

        val qualities = i.formats.orEmpty()
            .map { f ->
                if (f.height > 0) f.height
                else {
                    val note = "${f.formatNote ?: ""} ${f.format ?: ""}"
                    val match = Regex("""(\d{3,4})p?""").find(note)
                    val parsed = match?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    if (parsed in 144..8640) parsed else 0
                }
            }
            .filter { it in 144..8640 }
            .distinct()
            .sortedDescending()

        return MediaInfo(
            title = i.title ?: normUrl,
            uploader = i.uploader ?: "",
            thumbnail = i.thumbnail,
            seconds = i.duration,
            availableQualities = qualities
        )
    }

    /**
     * Downloads into a temp dir, then copies results to Downloads/UniversalDL. Returns files saved.
     * VIDEO/AUDIO/GIF use yt-dlp (VIDEO falls back to gallery-dl); IMAGE uses gallery-dl
     * (falls back to yt-dlp's thumbnail); plain image links are fetched directly.
     */
    fun download(
        ctx: Context, url: String, o: Options, procId: String, cookies: File?,
        onProgress: (Float, String) -> Unit,
    ): Int {
        cancelled = false
        val normUrl = LinkDetector.resolveRedirects(url)
        val tmp = File(ctx.cacheDir, "dl/${System.nanoTime()}").apply { mkdirs() }
        try {
            val imgExt = directImageExt(normUrl)
            if (o.kind == Kind.IMAGE && imgExt != null) {
                fetchDirect(normUrl, imgExt, tmp)
            } else if (LinkDetector.isInstagramUrl(normUrl)) {
                onProgress(-1f, "Fetching Instagram media…")
                val code = extractInstagramCode(normUrl)
                var nInsta = 0
                if (code != null) {
                    nInsta = runCatching { fetchInstagramApi(code, tmp) }.getOrDefault(0)
                }
                if (nInsta <= 0) {
                    nInsta = runCatching { fetchInstagramEmbed(normUrl, tmp) }.getOrDefault(0)
                }
                if (nInsta > 0) {
                    return tmp.listFiles().orEmpty().count { it.isFile && it.extension.lowercase() !in skipExt && save(ctx, it) }
                }
                // Fallback to gallery-dl / yt-dlp if direct extraction failed (e.g. private post with cookies)
                var err: Exception? = null
                val n = try { gallery(ctx, normUrl, tmp, cookies, onProgress) } catch (e: Exception) { err = e; 0 }
                if (n <= 0) {
                    if (cancelled) throw IllegalStateException("Cancelled")
                    try {
                        ytdlp(ctx, normUrl, o, tmp, procId, cookies, onProgress)
                    } catch (e: Exception) {
                        if (cookies == null) {
                            throw IllegalStateException("This Instagram post could not be downloaded publicly. If it is a private post, please import cookies.txt in Settings.")
                        }
                        throw err ?: e
                    }
                }
            } else if (LinkDetector.isFacebookUrl(normUrl)) {
                onProgress(-1f, "Fetching Facebook media…")
                val nFbVid = runCatching { fetchFacebookVideo(normUrl, tmp) }.getOrDefault(0)
                if (nFbVid > 0) {
                    return tmp.listFiles().orEmpty().count { it.isFile && it.extension.lowercase() !in skipExt && save(ctx, it) }
                }
                val nFbImg = runCatching { fetchFacebookImage(normUrl, tmp) }.getOrDefault(0)
                if (nFbImg > 0) {
                    return tmp.listFiles().orEmpty().count { it.isFile && it.extension.lowercase() !in skipExt && save(ctx, it) }
                }
                try { ytdlp(ctx, normUrl, o, tmp, procId, cookies, onProgress) } catch (e: Exception) {
                    val fallbackVid = runCatching { fetchFacebookVideo(normUrl, tmp) }.getOrDefault(0)
                    if (fallbackVid > 0) {
                        return tmp.listFiles().orEmpty().count { it.isFile && it.extension.lowercase() !in skipExt && save(ctx, it) }
                    }
                    throw e
                }
            } else if (LinkDetector.isGaanaUrl(normUrl)) {
                onProgress(-1f, "Fetching Gaana track…")
                val nGaana = runCatching { fetchGaanaTrack(normUrl, tmp) }.getOrDefault(0)
                if (nGaana > 0) {
                    return tmp.listFiles().orEmpty().count { it.isFile && it.extension.lowercase() !in skipExt && save(ctx, it) }
                }
                val audioOpts = o.copy(kind = Kind.AUDIO)
                try { ytdlp(ctx, normUrl, audioOpts, tmp, procId, cookies, onProgress) } catch (e: Exception) { throw e }
            } else if (o.kind == Kind.IMAGE) {
                var err: Exception? = null
                val n = try { gallery(ctx, normUrl, tmp, cookies, onProgress) } catch (e: Exception) { err = e; 0 }
                if (n <= 0) {
                    if (cancelled) throw IllegalStateException("Cancelled")
                    try { ytdlp(ctx, normUrl, o, tmp, procId, cookies, onProgress) } catch (e: Exception) { throw e }
                }
            } else {
                try {
                    ytdlp(ctx, normUrl, o, tmp, procId, cookies, onProgress)
                } catch (e: Exception) {
                    if (cancelled) throw e
                    if (LinkDetector.isFacebookUrl(normUrl)) {
                        onProgress(-1f, "yt-dlp couldn't parse Facebook page, trying direct extraction…")
                        val nFb = runCatching { fetchFacebookVideo(normUrl, tmp) }.getOrDefault(0)
                        if (nFb > 0) return tmp.listFiles().orEmpty().count { it.isFile && it.extension.lowercase() !in skipExt && save(ctx, it) }
                    } else if (LinkDetector.isInstagramUrl(normUrl)) {
                        onProgress(-1f, "yt-dlp couldn't parse Instagram page, trying direct extraction…")
                        val code = extractInstagramCode(normUrl)
                        val nInsta = if (code != null) runCatching { fetchInstagramApi(code, tmp) }.getOrDefault(0) else 0
                        if (nInsta > 0) return tmp.listFiles().orEmpty().count { it.isFile && it.extension.lowercase() !in skipExt && save(ctx, it) }
                    } else if (LinkDetector.isGaanaUrl(normUrl)) {
                        onProgress(-1f, "yt-dlp couldn't parse Gaana page, trying direct extraction…")
                        val nGaana = runCatching { fetchGaanaTrack(normUrl, tmp) }.getOrDefault(0)
                        if (nGaana > 0) return tmp.listFiles().orEmpty().count { it.isFile && it.extension.lowercase() !in skipExt && save(ctx, it) }
                    }

                    if (o.kind == Kind.VIDEO && isNoVideoFormatError(e)) {
                        onProgress(-1f, "No video format found, downloading audio track…")
                        val audioOpts = o.copy(kind = Kind.AUDIO)
                        ytdlp(ctx, normUrl, audioOpts, tmp, procId, cookies, onProgress)
                    } else if (o.kind == Kind.VIDEO) {
                        onProgress(-1f, "yt-dlp couldn't read this link, trying gallery-dl…")
                        val n = try { gallery(ctx, normUrl, tmp, cookies, onProgress) } catch (_: Exception) { 0 }
                        if (n <= 0) throw e
                    } else {
                        throw e
                    }
                }
            }
            return tmp.listFiles().orEmpty().count { it.isFile && it.extension.lowercase() !in skipExt && save(ctx, it) }
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun fetchOpenGraphInfo(url: String): MediaInfo {
        val desktopUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        val domain = runCatching { URL(url).host.removePrefix("www.") }.getOrDefault("Media Link")
        return runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", desktopUA)
                setRequestProperty("Referer", "https://$domain/")
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                connectTimeout = 5000
                readTimeout = 5000
                instanceFollowRedirects = true
            }
            val html = if (conn.responseCode == 200) conn.inputStream.bufferedReader().use { it.readText() } else ""
            conn.disconnect()

            val titleMatch = Regex("""<meta[^>]+property="og:title"[^>]+content="([^"]+)"""", RegexOption.IGNORE_CASE).find(html)
                ?: Regex("""<meta[^>]+content="([^"]+)"[^>]+property="og:title"""", RegexOption.IGNORE_CASE).find(html)
                ?: Regex("""<title>(.*?)</title>""", RegexOption.IGNORE_CASE).find(html)

            val imageMatch = Regex("""<meta[^>]+property="og:image"[^>]+content="([^"]+)"""", RegexOption.IGNORE_CASE).find(html)
                ?: Regex("""<meta[^>]+content="([^"]+)"[^>]+property="og:image"""", RegexOption.IGNORE_CASE).find(html)

            val title = titleMatch?.groupValues?.get(1)?.let { unescapeJsonUrl(it) }?.takeIf { it.isNotBlank() } ?: domain
            val image = imageMatch?.groupValues?.get(1)?.let { unescapeJsonUrl(it) }?.takeIf { it.startsWith("http") }

            MediaInfo(
                title = title,
                uploader = domain.replaceFirstChar { it.uppercase() },
                thumbnail = image,
                seconds = 0
            )
        }.getOrDefault(MediaInfo(title = domain, uploader = "", thumbnail = null, seconds = 0))
    }

    private fun fetchGaanaTrack(url: String, tmp: File): Int {
        val desktopUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        return runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", desktopUA)
                setRequestProperty("Referer", "https://gaana.com/")
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                connectTimeout = 10_000
                readTimeout = 10_000
                instanceFollowRedirects = true
            }

            val html = if (conn.responseCode == 200) conn.inputStream.bufferedReader().use { it.readText() } else return 0
            conn.disconnect()

            val audioUrls = mutableListOf<String>()

            val ogAudio = Regex("""<meta[^>]+property="og:audio"[^>]+content="([^"]+)"""", RegexOption.IGNORE_CASE).find(html)
                ?: Regex("""<meta[^>]+content="([^"]+)"[^>]+property="og:audio"""", RegexOption.IGNORE_CASE).find(html)
            if (ogAudio != null) {
                val u = unescapeJsonUrl(ogAudio.groupValues[1])
                if (u.startsWith("http")) audioUrls.add(u)
            }

            if (audioUrls.isEmpty()) {
                val streamMatches = Regex(""""(?:stream_url|high_quality_url|path)"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE).findAll(html)
                for (m in streamMatches) {
                    val u = unescapeJsonUrl(m.groupValues[1])
                    if (u.startsWith("http") || u.endsWith(".mp3") || u.endsWith(".m4a") || u.contains("gaanacdn")) {
                        audioUrls.add(u)
                    }
                }
            }

            if (audioUrls.isEmpty()) {
                val cdnMatches = Regex("""https?:\\?/\\?/[^"'\s<>]+?(?:gaanacdn|gaana)\.com[^"'\s<>]+\.(?:mp3|m4a|aac|ogg|mp4)[^"'\s<>]*""", RegexOption.IGNORE_CASE).findAll(html)
                for (m in cdnMatches) {
                    val candidate = unescapeJsonUrl(m.value)
                    audioUrls.add(candidate)
                }
            }

            var savedCount = 0
            val unique = audioUrls.distinct()
            for ((idx, audioUrl) in unique.withIndex()) {
                val file = File(tmp, "gaana_${System.nanoTime()}_$idx.mp3")
                val dlConn = runCatching {
                    (URL(audioUrl).openConnection() as HttpURLConnection).apply {
                        setRequestProperty("User-Agent", desktopUA)
                        setRequestProperty("Referer", "https://gaana.com/")
                        connectTimeout = 15_000
                        readTimeout = 30_000
                        instanceFollowRedirects = true
                    }
                }.getOrNull() ?: continue

                try {
                    if (dlConn.responseCode in 200..299) {
                        dlConn.inputStream.use { inp ->
                            file.outputStream().use { inp.copyTo(it) }
                        }
                    }
                    if (file.length() > 1000) savedCount++ else file.delete()
                } catch (_: Exception) {
                    file.delete()
                }
            }
            savedCount
        }.getOrDefault(0)
    }

    private fun fetchFacebookVideo(url: String, tmp: File): Int {
        val desktopUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        val mobileUA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        return runCatching {
            val videoUrls = mutableListOf<String>()

            // 1. Try Facebook Plugin Embed API (fdown.net mechanism)
            val encodedUrl = URLEncoder.encode(url, "UTF-8")
            val pluginUrl = "https://www.facebook.com/plugins/video.php?href=$encodedUrl"

            val pConn = runCatching {
                (URL(pluginUrl).openConnection() as HttpURLConnection).apply {
                    setRequestProperty("User-Agent", desktopUA)
                    setRequestProperty("Referer", "https://www.facebook.com/")
                    setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    instanceFollowRedirects = true
                }
            }.getOrNull()

            val pHtml = if (pConn?.responseCode == 200) pConn.inputStream.bufferedReader().use { it.readText() } else ""
            pConn?.disconnect()

            if (pHtml.isNotBlank()) {
                extractFacebookVideoUrlsFromHtml(pHtml, videoUrls)
            }

            // 2. Try m.facebook.com with Mobile UA
            if (videoUrls.isEmpty()) {
                val mUrl = url.replace("www.facebook.com", "m.facebook.com").replace("mbasic.facebook.com", "m.facebook.com")
                val conn = runCatching {
                    (URL(mUrl).openConnection() as HttpURLConnection).apply {
                        setRequestProperty("User-Agent", mobileUA)
                        setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                        connectTimeout = 10_000
                        readTimeout = 10_000
                        instanceFollowRedirects = true
                    }
                }.getOrNull()

                val mHtml = if (conn?.responseCode == 200) conn.inputStream.bufferedReader().use { it.readText() } else ""
                conn?.disconnect()

                if (mHtml.isNotBlank()) {
                    extractFacebookVideoUrlsFromHtml(mHtml, videoUrls)
                }
            }

            // 3. Try desktop www.facebook.com
            if (videoUrls.isEmpty()) {
                val dUrl = url.replace("m.facebook.com", "www.facebook.com").replace("mbasic.facebook.com", "www.facebook.com")
                val dConn = runCatching {
                    (URL(dUrl).openConnection() as HttpURLConnection).apply {
                        setRequestProperty("User-Agent", desktopUA)
                        setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                        connectTimeout = 10_000
                        readTimeout = 10_000
                        instanceFollowRedirects = true
                    }
                }.getOrNull()

                val dHtml = if (dConn?.responseCode == 200) dConn.inputStream.bufferedReader().use { it.readText() } else ""
                dConn?.disconnect()

                if (dHtml.isNotBlank()) {
                    extractFacebookVideoUrlsFromHtml(dHtml, videoUrls)
                }
            }

            var savedCount = 0
            val unique = videoUrls.distinct()
            if (unique.isNotEmpty()) {
                val bestUrl = unique.first()
                val file = File(tmp, "facebook_${System.currentTimeMillis()}.mp4")
                val dlConn = runCatching {
                    (URL(bestUrl).openConnection() as HttpURLConnection).apply {
                        setRequestProperty("User-Agent", desktopUA)
                        setRequestProperty("Referer", "https://www.facebook.com/")
                        connectTimeout = 15_000
                        readTimeout = 30_000
                        instanceFollowRedirects = true
                    }
                }.getOrNull()

                if (dlConn != null) {
                    try {
                        if (dlConn.responseCode in 200..299) {
                            dlConn.inputStream.use { inp ->
                                file.outputStream().use { inp.copyTo(it) }
                            }
                        }
                        if (file.length() > 5000) savedCount++ else file.delete()
                    } catch (_: Exception) {
                        file.delete()
                    }
                }
            }
            savedCount
        }.getOrDefault(0)
    }

    private fun extractFacebookVideoUrlsFromHtml(html: String, urls: MutableList<String>) {
        // 1. Schema.org contentUrl (highest resolution)
        val contentUrlMatches = Regex(""""contentUrl"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE).findAll(html)
        for (m in contentUrlMatches) {
            val v = unescapeJsonUrl(m.groupValues[1])
            if (v.startsWith("http")) urls.add(v)
        }

        // 2. browser_native_hd_url / browser_native_sd_url
        val browserNativeMatches = Regex(""""browser_native_(?:hd|sd)_url"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE).findAll(html)
        for (m in browserNativeMatches) {
            val v = unescapeJsonUrl(m.groupValues[1])
            if (v.startsWith("http")) urls.add(v)
        }

        // 3. hd_src / hd_src_no_ratelimit
        val hdSrcMatches = Regex(""""hd_src(?:_no_ratelimit)?"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE).findAll(html)
        for (m in hdSrcMatches) {
            val v = unescapeJsonUrl(m.groupValues[1])
            if (v.startsWith("http")) urls.add(v)
        }

        // 4. playable_url_quality_hd
        val hdMatches = Regex(""""playable_url_quality_hd"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE).findAll(html)
        for (m in hdMatches) {
            val v = unescapeJsonUrl(m.groupValues[1])
            if (v.startsWith("http")) urls.add(v)
        }

        // 5. sd_src / sd_src_no_ratelimit
        val sdSrcMatches = Regex(""""sd_src(?:_no_ratelimit)?"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE).findAll(html)
        for (m in sdSrcMatches) {
            val v = unescapeJsonUrl(m.groupValues[1])
            if (v.startsWith("http")) urls.add(v)
        }

        // 6. playable_url
        val sdMatches = Regex(""""playable_url"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE).findAll(html)
        for (m in sdMatches) {
            val v = unescapeJsonUrl(m.groupValues[1])
            if (v.startsWith("http")) urls.add(v)
        }

        // 7. video / source tags
        val videoTagMatches = Regex("""<video[^>]+src="([^"]+)"""", RegexOption.IGNORE_CASE).findAll(html)
        for (m in videoTagMatches) {
            val v = unescapeJsonUrl(m.groupValues[1])
            if (v.startsWith("http")) urls.add(v)
        }

        // 8. og:video / og:video:secure_url
        val ogVideo = Regex("""<meta[^>]+property="og:video"[^>]+content="([^"]+)"""", RegexOption.IGNORE_CASE).find(html)
            ?: Regex("""<meta[^>]+content="([^"]+)"[^>]+property="og:video"""", RegexOption.IGNORE_CASE).find(html)
            ?: Regex("""<meta[^>]+property="og:video:secure_url"[^>]+content="([^"]+)"""", RegexOption.IGNORE_CASE).find(html)

        if (ogVideo != null) {
            val v = unescapeJsonUrl(ogVideo.groupValues[1])
            if (v.startsWith("http")) urls.add(v)
        }

        // 9. Generic fbcdn.net .mp4 URLs in script
        if (urls.isEmpty()) {
            val cdnMatches = Regex("""https?:\\?/\\?/[^"'\s<>]+?fbcdn\.net[^"'\s<>]+\.mp4[^"'\s<>]*""", RegexOption.IGNORE_CASE).findAll(html)
            for (m in cdnMatches) {
                val v = unescapeJsonUrl(m.value)
                if (v.startsWith("http")) urls.add(v)
            }
        }
    }

    private fun fetchFacebookImage(url: String, tmp: File): Int {
        val mobileUA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        val mUrl = url.replace("www.facebook.com", "mbasic.facebook.com").replace("m.facebook.com", "mbasic.facebook.com")
        return runCatching {
            val conn = (URL(mUrl).openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", mobileUA)
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                connectTimeout = 10_000
                readTimeout = 10_000
                instanceFollowRedirects = true
            }

            val html = if (conn.responseCode == 200) conn.inputStream.bufferedReader().use { it.readText() } else return 0
            conn.disconnect()

            val imageUrls = mutableListOf<String>()

            val ogMatch = Regex("""<meta[^>]+property="og:image"[^>]+content="([^"]+)"""", RegexOption.IGNORE_CASE).find(html)
                ?: Regex("""<meta[^>]+content="([^"]+)"[^>]+property="og:image"""", RegexOption.IGNORE_CASE).find(html)
                ?: Regex("""<meta[^>]+property="og:image:url"[^>]+content="([^"]+)"""", RegexOption.IGNORE_CASE).find(html)

            if (ogMatch != null) {
                val imgUrl = unescapeJsonUrl(ogMatch.groupValues[1])
                if (imgUrl.startsWith("http") && !imgUrl.contains("static") && !imgUrl.contains("rsrc")) {
                    imageUrls.add(imgUrl)
                }
            }

            if (imageUrls.isEmpty()) {
                val cdnMatches = Regex("""https?:\\?/\\?/[^"'\s<>]+?fbcdn\.net[^"'\s<>]+\.(?:jpg|png|webp)[^"'\s<>]*""", RegexOption.IGNORE_CASE).findAll(html)
                for (m in cdnMatches) {
                    val candidate = unescapeJsonUrl(m.value)
                    if (!candidate.contains("static") && !candidate.contains("rsrc") && !candidate.contains("s150x150") && !candidate.contains("p50x50")) {
                        imageUrls.add(candidate)
                    }
                }
            }

            var savedCount = 0
            val unique = imageUrls.distinct()
            for ((idx, imgUrl) in unique.withIndex()) {
                val file = File(tmp, "facebook_${System.currentTimeMillis()}_$idx.jpg")
                val dlConn = runCatching {
                    (URL(imgUrl).openConnection() as HttpURLConnection).apply {
                        setRequestProperty("User-Agent", mobileUA)
                        setRequestProperty("Referer", "https://m.facebook.com/")
                        connectTimeout = 15_000
                        readTimeout = 30_000
                        instanceFollowRedirects = true
                    }
                }.getOrNull() ?: continue

                try {
                    if (dlConn.responseCode in 200..299) {
                        dlConn.inputStream.use { inp ->
                            file.outputStream().use { inp.copyTo(it) }
                        }
                    }
                    if (file.length() > 500) savedCount++ else file.delete()
                } catch (_: Exception) {
                    file.delete()
                }
            }
            savedCount
        }.getOrDefault(0)
    }

    private fun isNoVideoFormatError(e: Throwable): Boolean {
        val msg = e.message.orEmpty()
        return msg.contains("No video formats found", ignoreCase = true) ||
               msg.contains("Requested format is not available", ignoreCase = true) ||
               msg.contains("Unsupported URL", ignoreCase = true) ||
               msg.contains("please report this issue on", ignoreCase = true)
    }

    private fun isMainPostMediaUrl(rawUrl: String): Boolean {
        val lower = rawUrl.lowercase()
        if (!lower.startsWith("http")) return false
        if (lower.contains("static.cdninstagram") ||
            lower.contains("rsrc.php") ||
            lower.contains("/static/") ||
            lower.contains("logo") ||
            lower.contains("glyph") ||
            lower.contains("icon") ||
            lower.contains("avatar") ||
            lower.contains("profile_pic") ||
            lower.contains("s150x150") ||
            lower.contains("150x150_")
        ) return false
        return true
    }

    private fun parseInstagramResponse(content: String): List<String> {
        val trimmed = content.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return parseInstagramApiJson(trimmed)
        }
        val urls = mutableListOf<String>()

        val ogVideo = Regex("""<meta[^>]+property="og:video"[^>]+content="([^"]+)"""", RegexOption.IGNORE_CASE).find(trimmed)
            ?: Regex("""<meta[^>]+content="([^"]+)"[^>]+property="og:video"""", RegexOption.IGNORE_CASE).find(trimmed)
        if (ogVideo != null) {
            val u = unescapeJsonUrl(ogVideo.groupValues[1])
            if (isMainPostMediaUrl(u)) urls.add(u)
        }

        val ogImage = Regex("""<meta[^>]+property="og:image"[^>]+content="([^"]+)"""", RegexOption.IGNORE_CASE).find(trimmed)
            ?: Regex("""<meta[^>]+content="([^"]+)"[^>]+property="og:image"""", RegexOption.IGNORE_CASE).find(trimmed)
            ?: Regex("""<meta[^>]+name="twitter:image"[^>]+content="([^"]+)"""", RegexOption.IGNORE_CASE).find(trimmed)
        if (ogImage != null) {
            val u = unescapeJsonUrl(ogImage.groupValues[1])
            if (isMainPostMediaUrl(u)) urls.add(u)
        }

        val displayUrlMatches = Regex(""""display_url"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE).findAll(trimmed)
        for (m in displayUrlMatches) {
            val u = unescapeJsonUrl(m.groupValues[1])
            if (isMainPostMediaUrl(u)) urls.add(u)
        }

        val videoUrlMatches = Regex(""""video_url"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE).findAll(trimmed)
        for (m in videoUrlMatches) {
            val u = unescapeJsonUrl(m.groupValues[1])
            if (isMainPostMediaUrl(u)) urls.add(u)
        }

        if (urls.isEmpty()) {
            val cdnMatches = Regex("""https?:\\?/\\?/[^"'\s<>]+?\.(?:jpg|jpeg|png|webp|mp4)[^"'\s<>]*""", RegexOption.IGNORE_CASE).findAll(trimmed)
            for (m in cdnMatches) {
                val u = unescapeJsonUrl(m.value)
                if (isMainPostMediaUrl(u)) urls.add(u)
            }
        }
        return urls.distinct()
    }

    private fun parseInstagramApiJson(jsonStr: String): List<String> {
        val urls = mutableListOf<String>()
        runCatching {
            val root = JSONObject(jsonStr)

            // ddinstagram / vxinstagram format: {"item": {"media_urls": [...]}}
            val itemObj = root.optJSONObject("item")
            val mediaUrlsArr = itemObj?.optJSONArray("media_urls") ?: root.optJSONArray("media_urls")
            if (mediaUrlsArr != null && mediaUrlsArr.length() > 0) {
                for (m in 0 until mediaUrlsArr.length()) {
                    val u = unescapeJsonUrl(mediaUrlsArr.getString(m))
                    if (u.isNotBlank()) urls.add(u)
                }
                if (urls.isNotEmpty()) return urls
            }

            // 1. Check data -> xdt_shortcode_media or shortcode_media
            val dataObj = root.optJSONObject("data")
            val mediaObj = dataObj?.optJSONObject("xdt_shortcode_media")
                ?: dataObj?.optJSONObject("shortcode_media")
                ?: root.optJSONObject("graphql")?.optJSONObject("shortcode_media")

            if (mediaObj != null) {
                extractMediaFromGraphqlObj(mediaObj, urls)
                if (urls.isNotEmpty()) return urls
            }

            // 2. Check items array
            val items = root.optJSONArray("items")
            if (items != null && items.length() > 0) {
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    val carousel = item.optJSONArray("carousel_media")
                    if (carousel != null && carousel.length() > 0) {
                        for (c in 0 until carousel.length()) {
                            val child = carousel.getJSONObject(c)
                            extractMediaFromItemNode(child, urls)
                        }
                    } else {
                        extractMediaFromItemNode(item, urls)
                    }
                }
                if (urls.isNotEmpty()) return urls
            }

            // 3. Check oEmbed / thumbnail_url
            val thumbUrl = root.optString("thumbnail_url")
            if (thumbUrl.isNotBlank()) {
                urls.add(unescapeJsonUrl(thumbUrl))
                return urls
            }

            val directDisplayUrl = root.optString("display_url")
            if (directDisplayUrl.isNotBlank()) {
                urls.add(unescapeJsonUrl(directDisplayUrl))
                return urls
            }
        }
        return urls
    }

    private fun extractMediaFromGraphqlObj(node: JSONObject, urls: MutableList<String>) {
        val sidecar = node.optJSONObject("edge_sidecar_to_children")?.optJSONArray("edges")
        if (sidecar != null && sidecar.length() > 0) {
            for (i in 0 until sidecar.length()) {
                val childNode = sidecar.getJSONObject(i).optJSONObject("node") ?: continue
                extractMediaFromGraphqlObj(childNode, urls)
            }
            return
        }

        val videoUrl = node.optString("video_url")
        if (videoUrl.isNotBlank()) {
            urls.add(unescapeJsonUrl(videoUrl))
            return
        }

        val displayUrl = node.optString("display_url")
        if (displayUrl.isNotBlank()) {
            urls.add(unescapeJsonUrl(displayUrl))
            return
        }

        val displayResources = node.optJSONArray("display_resources")
        if (displayResources != null && displayResources.length() > 0) {
            val bestRes = displayResources.getJSONObject(displayResources.length() - 1).optString("src")
            if (bestRes.isNotBlank()) urls.add(unescapeJsonUrl(bestRes))
        }
    }

    private fun extractMediaFromItemNode(node: JSONObject, urls: MutableList<String>) {
        val videoVersions = node.optJSONArray("video_versions")
        if (videoVersions != null && videoVersions.length() > 0) {
            val bestVideo = videoVersions.getJSONObject(0).optString("url")
            if (bestVideo.isNotBlank()) urls.add(unescapeJsonUrl(bestVideo))
        } else {
            val videoUrl = node.optString("video_url")
            if (videoUrl.isNotBlank()) urls.add(videoUrl)
        }

        val imageVersions = node.optJSONObject("image_versions2")?.optJSONArray("candidates")
        if (imageVersions != null && imageVersions.length() > 0) {
            val bestImage = imageVersions.getJSONObject(0).optString("url")
            if (bestImage.isNotBlank()) urls.add(unescapeJsonUrl(bestImage))
        } else {
            val displayUrl = node.optString("display_url")
            if (displayUrl.isNotBlank()) urls.add(unescapeJsonUrl(displayUrl))
        }
    }

    private fun fetchInstagramApiThumbnail(code: String): String? {
        val desktopUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        val endpoints = listOf(
            "https://www.instagram.com/graphql/query/?doc_id=8845758582113289&variables=%7B%22shortcode%22%3A%22$code%22%7D",
            "https://www.instagram.com/p/$code/?__a=1&__d=dis",
            "https://www.instagram.com/api/v1/media/by/code/$code/info/",
            "https://ddinstagram.com/p/$code/",
            "https://vxinstagram.com/p/$code/",
            "https://api.instagram.com/oembed/?url=https://www.instagram.com/p/$code/"
        )
        for (endpoint in endpoints) {
            val conn = runCatching {
                (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    setRequestProperty("User-Agent", desktopUA)
                    setRequestProperty("Referer", "https://www.instagram.com/")
                    setRequestProperty("X-IG-App-ID", "936619743392459")
                    setRequestProperty("X-ASBD-ID", "198387")
                    setRequestProperty("X-Requested-With", "XMLHttpRequest")
                    connectTimeout = 8000
                    readTimeout = 8000
                }
            }.getOrNull() ?: continue

            val bodyText = runCatching {
                if (conn.responseCode == 200) conn.inputStream.bufferedReader().use { it.readText() } else null
            }.getOrNull() ?: continue

            val mediaUrls = parseInstagramResponse(bodyText)
            if (mediaUrls.isNotEmpty()) return mediaUrls.first()
        }
        return null
    }

    private fun fetchInstagramApi(code: String, tmp: File): Int {
        val desktopUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        val endpoints = listOf(
            "https://www.instagram.com/graphql/query/?doc_id=8845758582113289&variables=%7B%22shortcode%22%3A%22$code%22%7D",
            "https://www.instagram.com/p/$code/?__a=1&__d=dis",
            "https://www.instagram.com/api/v1/media/by/code/$code/info/",
            "https://ddinstagram.com/p/$code/",
            "https://vxinstagram.com/p/$code/",
            "https://kkinstagram.com/p/$code/",
            "https://api.instagram.com/oembed/?url=https://www.instagram.com/p/$code/"
        )

        for (endpoint in endpoints) {
            val conn = runCatching {
                (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    setRequestProperty("User-Agent", desktopUA)
                    setRequestProperty("Referer", "https://www.instagram.com/")
                    setRequestProperty("X-IG-App-ID", "936619743392459")
                    setRequestProperty("X-ASBD-ID", "198387")
                    setRequestProperty("X-Requested-With", "XMLHttpRequest")
                    setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                    setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    instanceFollowRedirects = true
                }
            }.getOrNull() ?: continue

            val bodyText = runCatching {
                if (conn.responseCode == 200) conn.inputStream.bufferedReader().use { it.readText() } else null
            }.getOrNull() ?: continue

            val mediaUrls = parseInstagramResponse(bodyText)
            if (mediaUrls.isNotEmpty()) {
                var savedCount = 0
                val uniqueUrls = mediaUrls.distinct()
                for ((idx, mediaUrl) in uniqueUrls.withIndex()) {
                    val cleanUrl = unescapeJsonUrl(mediaUrl)
                    val isVideo = cleanUrl.contains(".mp4", ignoreCase = true) || cleanUrl.contains("video", ignoreCase = true)
                    val ext = if (isVideo) "mp4" else "jpg"
                    val file = File(tmp, "instagram_${code}_$idx.$ext")
                    val dlConn = runCatching {
                        (URL(cleanUrl).openConnection() as HttpURLConnection).apply {
                            setRequestProperty("User-Agent", desktopUA)
                            setRequestProperty("Referer", "https://www.instagram.com/")
                            setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                            connectTimeout = 15_000
                            readTimeout = 30_000
                            instanceFollowRedirects = true
                        }
                    }.getOrNull() ?: continue

                    try {
                        val responseCode = dlConn.responseCode
                        val inputStream = if (responseCode in 200..299) dlConn.inputStream else null
                        if (inputStream != null) {
                            inputStream.use { inp ->
                                file.outputStream().use { inp.copyTo(it) }
                            }
                        }
                        if (file.length() > 500) savedCount++ else file.delete()
                    } catch (_: Exception) {
                        file.delete()
                    }
                }
                if (savedCount > 0) return savedCount
            }
        }
        return 0
    }

    private fun fetchInstagramEmbedThumbnail(url: String): String? {
        return runCatching {
            val codeMatch = Regex("""instagram\.com/(?:p|reel|reels|tv|share/p|share/reel)/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE).find(url) ?: return null
            val code = codeMatch.groupValues[1]
            val embedUrl = "https://www.instagram.com/p/$code/embed/captioned/"
            val conn = (URL(embedUrl).openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                connectTimeout = 10_000
                readTimeout = 10_000
                instanceFollowRedirects = true
            }
            val html = conn.inputStream.bufferedReader().use { it.readText() }

            // 1. display_url in JSON script
            val displayUrlMatch = Regex(""""display_url"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE).find(html)
            if (displayUrlMatch != null) {
                val candidate = unescapeJsonUrl(displayUrlMatch.groupValues[1])
                if (isMainPostMediaUrl(candidate)) return candidate
            }

            // 2. img src matches
            val imgMatches = Regex("""<img[^>]+src="([^"]+)"[^>]*>""", RegexOption.IGNORE_CASE).findAll(html)
            for (m in imgMatches) {
                val candidate = unescapeJsonUrl(m.groupValues[1])
                if (isMainPostMediaUrl(candidate)) return candidate
            }
            null
        }.getOrNull()
    }

    private fun fetchInstagramEmbed(url: String, tmp: File): Int {
        val codeMatch = Regex("""instagram\.com/(?:p|reel|reels|tv|share/p|share/reel)/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE).find(url)
            ?: return 0
        val code = codeMatch.groupValues[1]
        val embedUrl = "https://www.instagram.com/p/$code/embed/captioned/"

        val conn = (URL(embedUrl).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }

        val html = try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            return 0
        }

        val mediaUrls = mutableListOf<String>()

        // 1. High-res video URL if present (reels/videos)
        val videoUrlMatches = Regex(""""video_url"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE).findAll(html)
        for (m in videoUrlMatches) {
            val vUrl = unescapeJsonUrl(m.groupValues[1])
            if (isMainPostMediaUrl(vUrl)) mediaUrls.add(vUrl)
        }
        val videoTagMatches = Regex("""<video[^>]+src="([^"]+)"""", RegexOption.IGNORE_CASE).findAll(html)
        for (m in videoTagMatches) {
            val vUrl = unescapeJsonUrl(m.groupValues[1])
            if (isMainPostMediaUrl(vUrl)) mediaUrls.add(vUrl)
        }

        // 2. High-res display_url matches (JSON in script tag)
        val displayUrlMatches = Regex(""""display_url"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE).findAll(html)
        for (m in displayUrlMatches) {
            val iUrl = unescapeJsonUrl(m.groupValues[1])
            if (isMainPostMediaUrl(iUrl)) mediaUrls.add(iUrl)
        }

        // 3. thumbnail_src matches
        val thumbSrcMatches = Regex(""""thumbnail_src"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE).findAll(html)
        for (m in thumbSrcMatches) {
            val iUrl = unescapeJsonUrl(m.groupValues[1])
            if (isMainPostMediaUrl(iUrl)) mediaUrls.add(iUrl)
        }

        // 4. Image tag src matches
        val imgTagMatches = Regex("""<img[^>]+src="([^"]+)"""", RegexOption.IGNORE_CASE).findAll(html)
        for (m in imgTagMatches) {
            val iUrl = unescapeJsonUrl(m.groupValues[1])
            if (isMainPostMediaUrl(iUrl)) mediaUrls.add(iUrl)
        }

        // 5. srcset URLs
        val srcsetMatches = Regex("""srcset="([^"]+)"""", RegexOption.IGNORE_CASE).findAll(html)
        for (m in srcsetMatches) {
            val parts = m.groupValues[1].split(",")
            for (part in parts) {
                val candidate = unescapeJsonUrl(part.trim().substringBefore(' '))
                if (isMainPostMediaUrl(candidate)) mediaUrls.add(candidate)
            }
        }

        var savedCount = 0
        val uniqueUrls = mediaUrls.distinct()
        for ((idx, mediaUrl) in uniqueUrls.withIndex()) {
            val isVideo = mediaUrl.contains(".mp4", ignoreCase = true) || mediaUrl.contains("video", ignoreCase = true)
            val ext = if (isVideo) "mp4" else "jpg"
            val file = File(tmp, "instagram_${code}_$idx.$ext")
            val dlConn = (URL(mediaUrl).openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", UA)
                connectTimeout = 15_000
                readTimeout = 30_000
            }
            try {
                dlConn.inputStream.use { inp ->
                    file.outputStream().use { inp.copyTo(it) }
                }
                if (file.length() > 500) savedCount++ else file.delete()
            } catch (_: Exception) {
                file.delete()
            }
        }
        return savedCount
    }

    private fun unescapeJsonUrl(url: String): String {
        return url.replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
    }

    private fun gallery(ctx: Context, url: String, tmp: File, cookies: File?, onProgress: (Float, String) -> Unit): Int {
        var seen = 0
        val n = Gallery.run(ctx, url, tmp, cookies) { line ->
            seen++
            onProgress(-1f, "Saved $seen: ${line.substringAfterLast('/')}")
        }
        if (n < 0) throw IllegalStateException("Cancelled")
        return n
    }

    private fun fetchDirect(url: String, ext: String, tmp: File) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", UA)
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.inputStream.use { inp ->
            File(tmp, "image_${System.currentTimeMillis()}.$ext").outputStream().use { inp.copyTo(it) }
        }
    }

    private fun applyFormatOptions(r: YoutubeDLRequest, o: Options, url: String) {
        // Embed metadata (artist, title, uploader) into downloaded media
        r.addOption("--embed-metadata")

        // Only embed thumbnail as cover art for audio files (MP3/M4A).
        // For video files, embedding a thumbnail adds an MJPEG image stream to the MP4,
        // causing ExoPlayer and Android media players to display a static thumbnail instead of video!
        if (o.kind == Kind.AUDIO) {
            r.addOption("--embed-thumbnail")
        }

        val isClipping = !o.clipStart.isNullOrBlank() && !o.clipEnd.isNullOrBlank()
        if (isClipping) {
            val start = o.clipStart.trim()
            val end = o.clipEnd.trim()
            r.addOption("--download-sections", "*$start-$end")
            r.addOption("--force-keyframes-at-cuts")
            if (o.kind == Kind.VIDEO) {
                r.addOption("--recode-video", o.videoFormat.ifBlank { "mp4" })
            }
        }

        when (o.kind) {
            Kind.VIDEO -> {
                val formatString = if (o.maxHeight > 0) {
                    "bv*[height<=${o.maxHeight}][vcodec^=avc1]+ba[acodec^=mp4a]/bv*[height<=${o.maxHeight}]+ba/b[height<=${o.maxHeight}]/bv*+ba/b/best"
                } else {
                    "bv*[vcodec^=avc1]+ba[acodec^=mp4a]/bv*+ba/b/best"
                }
                r.addOption("-f", formatString)
                r.addOption("--merge-output-format", o.videoFormat)
            }
            Kind.AUDIO -> {
                r.addOption("-f", "ba/b/best")
                r.addOption("-x")
                r.addOption("--audio-format", o.audioFormat)
                r.addOption("--audio-quality", "0")
            }
            Kind.GIF -> {
                // First 15 s, 480p, 12 fps keeps GIFs a sane size.
                r.addOption("-f", "bv*/b/best")
                r.addOption("-S", "res:480")
                r.addOption("--download-sections", "*0-15")
                r.addOption("--recode-video", "gif")
                r.addOption("--postprocessor-args", "VideoConvertor:-vf fps=12,scale=480:-1:flags=lanczos")
            }
            Kind.IMAGE -> {
                if (LinkDetector.isImagePostUrl(url)) {
                    r.addOption("-f", "b/best")
                    r.addOption("--write-thumbnail")
                    r.addOption("--convert-thumbnails", "jpg")
                } else {
                    r.addOption("--write-thumbnail")
                    r.addOption("--skip-download")
                    r.addOption("--convert-thumbnails", "jpg")
                }
            }
        }
    }

    private fun ytdlp(ctx: Context, url: String, o: Options, tmp: File, procId: String, cookies: File?, onProgress: (Float, String) -> Unit) {
        fun runWithClient(client: String?): Boolean {
            val r = buildYoutubeDLRequest(ctx, url, cookies, client)
            r.addOption("-o", "${tmp.absolutePath}/%(title).80s [%(id)s].%(ext)s")
            applyFormatOptions(r, o, url)

            YoutubeDL.getInstance().execute(r, procId) { p, eta, line ->
                val statusText = formatProgressStatus(p, eta, line)
                onProgress((p / 100f).coerceIn(0f, 1f), statusText)
            }
            return true
        }

        try {
            runWithClient(null)
        } catch (e: Exception) {
            if (cancelled) throw e
            if (isBotCheckError(e)) {
                runWithClient("android,mweb,ios")
            } else {
                throw e
            }
        }
    }

    private fun formatProgressStatus(p: Float, eta: Long, line: String?): String {
        if (p < 0f) return "Starting…"
        val percentInt = p.toInt().coerceIn(0, 100)
        val raw = line ?: ""

        val sizeMatch = Regex("""of\s+~?\s*(\d+(?:\.\d+)?\s*[KMGTP]i?B)""", RegexOption.IGNORE_CASE).find(raw)
        val speedMatch = Regex("""at\s+(\d+(?:\.\d+)?\s*[KMGTP]i?B/s)""", RegexOption.IGNORE_CASE).find(raw)

        val totalSizeStr = sizeMatch?.groupValues?.get(1)
        val speedStr = speedMatch?.groupValues?.get(1)

        val sizePart = if (totalSizeStr != null) {
            val totalBytes = parseBytes(totalSizeStr)
            if (totalBytes > 0) {
                val downloadedBytes = (totalBytes * (p / 100f)).toLong()
                "${formatFileSize(downloadedBytes)} / ${formatFileSize(totalBytes)} ($percentInt%)"
            } else {
                "$percentInt% of $totalSizeStr"
            }
        } else {
            "$percentInt%"
        }

        val speedPart = if (!speedStr.isNullOrBlank()) "  •  $speedStr" else ""
        val etaPart = if (eta > 0) "  •  ETA ${eta}s" else ""

        return "$sizePart$speedPart$etaPart"
    }

    private fun parseBytes(sizeStr: String): Long {
        val match = Regex("""(\d+(?:\.\d+)?)\s*([KMGTP]i?B)""", RegexOption.IGNORE_CASE).find(sizeStr) ?: return 0L
        val value = match.groupValues[1].toDoubleOrNull() ?: return 0L
        val unit = match.groupValues[2].uppercase()

        val multiplier = when {
            unit.startsWith("K") -> 1024L
            unit.startsWith("M") -> 1024L * 1024L
            unit.startsWith("G") -> 1024L * 1024L * 1024L
            unit.startsWith("T") -> 1024L * 1024L * 1024L * 1024L
            else -> 1L
        }
        return (value * multiplier).toLong()
    }

    fun cancel(procId: String) {
        cancelled = true
        runCatching { YoutubeDL.getInstance().destroyProcessById(procId) }
        Gallery.cancel() // takes effect between files
    }

    /** Sites change often; updating yt-dlp is the usual fix when a link stops working. */
    fun updateEngine(ctx: Context): YoutubeDL.UpdateStatus? {
        sanitizePythonEnv()
        return YoutubeDL.getInstance().updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.STABLE)
    }

    fun version(ctx: Context): String? {
        return runCatching { YoutubeDL.getInstance().version(ctx) }.getOrNull()
    }

    private fun save(ctx: Context, f: File): Boolean {
        val ext = f.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: when (ext) {
                "mp4", "mkv", "webm" -> "video/$ext"
                "mp3", "m4a", "opus", "flac", "wav", "aac", "ogg" -> "audio/$ext"
                "jpg", "jpeg", "png", "webp", "gif" -> "image/$ext"
                else -> "application/octet-stream"
            }

        val collection = when {
            mime.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            mime.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            mime.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }

        val relativePath = when {
            mime.startsWith("video/") -> "Movies/UniversalDL"
            mime.startsWith("audio/") -> "Music/UniversalDL"
            mime.startsWith("image/") -> "Pictures/UniversalDL"
            else -> "Download/UniversalDL"
        }

        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        }

        val resolver = ctx.contentResolver
        val uri = try {
            resolver.insert(collection, cv) ?: resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
        } catch (_: Exception) {
            try {
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/UniversalDL")
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
            } catch (_: Exception) {
                null
            }
        } ?: return false

        return try {
            resolver.openOutputStream(uri)?.use { out ->
                f.inputStream().use { it.copyTo(out) }
            }
            true
        } catch (_: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }
}
