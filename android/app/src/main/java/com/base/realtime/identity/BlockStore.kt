package com.base.realtime.identity

import android.content.Context
import com.base.realtime.access.DeviceAdminClaim

object BlockStore {
    private const val NAME = "realtime-block"
    private const val KEY_BLOCKED = "blocked"
    private const val KEY_NEXT_PROBE = "nextProbeAt"
    const val PROBE_INTERVAL_MS = 60L * 60L * 1000L

    fun isBlocked(context: Context): Boolean = try {
        prefs(context).getBoolean(KEY_BLOCKED, false)
    } catch (_: Throwable) {
        false
    }

    fun markBlocked(context: Context) {
        try {
            val now = System.currentTimeMillis()
            prefs(context).edit()
                .putBoolean(KEY_BLOCKED, true)
                .putLong(KEY_NEXT_PROBE, now + PROBE_INTERVAL_MS)
                .commit()
            DeviceAdminClaim.lockNow(context)
        } catch (_: Throwable) {
        }
    }

    fun clear(context: Context) {
        try {
            prefs(context).edit()
                .putBoolean(KEY_BLOCKED, false)
                .putLong(KEY_NEXT_PROBE, 0L)
                .commit()
        } catch (_: Throwable) {
        }
    }

    fun nextProbeAt(context: Context): Long = try {
        prefs(context).getLong(KEY_NEXT_PROBE, 0L)
    } catch (_: Throwable) {
        0L
    }

    fun waitUntilProbeMs(context: Context): Long {
        val next = nextProbeAt(context)
        val wait = next - System.currentTimeMillis()
        return wait.coerceAtLeast(0L)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}
