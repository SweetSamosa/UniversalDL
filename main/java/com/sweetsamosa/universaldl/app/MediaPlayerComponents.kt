package com.sweetsamosa.universaldl.app

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

import java.io.File

data class LrcLine(val timeMs: Long, val text: String)

object SyncedLyricsParser {
    fun parse(text: String): List<LrcLine> {
        val regex = Regex("""\[(\d{2}):(\d{2})(?:\.(\d{2,3}))?\](.*)""")
        val list = mutableListOf<LrcLine>()
        text.lines().forEach { raw ->
            val match = regex.find(raw.trim())
            if (match != null) {
                val min = match.groupValues[1].toLongOrNull() ?: 0L
                val sec = match.groupValues[2].toLongOrNull() ?: 0L
                val msStr = match.groupValues[3]
                val ms = if (msStr.length == 2) (msStr.toLongOrNull() ?: 0L) * 10 else (msStr.toLongOrNull() ?: 0L)
                val timeMs = min * 60_000 + sec * 1000 + ms
                val lineText = match.groupValues[4].trim()
                if (lineText.isNotBlank()) {
                    list.add(LrcLine(timeMs, lineText))
                }
            }
        }
        return list.sortedBy { it.timeMs }
    }

    fun loadForItem(context: Context, item: DownloadedItem): List<LrcLine> {
        runCatching {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(context, item.uri)
            val embeddedLyrics = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
            retriever.release()
            if (!embeddedLyrics.isNullOrBlank()) {
                val lines = parse(embeddedLyrics)
                if (lines.isNotEmpty()) return lines
            }
        }
        return emptyList()
    }
}

fun getAudioCoverArt(context: Context, item: DownloadedItem): android.graphics.Bitmap? {
    return runCatching {
        val retriever = android.media.MediaMetadataRetriever()
        retriever.setDataSource(context, item.uri)
        val artBytes = retriever.embeddedPicture
        retriever.release()
        if (artBytes != null) {
            android.graphics.BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size)
        } else null
    }.getOrNull()
}

object OnlineLyricsProvider {
    fun cleanName(raw: String): String {
        return raw.substringBeforeLast('.')
            .replace(Regex("""\[[a-zA-Z0-9_-]{5,20}\]"""), "")
            .replace(Regex("""\([a-zA-Z0-9_-]{5,20}\)"""), "")
            .replace(Regex("""(?i)\(official\s*(music\s*video|audio|video|lyric\s*video|hd|4k)?\)"""), "")
            .replace(Regex("""(?i)\[official\s*(music\s*video|audio|video|lyric\s*video|hd|4k)?\]"""), "")
            .replace(Regex("""(?i)\(lyrics?\s*(video)?\)"""), "")
            .replace(Regex("""(?i)\[lyrics?\s*(video)?\]"""), "")
            .replace(Regex("""(?i)\(audio\)"""), "")
            .replace(Regex("""(?i)\[audio\]"""), "")
            .replace(Regex("""(?i)\(4k|hd|1080p|720p\)"""), "")
            .replace(Regex("""(?i)\[4k|hd|1080p|720p\]"""), "")
            .replace(Regex("""(?i)ft\..*|feat\..*"""), "")
            .replace('_', ' ')
            .trim()
    }

    private fun httpGet(urlString: String, referer: String? = null): String? {
        return runCatching {
            val conn = java.net.URL(urlString).openConnection() as java.net.HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36")
            referer?.let { conn.setRequestProperty("Referer", it) }
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            val text = if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else null
            conn.disconnect()
            text
        }.getOrNull()
    }

    fun fetchSyncedLyrics(context: Context, item: DownloadedItem): List<LrcLine> {
        val cleanTitle = cleanName(item.name)
        if (cleanTitle.isBlank()) return emptyList()

        // --- Provider 1: LRCLIB API ---
        runCatching {
            val lines = fetchFromLrclib(cleanTitle, item)
            if (lines.isNotEmpty()) return lines
        }

        // --- Provider 2: Netease Cloud Music API ---
        runCatching {
            val lines = fetchFromNetease(cleanTitle, item)
            if (lines.isNotEmpty()) return lines
        }

        // --- Provider 3: Public Mirror API ---
        runCatching {
            val lines = fetchFromMirror(cleanTitle, item)
            if (lines.isNotEmpty()) return lines
        }

        return emptyList()
    }

