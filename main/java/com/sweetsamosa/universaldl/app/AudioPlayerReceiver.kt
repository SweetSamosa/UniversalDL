package com.sweetsamosa.universaldl.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AudioPlayerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val ctx = context ?: return
        when (intent?.action) {
            ACTION_TOGGLE_PLAY -> AudioPlayerController.togglePlayPause()
            ACTION_STOP -> AudioPlayerController.stop()
            ACTION_PREVIOUS -> AudioPlayerController.playPrevious(ctx)
            ACTION_NEXT -> AudioPlayerController.playNext(ctx)
        }
    }

    companion object {
        const val ACTION_TOGGLE_PLAY = "com.sweetsamosa.universaldl.app.ACTION_TOGGLE_PLAY"
        const val ACTION_STOP = "com.sweetsamosa.universaldl.app.ACTION_STOP"
        const val ACTION_PREVIOUS = "com.sweetsamosa.universaldl.app.ACTION_PREVIOUS"
        const val ACTION_NEXT = "com.sweetsamosa.universaldl.app.ACTION_NEXT"
    }
}
