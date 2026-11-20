package com.sweetsamosa.universaldl.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class ClipState(
    val url: String = "",
    val info: MediaInfo? = null,
    val busy: Boolean = false,
    val status: String = "",
    val progress: Float = -1f,
    val startHours: Int = 0,
    val startMinutes: Int = 0,
    val startSeconds: Int = 0,
    val endHours: Int = 0,
    val endMinutes: Int = 0,
    val endSeconds: Int = 0,
    val maxHeight: Int = 1080,
    val isAudioOnly: Boolean = false,
    val videoFormat: String = "mp4"
)

data class UiState(
    val url: String = "",
    val info: MediaInfo? = null,
    val opts: Options = Options(),
    val status: String = "Starting engine…",
    val progress: Float = -1f,   // 0..1 = determinate, <0 = indeterminate (while busy)
    val busy: Boolean = false,
    val ready: Boolean = false,
    val hasCookies: Boolean = false,
    val downloads: List<DownloadedItem> = emptyList(),
    val currentTab: Int = 0, // 0 = Home, 1 = Clip Cutter, 2 = Downloads, 3 = Settings
    val clipState: ClipState = ClipState(),
    val downloadCompletedEvent: Long = 0L,
    val engineStatus: String = "",
    val ytVersion: String = "",
    val gdlVersion: String = "",
    val isDownloadedForCurrentOpts: Boolean = false,
    val hasNewDownloads: Boolean = false,
    val isAmoledLyrics: Boolean = false
)

/** Process-wide owner of all work, so downloads outlive the Activity (the foreground service keeps the process alive). */
object Manager {
    val ui = MutableStateFlow(UiState())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val PROC = "dl"
    private var app: Context? = null
    private var fetchJob: Job? = null

    private fun edit(f: UiState.() -> UiState) = ui.update { it.f() }
    private fun msg(e: Throwable): String {
        val text = e.message ?: e.toString()
        if (text.contains("Sign in to confirm", ignoreCase = true) ||
            text.contains("confirm you're not a bot", ignoreCase = true) ||
            (text.contains("bot", ignoreCase = true) && text.contains("sign in", ignoreCase = true))) {
            return "YouTube bot check triggered. Please import cookies.txt in Settings or Update Engines."
        }
        if (text.contains("login", ignoreCase = true) || text.contains("redirected to login", ignoreCase = true) || text.contains("302", ignoreCase = true)) {
            return "Instagram requires login for this private post. Please import cookies.txt in Settings."
        }
        if (text.contains("cannot parse data", ignoreCase = true) || text.contains("unable to extract", ignoreCase = true)) {
            return "Engine could not parse this link. Please tap 'Update Engines' in Settings or check if post is private."
        }
        if (text.contains("Unsupported URL", ignoreCase = true)) {
            return "Could not extract media format from this link. Try selecting Audio or Image format, or check if link is valid."
        }
        val errLine = text.lines().firstOrNull { it.contains("ERROR:", ignoreCase = true) }
            ?: text.lines().lastOrNull { it.isNotBlank() }
            ?: e.javaClass.simpleName
        return errLine.removePrefix("ERROR:").trim()
    }
    private fun cookieFile(ctx: Context) = File(ctx.filesDir, "cookies.txt")
    private fun cookies(ctx: Context) = cookieFile(ctx).takeIf { it.length() > 0 }

    fun init(ctx: Context) {
        if (app != null) {
            refreshDownloads()
            return
        }
        val a = ctx.applicationContext
        app = a
        val prefs = a.getSharedPreferences("universaldl_settings", Context.MODE_PRIVATE)
        val savedAmoled = prefs.getBoolean("amoled_lyrics", false)

        edit {
            copy(
                hasCookies = cookies(a) != null,
                isAmoledLyrics = savedAmoled
            )
        }

        scope.launch {
            try {
                Downloader.sanitizePythonEnv()
                YoutubeDL.getInstance().init(a)
                FFmpeg.getInstance().init(a)
                val ytVer = Downloader.version(a) ?: "Installed"
                val gdlVer = runCatching { Gallery.version(a) }.getOrDefault("Installed")
                edit { copy(ready = true, status = "Ready", ytVersion = ytVer, gdlVersion = gdlVer) }
                runCatching { Downloader.updateEngine(a) }
                runCatching {
                    Gallery.update(a)
                    val newGdlVer = runCatching { Gallery.version(a) }.getOrDefault("")
                    if (newGdlVer.isNotBlank()) edit { copy(gdlVersion = newGdlVer) }
                }
            } catch (e: Exception) {
                edit { copy(status = "Engine failed: ${msg(e)}") }
            }
            refreshDownloads()
        }
    }

    fun setTab(tab: Int) = edit {
        copy(
            currentTab = tab,
            hasNewDownloads = if (tab == 2) false else hasNewDownloads
        )
    }

    fun toggleAmoledLyrics() {
        val valNew = !ui.value.isAmoledLyrics
        edit { copy(isAmoledLyrics = valNew) }
        app?.getSharedPreferences("universaldl_settings", Context.MODE_PRIVATE)?.edit()?.putBoolean("amoled_lyrics", valNew)?.apply()
    }