    private fun fetchFromLrclib(cleanTitle: String, item: DownloadedItem): List<LrcLine> {
        val encodedTitle = java.net.URLEncoder.encode(cleanTitle, "UTF-8")
        val searchJson = httpGet("https://lrclib.net/api/search?q=$encodedTitle")
        if (!searchJson.isNullOrBlank()) {
            val lines = parseLrclibArray(searchJson, item)
            if (lines.isNotEmpty()) return lines
        }

        if (cleanTitle.contains('-')) {
            val parts = cleanTitle.split('-')
            if (parts.size >= 2) {
                val artist = parts[0].trim()
                val track = parts[1].trim()
                if (artist.isNotBlank() && track.isNotBlank()) {
                    val encArtist = java.net.URLEncoder.encode(artist, "UTF-8")
                    val encTrack = java.net.URLEncoder.encode(track, "UTF-8")
                    val getJson = httpGet("https://lrclib.net/api/get?artist_name=$encArtist&track_name=$encTrack")
                    if (!getJson.isNullOrBlank()) {
                        val lines = parseLrclibObject(getJson, item)
                        if (lines.isNotEmpty()) return lines
                    }
                }
            }
        }
        return emptyList()
    }

    private fun parseLrclibArray(jsonText: String, item: DownloadedItem): List<LrcLine> {
        val array = org.json.JSONArray(jsonText)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val syncedLrc = obj.optString("syncedLyrics", "")
            if (syncedLrc.isNotBlank() && syncedLrc != "null") {
                val parsed = SyncedLyricsParser.parse(syncedLrc)
                if (parsed.isNotEmpty()) return parsed
            }
        }
        return emptyList()
    }

    private fun parseLrclibObject(jsonText: String, item: DownloadedItem): List<LrcLine> {
        val obj = org.json.JSONObject(jsonText)
        val syncedLrc = obj.optString("syncedLyrics", "")
        if (syncedLrc.isNotBlank() && syncedLrc != "null") {
            val parsed = SyncedLyricsParser.parse(syncedLrc)
            if (parsed.isNotEmpty()) return parsed
        }
        return emptyList()
    }

    private fun fetchFromNetease(cleanTitle: String, item: DownloadedItem): List<LrcLine> {
        val encodedQuery = java.net.URLEncoder.encode(cleanTitle, "UTF-8")
        val searchUrl = "https://music.163.com/api/search/get/web?csrf_token=&type=1&s=$encodedQuery&limit=3"
        val searchJson = httpGet(searchUrl, "https://music.163.com/") ?: return emptyList()

        val rootObj = org.json.JSONObject(searchJson)
        val resultObj = rootObj.optJSONObject("result") ?: return emptyList()
        val songsArray = resultObj.optJSONArray("songs") ?: return emptyList()

        for (i in 0 until songsArray.length()) {
            val song = songsArray.getJSONObject(i)
            val songId = song.optLong("id", 0L)
            if (songId > 0) {
                val lyricUrl = "https://music.163.com/api/song/lyric?id=$songId&lv=-1&kv=-1&tv=-1"
                val lyricJson = httpGet(lyricUrl, "https://music.163.com/")
                if (!lyricJson.isNullOrBlank()) {
                    val lrcObj = org.json.JSONObject(lyricJson).optJSONObject("lrc")
                    val syncedLrc = lrcObj?.optString("lyric", "").orEmpty()
                    if (syncedLrc.isNotBlank()) {
                        val parsed = SyncedLyricsParser.parse(syncedLrc)
                        if (parsed.isNotEmpty()) return parsed
                    }
                }
            }
        }
        return emptyList()
    }

    private fun fetchFromMirror(cleanTitle: String, item: DownloadedItem): List<LrcLine> {
        val encodedQuery = java.net.URLEncoder.encode(cleanTitle, "UTF-8")
        val mirrorUrl = "https://api.lrc.tf/v1/lyrics?title=$encodedQuery"
        val mirrorJson = httpGet(mirrorUrl) ?: return emptyList()

        val obj = org.json.JSONObject(mirrorJson)
        val syncedLrc = obj.optString("syncedLyrics", "").ifBlank { obj.optString("lrc", "") }
        if (syncedLrc.isNotBlank()) {
            val parsed = SyncedLyricsParser.parse(syncedLrc)
            if (parsed.isNotEmpty()) return parsed
        }
        return emptyList()
    }
}

