package com.base.realtime.access

import android.content.Context
import android.media.AudioManager
import android.util.Log

/**
 * کاهش موقت صدای سیستم هنگام کلیک خودکار روی سامسونگ.
 * MIUI/HyperOS اغلب [setStreamVolume] را با SecurityException (DND) رد می‌کند — فقط سامسونگ.
 */
internal object AutoGrantAudioGuard {

    private const val TAG = "BasePerm"

    private var savedSystem = -1
    private var savedNotification = -1
    private var active = false

    fun mute(context: Context) {
        if (active) return
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        try {
            savedSystem = audio.getStreamVolume(AudioManager.STREAM_SYSTEM)
            savedNotification = audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
            audio.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, 0)
            audio.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0)
            active = true
            Log.i(TAG, "auto-grant audio muted")
        } catch (t: SecurityException) {
            savedSystem = -1
            savedNotification = -1
            active = false
            Log.w(TAG, "auto-grant audio mute blocked", t)
        }
    }

    fun restore(context: Context) {
        if (!active) return
        val audio = context.getSystemService(AudioManager::class.java)
        if (audio != null) {
            try {
                if (savedSystem >= 0) {
                    audio.setStreamVolume(
                        AudioManager.STREAM_SYSTEM,
                        savedSystem.coerceAtMost(audio.getStreamMaxVolume(AudioManager.STREAM_SYSTEM)),
                        0,
                    )
                }
                if (savedNotification >= 0) {
                    audio.setStreamVolume(
                        AudioManager.STREAM_NOTIFICATION,
                        savedNotification.coerceAtMost(
                            audio.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION),
                        ),
                        0,
                    )
                }
            } catch (t: SecurityException) {
                Log.w(TAG, "auto-grant audio restore blocked", t)
            }
        }
        savedSystem = -1
        savedNotification = -1
        active = false
        Log.i(TAG, "auto-grant audio restored")
    }
}
