package com.base.realtime.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        try {
            val action = intent?.action ?: return
            if (action !in ACTIONS) return
            val reason = if (
                action.contains("BOOT", ignoreCase = true) ||
                action.contains("QUICKBOOT") ||
                action == Intent.ACTION_LOCKED_BOOT_COMPLETED
            ) {
                "boot"
            } else {
                "system"
            }
            RestartCoordinator.request(context, reason)
        } catch (_: Throwable) {
        }
    }

    private companion object {
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_LOCALE_CHANGED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            "android.intent.action.REBOOT",
            "miui.intent.action.BOOT_COMPLETED",
        )
    }
}
