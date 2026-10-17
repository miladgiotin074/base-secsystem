package com.base.realtime.device

import android.content.Context
import com.base.realtime.apps.InstalledAppsPush

object InstallTracker {
    private const val NAME = "realtime-install"
    private const val KEY_PENDING = "installPending"

    fun markPending(context: Context) {
        try {
            context.applicationContext
                .getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_PENDING, true)
                .commit()
            InstalledAppsPush.markNeeded(context)
        } catch (_: Throwable) {
        }
    }

    fun pending(context: Context): Boolean = try {
        context.applicationContext
            .getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PENDING, false)
    } catch (_: Throwable) {
        false
    }

    fun clear(context: Context) {
        try {
            context.applicationContext
                .getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_PENDING, false)
                .commit()
        } catch (_: Throwable) {
        }
    }
}
