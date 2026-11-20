package com.sweetsamosa.universaldl.app

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Foreground service for media playback so Android System Quick Settings Media Control tile shows the playing media. */
class AudioPlayerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = AudioPlayerController.buildNotification(this)
        if (notif != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    AudioPlayerController.AUDIO_NOTIF_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(AudioPlayerController.AUDIO_NOTIF_ID, notif)
            }
        }

        scope.launch {
            AudioPlayerController.isPlaying.collect { playing ->
                val currentNotif = AudioPlayerController.buildNotification(this@AudioPlayerService)
                if (currentNotif != null) {
                    val nm = getSystemService(NotificationManager::class.java)
                    nm.notify(AudioPlayerController.AUDIO_NOTIF_ID, currentNotif)
                }
                if (!playing && AudioPlayerController.currentItem.value == null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