    fun setUrl(u: String) {
        val trimmed = u.trim()
        val normalized = LinkDetector.normalizeUrl(trimmed)
        val detectedKind = LinkDetector.detect(normalized)
        edit {
            copy(
                url = normalized,
                info = null,
                isDownloadedForCurrentOpts = false,
                opts = opts.copy(kind = detectedKind)
            )
        }
        if (normalized.startsWith("http://", ignoreCase = true) || normalized.startsWith("https://", ignoreCase = true)) {
            fetchJob?.cancel()
            fetchJob = scope.launch {
                delay(400) // Debounce auto-fetch when typing
                val resolved = LinkDetector.resolveRedirects(normalized)
                val newKind = LinkDetector.detect(resolved)
                edit { copy(url = resolved, opts = opts.copy(kind = newKind)) }
                fetchInternal(resolved)
            }
        }
    }

    fun setOpts(o: Options) = edit { copy(opts = o, isDownloadedForCurrentOpts = false) }

    fun fetch() {
        val u = ui.value.url.trim()
        if (u.isBlank()) return
        fetchJob?.cancel()
        fetchJob = scope.launch { fetchInternal(u) }
    }

    private fun fetchInternal(url: String) {
        val a = app ?: return
        if (ui.value.busy || url.isBlank()) return
        edit { copy(busy = true, progress = -1f, status = "Fetching info…") }
        try {
            val i = Downloader.info(url, cookies(a))
            edit { copy(info = i, status = "Ready") }
        } catch (e: Exception) {
            val m = msg(e)
            edit { copy(info = MediaInfo(url, "", null, 0), status = "No preview ($m). You can still try Download.") }
        } finally {
            edit { copy(busy = false) }
        }
    }

    fun download() {
        val ctx = app ?: return
        val st = ui.value
        val urlToDl = st.url.trim()
        if (urlToDl.isBlank()) return
        edit { copy(busy = true, progress = 0f, status = "Starting…") }
        runCatching { ctx.startForegroundService(Intent(ctx, DownloadService::class.java)) }
        scope.launch {
            try {
                val n = Downloader.download(ctx, urlToDl, st.opts, PROC, cookies(ctx)) { p, text ->
                    edit { copy(progress = p, status = text) }
                }
                val finalStatus = if (n > 0) "Saved $n file(s) to Downloads" else "Nothing was downloaded"
                edit {
                    copy(
                        status = finalStatus,
                        isDownloadedForCurrentOpts = if (n > 0) true else isDownloadedForCurrentOpts,
                        hasNewDownloads = if (n > 0 && currentTab != 2) true else hasNewDownloads,
                        downloadCompletedEvent = if (n > 0) System.currentTimeMillis() else downloadCompletedEvent
                    )
                }
                refreshDownloads()
            } catch (e: Exception) {
                val m = if (Downloader.cancelled) "Cancelled" else "Failed: ${msg(e)}"
                edit { copy(status = m) }
            } finally {
                edit { copy(busy = false, progress = -1f) }
            }
        }
    }

    fun refreshDownloads() {
        val a = app ?: return
        scope.launch {
            val list = DownloadHistory.load(a)
            edit {
                copy(
                    downloads = list,
                    hasNewDownloads = if (list.size > downloads.size && currentTab != 2) true else hasNewDownloads
                )
            }
        }
    }

    private var clipFetchJob: Job? = null

    fun setClipUrl(u: String) {
        val trimmed = u.trim()
        val normalized = LinkDetector.normalizeUrl(trimmed)
        edit {
            copy(clipState = clipState.copy(url = normalized, info = null, status = ""))
        }
        if (normalized.startsWith("http://", ignoreCase = true) || normalized.startsWith("https://", ignoreCase = true)) {
            clipFetchJob?.cancel()
            clipFetchJob = scope.launch {
                delay(400)
                val resolved = LinkDetector.resolveRedirects(normalized)
                edit { copy(clipState = clipState.copy(url = resolved)) }
                fetchClipInfoInternal(resolved)
            }
        }
    }

    fun fetchClipInfo() {
        val u = ui.value.clipState.url.trim()
        if (u.isNotBlank()) {
            clipFetchJob?.cancel()
            clipFetchJob = scope.launch { fetchClipInfoInternal(u) }
        }
    }

    private fun fetchClipInfoInternal(url: String) {
        val a = app ?: return
        if (ui.value.clipState.busy || url.isBlank()) return
        edit { copy(clipState = clipState.copy(busy = true, progress = -1f, status = "Fetching video info…")) }
        try {
            val i = Downloader.info(url, cookies(a))
            val totalSec = i.seconds
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            edit {
                copy(
                    clipState = clipState.copy(
                        info = i,
                        status = "Video loaded",
                        endHours = if (clipState.endHours == 0 && clipState.endMinutes == 0 && clipState.endSeconds == 0) h else clipState.endHours,
                        endMinutes = if (clipState.endHours == 0 && clipState.endMinutes == 0 && clipState.endSeconds == 0) m else clipState.endMinutes,
                        endSeconds = if (clipState.endHours == 0 && clipState.endMinutes == 0 && clipState.endSeconds == 0) s else clipState.endSeconds
                    )
                )
            }
        } catch (e: Exception) {
            edit { copy(clipState = clipState.copy(status = "Could not fetch preview: ${msg(e)}")) }
        } finally {
            edit { copy(clipState = clipState.copy(busy = false)) }
        }
    }