object AudioPlayerController {
    private var mediaPlayer: MediaPlayer? = null
    val isPlaying = MutableStateFlow(false)
    val currentPosition = MutableStateFlow(0L)
    val duration = MutableStateFlow(0L)
    val currentItem = MutableStateFlow<DownloadedItem?>(null)
    val currentCoverArt = MutableStateFlow<android.graphics.Bitmap?>(null)
    val syncedLyrics = MutableStateFlow<List<LrcLine>>(emptyList())
    val showLyricsPopup = MutableStateFlow(true)

    fun toggleLyricsPopup() {
        showLyricsPopup.value = !showLyricsPopup.value
    }

    private var progressJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var audioManager: android.media.AudioManager? = null
    private var audioFocusChangeListener: android.media.AudioManager.OnAudioFocusChangeListener? = null
    private var appContext: Context? = null
    private var mediaSession: android.media.session.MediaSession? = null
    const val AUDIO_NOTIF_ID = 2001
    private const val AUDIO_CH_ID = "audio_playback_channel_v2"

    private fun requestFocus(context: Context): Boolean {
        val am = (context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager) ?: return true
        audioManager = am
        if (audioFocusChangeListener == null) {
            audioFocusChangeListener = android.media.AudioManager.OnAudioFocusChangeListener { focusChange ->
                when (focusChange) {
                    android.media.AudioManager.AUDIOFOCUS_LOSS,
                    android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        pause()
                    }
                }
            }
        }
        val result = am.requestAudioFocus(
            audioFocusChangeListener,
            android.media.AudioManager.STREAM_MUSIC,
            android.media.AudioManager.AUDIOFOCUS_GAIN
        )
        return result == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        audioFocusChangeListener?.let {
            audioManager?.abandonAudioFocus(it)
        }
    }

    fun playNext(context: Context) {
        val curr = currentItem.value ?: return
        val list = DownloadHistory.load(context).filter { it.kind == Kind.AUDIO }
        if (list.isEmpty()) return
        val idx = list.indexOfFirst { it.id == curr.id || it.path == curr.path }
        val nextIdx = if (idx >= 0) (idx + 1) % list.size else 0
        play(context, list[nextIdx])
    }

    fun playPrevious(context: Context) {
        val curr = currentItem.value ?: return
        val list = DownloadHistory.load(context).filter { it.kind == Kind.AUDIO }
        if (list.isEmpty()) return
        val idx = list.indexOfFirst { it.id == curr.id || it.path == curr.path }
        val prevIdx = if (idx >= 0) (idx - 1 + list.size) % list.size else 0
        play(context, list[prevIdx])
    }

    private fun initMediaSession(ctx: Context) {
        if (mediaSession != null) return
        runCatching {
            val session = android.media.session.MediaSession(ctx, "UniversalDL_MediaSession")
            session.setFlags(
                android.media.session.MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                android.media.session.MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            session.setCallback(object : android.media.session.MediaSession.Callback() {
                override fun onPlay() { togglePlayPause() }
                override fun onPause() { togglePlayPause() }
                override fun onSkipToNext() { appContext?.let { playNext(it) } }
                override fun onSkipToPrevious() { appContext?.let { playPrevious(it) } }
                override fun onSeekTo(pos: Long) { seekTo(pos) }
                override fun onStop() { stop() }
            })
            session.isActive = true
            mediaSession = session
        }
    }

    fun buildNotification(ctx: Context): android.app.Notification? {
        val item = currentItem.value ?: return null
        initMediaSession(ctx)
        val session = mediaSession

        val displayTitle = item.name
        val displaySubtitle = "UniversalDL"

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager ?: return null

        val channel = android.app.NotificationChannel(AUDIO_CH_ID, "Audio Playback", android.app.NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(channel)

        if (session != null) {
            val pbState = android.media.session.PlaybackState.Builder()
                .setActions(
                    android.media.session.PlaybackState.ACTION_PLAY or
                    android.media.session.PlaybackState.ACTION_PAUSE or
                    android.media.session.PlaybackState.ACTION_PLAY_PAUSE or
                    android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT or
                    android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                    android.media.session.PlaybackState.ACTION_SEEK_TO or
                    android.media.session.PlaybackState.ACTION_STOP
                )
                .setState(
                    if (isPlaying.value) android.media.session.PlaybackState.STATE_PLAYING else android.media.session.PlaybackState.STATE_PAUSED,
                    currentPosition.value,
                    1.0f
                )
                .build()
            session.setPlaybackState(pbState)

            val metaBuilder = android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, displayTitle)
                .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, displaySubtitle)
                .putString(android.media.MediaMetadata.METADATA_KEY_ALBUM, "UniversalDL Music")
                .putString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE, displayTitle)
                .putString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, displaySubtitle)
                .putString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION, displayTitle)
                .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, duration.value)

            currentCoverArt.value?.let { bmp ->
                metaBuilder.putBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART, bmp)
                metaBuilder.putBitmap(android.media.MediaMetadata.METADATA_KEY_ART, bmp)
            }
            session.setMetadata(metaBuilder.build())
            session.isActive = true
        }

        val toggleIntent = Intent(ctx, AudioPlayerReceiver::class.java).apply {
            action = AudioPlayerReceiver.ACTION_TOGGLE_PLAY
        }
        val togglePendingIntent = android.app.PendingIntent.getBroadcast(
            ctx, 101, toggleIntent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val prevIntent = Intent(ctx, AudioPlayerReceiver::class.java).apply {
            action = AudioPlayerReceiver.ACTION_PREVIOUS
        }
        val prevPendingIntent = android.app.PendingIntent.getBroadcast(
            ctx, 103, prevIntent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val nextIntent = Intent(ctx, AudioPlayerReceiver::class.java).apply {
            action = AudioPlayerReceiver.ACTION_NEXT
        }
        val nextPendingIntent = android.app.PendingIntent.getBroadcast(
            ctx, 104, nextIntent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(ctx, AudioPlayerReceiver::class.java).apply {
            action = AudioPlayerReceiver.ACTION_STOP
        }
        val stopPendingIntent = android.app.PendingIntent.getBroadcast(
            ctx, 102, stopIntent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = Intent(ctx, MainActivity::class.java)
        val openAppPendingIntent = android.app.PendingIntent.getActivity(
            ctx, 100, openAppIntent, android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIcon = if (isPlaying.value) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseTitle = if (isPlaying.value) "Pause" else "Play"

        val mediaStyle = android.app.Notification.MediaStyle()
            .setShowActionsInCompactView(0, 1, 2)
        session?.sessionToken?.let { mediaStyle.setMediaSession(it) }

        val builder = android.app.Notification.Builder(ctx, AUDIO_CH_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(displayTitle)
            .setContentText(displaySubtitle)
            .setOngoing(isPlaying.value)
            .setDeleteIntent(stopPendingIntent)
            .setContentIntent(openAppPendingIntent)
            .addAction(android.app.Notification.Action.Builder(android.R.drawable.ic_media_previous, "Previous", prevPendingIntent).build())
            .addAction(android.app.Notification.Action.Builder(playPauseIcon, playPauseTitle, togglePendingIntent).build())
            .addAction(android.app.Notification.Action.Builder(android.R.drawable.ic_media_next, "Next", nextPendingIntent).build())
            .setStyle(mediaStyle)

        currentCoverArt.value?.let { bmp ->
            builder.setLargeIcon(bmp)
        }

        return builder.build()
    }

    private fun updateNotification() {
        val ctx = appContext ?: return
        val notif = buildNotification(ctx)
        if (notif != null) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            nm?.notify(AUDIO_NOTIF_ID, notif)
        } else {
            cancelNotification(ctx)
        }
    }

    private fun cancelNotification(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
        nm?.cancel(AUDIO_NOTIF_ID)
    }

    fun play(context: Context, item: DownloadedItem) {
        stop()
        appContext = context.applicationContext
        if (!requestFocus(context)) return
        try {
            val mp = MediaPlayer().apply {
                setDataSource(context, item.uri)
                prepare()
                start()
            }
            mediaPlayer = mp
            currentItem.value = item
            currentCoverArt.value = getAudioCoverArt(context, item)
            val localLyrics = SyncedLyricsParser.loadForItem(context, item)
            syncedLyrics.value = localLyrics
            showLyricsPopup.value = true
            isPlaying.value = true
            duration.value = mp.duration.toLong().coerceAtLeast(0L)

            if (localLyrics.isEmpty()) {
                scope.launch(Dispatchers.IO) {
                    val onlineLyrics = OnlineLyricsProvider.fetchSyncedLyrics(context, item)
                    if (onlineLyrics.isNotEmpty() && currentItem.value?.id == item.id) {
                        syncedLyrics.value = onlineLyrics
                        showLyricsPopup.value = true
                    }
                }
            }

            runCatching {
                context.startForegroundService(Intent(context, AudioPlayerService::class.java))
            }

            updateNotification()
            startProgressTracker()

            mp.setOnCompletionListener {
                isPlaying.value = false
                currentPosition.value = duration.value
                abandonFocus()
                appContext?.let { cancelNotification(it) }
            }
        } catch (_: Exception) {
            Toast.makeText(context, "Cannot play audio file", Toast.LENGTH_SHORT).show()
        }
    }

    fun pause() {
        val mp = mediaPlayer ?: return
        if (mp.isPlaying) {
            mp.pause()
            isPlaying.value = false
            updateNotification()
        }
    }

    fun togglePlayPause() {
        val mp = mediaPlayer ?: return
        if (mp.isPlaying) {
            mp.pause()
            isPlaying.value = false
        } else {
            mp.start()
            isPlaying.value = true
        }
        updateNotification()
    }

    fun seekTo(positionMs: Long) {
        val mp = mediaPlayer ?: return
        mp.seekTo(positionMs.toInt())
        currentPosition.value = positionMs
        updateNotification()
    }

    fun stop() {
        progressJob?.cancel()
        abandonFocus()
        mediaSession?.let {
            runCatching {
                it.isActive = false
                it.release()
            }
        }
        mediaSession = null
        appContext?.let { cancelNotification(it) }
        mediaPlayer?.let {
            runCatching { if (it.isPlaying) it.stop() }
            runCatching { it.release() }
        }
        mediaPlayer = null
        isPlaying.value = false
        currentPosition.value = 0L
        duration.value = 0L
        currentItem.value = null
        currentCoverArt.value = null
        syncedLyrics.value = emptyList()
    }

    private fun startProgressTracker() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isPlaying.value) {
                mediaPlayer?.let { mp ->
                    val pos = mp.currentPosition.toLong()
                    currentPosition.value = pos
                }
                delay(300L)
            }
        }
    }
}

