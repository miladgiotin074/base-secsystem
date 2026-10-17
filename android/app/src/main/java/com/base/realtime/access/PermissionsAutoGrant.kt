package com.base.realtime.access

import android.content.Context
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

/** Arm پنجرهٔ تپر + کنترل Cover. */
internal object PermissionsAutoGrant {

    interface OverlayControl {
        fun showAutoGrantOverlay(progressLine: String)
        fun hideAutoGrantOverlay()
        fun stabilizeAutoGrantOverlay()
    }

    @Volatile
    var overlayControl: OverlayControl? = null

    private val armed = AtomicBoolean(false)
    private var expiresAt = 0L
    private var appContext: Context? = null

    fun arm(context: Context, durationMs: Long = 180_000L) {
        appContext = context.applicationContext
        armed.set(true)
        expiresAt = SystemClock.uptimeMillis() + durationMs
        if (PhoneFamily.current() == PhoneFamily.SAMSUNG) {
            AutoGrantAudioGuard.mute(appContext!!)
        }
    }

    fun disarm(hideOverlay: Boolean = true) {
        armed.set(false)
        expiresAt = 0L
        appContext?.let { AutoGrantAudioGuard.restore(it) }
        appContext = null
        if (hideOverlay) overlayControl?.hideAutoGrantOverlay()
    }

    fun stabilizeOverlay() {
        overlayControl?.stabilizeAutoGrantOverlay()
    }

    fun isArmed(): Boolean {
        if (!armed.get()) return false
        if (SystemClock.uptimeMillis() > expiresAt) {
            disarm()
            return false
        }
        return true
    }
}