    fun updateClipState(f: ClipState.() -> ClipState) {
        edit { copy(clipState = clipState.f()) }
    }

    fun downloadClip() {
        val ctx = app ?: return
        val cs = ui.value.clipState
        val urlToDl = cs.url.trim()
        if (urlToDl.isBlank()) return

        val startTotal = cs.startHours * 3600 + cs.startMinutes * 60 + cs.startSeconds
        val endTotal = cs.endHours * 3600 + cs.endMinutes * 60 + cs.endSeconds

        if (endTotal <= startTotal) {
            edit { copy(clipState = clipState.copy(status = "End timestamp must be greater than start timestamp.")) }
            return
        }

        val startStr = String.format(java.util.Locale.US, "%02d:%02d:%02d", cs.startHours, cs.startMinutes, cs.startSeconds)
        val endStr = String.format(java.util.Locale.US, "%02d:%02d:%02d", cs.endHours, cs.endMinutes, cs.endSeconds)

        val opts = Options(
            kind = if (cs.isAudioOnly) Kind.AUDIO else Kind.VIDEO,
            videoFormat = cs.videoFormat,
            maxHeight = cs.maxHeight,
            audioFormat = "mp3",
            clipStart = startStr,
            clipEnd = endStr
        )

        edit { copy(clipState = clipState.copy(busy = true, progress = 0f, status = "Trimming clip ($startStr -> $endStr)…")) }
        runCatching { ctx.startForegroundService(Intent(ctx, DownloadService::class.java)) }

        scope.launch {
            try {
                val n = Downloader.download(ctx, urlToDl, opts, PROC, cookies(ctx)) { p, text ->
                    edit { copy(clipState = clipState.copy(progress = p, status = text)) }
                }
                val finalStatus = if (n > 0) "✂️ Clip saved successfully to Downloads!" else "Nothing was downloaded"
                edit {
                    copy(
                        clipState = clipState.copy(status = finalStatus),
                        hasNewDownloads = if (n > 0 && currentTab != 2) true else hasNewDownloads,
                        downloadCompletedEvent = if (n > 0) System.currentTimeMillis() else downloadCompletedEvent
                    )
                }
                refreshDownloads()
            } catch (e: Exception) {
                val m = if (Downloader.cancelled) "Cancelled" else "Clip failed: ${msg(e)}"
                edit { copy(clipState = clipState.copy(status = m)) }
            } finally {
                edit { copy(clipState = clipState.copy(busy = false, progress = -1f)) }
            }
        }
    }

    fun cancelClip() = Downloader.cancel(PROC)

    fun cancel() = Downloader.cancel(PROC)

    fun updateEngine() {
        val a = app ?: return
        scope.launch {
            edit { copy(busy = true, progress = -1f, engineStatus = "Updating yt-dlp…") }
            val ytRes = try {
                val status = Downloader.updateEngine(a)
                val newVer = Downloader.version(a) ?: ""
                if (newVer.isNotBlank()) edit { copy(ytVersion = newVer) }
                "yt-dlp: ${status?.name ?: "DONE"}"
            } catch (e: Exception) {
                "yt-dlp update failed: ${msg(e)}"
            }

            edit { copy(engineStatus = "$ytRes\nUpdating gallery-dl…") }
            val gdRes = try {
                val res = Gallery.update(a)
                val newVer = runCatching { Gallery.version(a) }.getOrDefault("")
                if (newVer.isNotBlank()) edit { copy(gdlVersion = newVer) }
                res
            } catch (e: Exception) {
                "gallery-dl update failed: ${msg(e)}"
            }

            val finalMsg = "Update Completed:\n• $ytRes\n• $gdRes"
            edit { copy(busy = false, engineStatus = finalMsg) }
        }
    }

    /** cookies.txt (Netscape format) from a logged-in browser: unlocks private/age-gated posts. */
    fun importCookies(ctx: Context, uri: Uri) {
        scope.launch {
            try {
                ctx.contentResolver.openInputStream(uri)?.use { i -> cookieFile(ctx).outputStream().use { i.copyTo(it) } }
                edit { copy(hasCookies = cookies(ctx) != null, status = "Cookies imported") }
            } catch (e: Exception) {
                edit { copy(status = "Cookie import failed: ${msg(e)}") }
            }
        }
    }

    fun clearCookies(ctx: Context) {
        cookieFile(ctx).delete()
        edit { copy(hasCookies = false, status = "Cookies removed") }
    }
}
