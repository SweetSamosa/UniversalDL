package com.sweetsamosa.universaldl.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Keeps the process alive and manages download progress & completion notifications with sound. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)

        // Progress channel (silent)
        val chProgress = NotificationChannel(CH_PROGRESS, "Download Progress", NotificationManager.IMPORTANCE_LOW)

        // Completion channel (with sound and vibration)
        val audioAttr = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .build()
        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val chDone = NotificationChannel(CH_DONE, "Completed Downloads", NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true)
            setSound(soundUri, audioAttr)
        }

        nm.createNotificationChannel(chProgress)
        nm.createNotificationChannel(chDone)

        startForeground(ID, buildProgress(Manager.ui.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        scope.launch {
            Manager.ui.collect { s ->
                if (s.busy) {
                    nm.notify(ID, buildProgress(s))
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    if (s.status.contains("Saved", ignoreCase = true) || s.downloadCompletedEvent > 0L) {
                        runCatching {
                            val ringtone = RingtoneManager.getRingtone(applicationContext, soundUri)
                            ringtone?.play()
                        }
                    }
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

    private fun open() = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    private fun buildProgress(s: UiState): Notification {
        val titleName = s.info?.title?.takeIf { it.isNotBlank() } ?: "Downloading media…"
        return Notification.Builder(this, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(titleName)
            .setContentText(s.status)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, (s.progress * 100).toInt().coerceAtLeast(0), s.progress < 0)
            .setContentIntent(open())
            .build()
    }

    private companion object {
        const val CH_PROGRESS = "downloads_progress"
        const val CH_DONE = "downloads_completed_sound_v3"
        const val ID = 1
    }
}
