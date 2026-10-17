package com.base.realtime.access

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

object AccessibilityGate {
    fun openSettings(context: Context) {
        if (context is Activity) {
            AccessibilityGuideActivity.open(context)
            return
        }
        val opened = AccessibilityGrant.open(context)
        if (opened != AccessibilityGrant.Opened.NONE) return
        try {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Throwable) {
        }
    }

    fun ready(context: Context): Boolean {
        return AgentAccessibilityService.isEnabled(context) && AgentAccessibilityService.get() != null
    }

    suspend fun captureJpeg(context: Context): ByteArray? {
        val service = AgentAccessibilityService.get()
        if (service == null) {
            if (!AgentAccessibilityService.isEnabled(context)) return null
            return null
        }
        return suspendCancellableCoroutine { cont ->
            service.takeJpeg { bytes ->
                if (cont.isActive) cont.resume(bytes)
            }
        }
    }
}
