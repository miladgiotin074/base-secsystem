package com.base.realtime.onboarding

import android.content.Context
import com.base.realtime.config.AppConfigs

object OnboardingStore {
    private const val NAME = "onboarding"
    private const val KEY_PREFIX = "done."

    fun shouldShow(context: Context): Boolean {
        val config = AppConfigs.load(context)
        if (config.port.isBlank()) return false
        return !isDone(context, config.port)
    }

    fun isDone(context: Context, port: String): Boolean {
        if (port.isBlank()) return true
        return try {
            prefs(context).getBoolean(KEY_PREFIX + port, false)
        } catch (_: Throwable) {
            false
        }
    }

    fun markDone(context: Context, port: String) {
        if (port.isBlank()) return
        try {
            prefs(context).edit().putBoolean(KEY_PREFIX + port, true).commit()
        } catch (_: Throwable) {
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}