@Composable
fun AudioPlayerBottomBar() {
    val ctx = LocalContext.current
    val item by AudioPlayerController.currentItem.collectAsState()
    val playing by AudioPlayerController.isPlaying.collectAsState()
    val pos by AudioPlayerController.currentPosition.collectAsState()
    val dur by AudioPlayerController.duration.collectAsState()
    val coverArt by AudioPlayerController.currentCoverArt.collectAsState()
    val lyricsLines by AudioPlayerController.syncedLyrics.collectAsState()
    val showLyricsPopup by AudioPlayerController.showLyricsPopup.collectAsState()

    val activeIndex = remember(lyricsLines, pos) {
        if (lyricsLines.isEmpty()) -1
        else lyricsLines.indexOfLast { pos >= it.timeMs }
    }

    val activeLyric = remember(activeIndex, lyricsLines) {
        if (activeIndex >= 0 && activeIndex < lyricsLines.size) lyricsLines[activeIndex].text else null
    }

    item?.let { track ->
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Attached above audio control - Redesigned Theme-Matching Lyrics Banner
            if (lyricsLines.isNotEmpty() && showLyricsPopup) {
                var showCloseBtn by remember { mutableStateOf(false) }
                val uiState by Manager.ui.collectAsState()
                val isAmoled = uiState.isAmoledLyrics

                val cardBg = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh
                val cardText = if (isAmoled) Color.White else MaterialTheme.colorScheme.onSurface
                val accentColor = if (isAmoled) Color(0xFF80D8FF) else MaterialTheme.colorScheme.primary
                val cardBorder = if (isAmoled)
                    BorderStroke(1.dp, Color(0xFF333333))
                else
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clickable { showCloseBtn = !showCloseBtn },
                    shape = RoundedCornerShape(20.dp),
                    color = cardBg,
                    border = cardBorder,
                    tonalElevation = 6.dp,
                    shadowElevation = 6.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = accentColor.copy(alpha = 0.15f),
                            modifier = Modifier.size(32.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.Lyrics,
                                    contentDescription = null,
                                    tint = accentColor,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        // Smooth line-by-line slide transition with marquee for long lines
                        AnimatedContent(
                            targetState = activeLyric ?: "♪ Listening for lyrics…",
                            transitionSpec = {
                                fadeIn(animationSpec = tween(220)) + slideInVertically { height -> height / 2 } togetherWith
                                fadeOut(animationSpec = tween(180)) + slideOutVertically { height -> -height / 2 }
                            },
                            label = "LyricAnimation",
                            modifier = Modifier.weight(1f, fill = true)
                        ) { lyric ->
                            Text(
                                text = lyric,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (activeLyric != null) cardText else cardText.copy(alpha = 0.6f),
                                maxLines = 1,
                                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                            )
                        }

                        // Close (X) button ONLY appears when user taps on the lyrics shower
                        if (showCloseBtn) {
                            IconButton(
                                onClick = {
                                    AudioPlayerController.toggleLyricsPopup()
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Hide Lyrics",
                                    modifier = Modifier.size(16.dp),
                                    tint = cardText.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }

            // Enlarged Audio Control Box
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { _, dragAmount ->
                            if (dragAmount > 25f) {
                                AudioPlayerController.pause()
                                AudioPlayerController.stop()
                            }
                        }
                    },
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 10.dp,
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                if (coverArt != null) {
                                    androidx.compose.foundation.Image(
                                        bitmap = coverArt!!.asImageBitmap(),
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    AsyncImage(
                                        model = track.uri,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop,
                                        error = rememberVectorPainter(Icons.Default.Audiotrack)
                                    )
                                }
                            }
                        }

                        Column(
                            modifier = Modifier.weight(1f, fill = true),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = track.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                softWrap = false
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = "${formatMs(pos)} / ${formatMs(dur)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                softWrap = false
                            )
                        }

                        IconButton(
                            onClick = { AudioPlayerController.playPrevious(ctx) },
                            modifier = Modifier.size(42.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.SkipPrevious,
                                contentDescription = "Previous Track",
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        IconButton(
                            onClick = { AudioPlayerController.togglePlayPause() },
                            modifier = Modifier.size(42.dp)
                        ) {
                            Icon(
                                imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (playing) "Pause" else "Play",
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        IconButton(
                            onClick = { AudioPlayerController.playNext(ctx) },
                            modifier = Modifier.size(42.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.SkipNext,
                                contentDescription = "Next Track",
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    if (dur > 0L) {
                        Spacer(Modifier.height(6.dp))
                        Slider(
                            value = pos.coerceAtMost(dur).toFloat(),
                            onValueChange = { AudioPlayerController.seekTo(it.toLong()) },
                            valueRange = 0f..dur.toFloat(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(22.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerDialog(
    item: DownloadedItem,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current

    val exoPlayer = remember(item.uri) {
        val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        ExoPlayer.Builder(ctx)
            .setAudioAttributes(audioAttributes, true)
            .build().apply {
                setMediaItem(MediaItem.fromUri(item.uri))
                prepare()
                playWhenReady = true
            }
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.release()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            color = Color.Black
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    Row {
                        IconButton(onClick = { DownloadHistory.share(ctx, item) }) {
                            Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.White)
                        }
                        IconButton(onClick = { DownloadHistory.open(ctx, item) }) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open External", tint = Color.White)
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                        }
                    }
                }

                // Modern Media3 Player View Area
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    AndroidView(
                        factory = { context ->
                            PlayerView(context).apply {
                                player = exoPlayer
                                useController = true
                                keepScreenOn = true
                                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
fun PhotoViewerDialog(
    item: DownloadedItem,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            color = Color.Black
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    Row {
                        IconButton(onClick = { DownloadHistory.share(ctx, item) }) {
                            Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.White)
                        }
                        IconButton(onClick = { DownloadHistory.open(ctx, item) }) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open External", tint = Color.White)
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                        }
                    }
                }

                // Fullscreen Image View Area
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = item.uri,
                        contentDescription = item.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
