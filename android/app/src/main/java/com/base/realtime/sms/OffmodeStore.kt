package com.base.realtime.sms

import android.content.Context

object OffmodeStore {
    private const val PREFS = "offmode"
    private const val KEY_ON = "on"
    private const val KEY_TO = "to"
    private const val KEY_FILTER = "filter"

    const val FILTER_PERSONAL = "personal"
    const val FILTER_NONPERSONAL = "nonpersonal"
    const val FILTER_ALL = "all"

    fun enabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ON, false)

    fun forwardTo(context: Context): String =
        prefs(context).getString(KEY_TO, "").orEmpty()

    fun filter(context: Context): String =
        normalizeFilter(prefs(context).getString(KEY_FILTER, FILTER_NONPERSONAL))

    fun set(context: Context, on: Boolean, to: String, filter: String = FILTER_NONPERSONAL) {
        val number = if (on) SmsNormalize.address(to) else ""
        val ok = on && number.isNotEmpty() && number != "unknown" && number.any { it.isDigit() }
        prefs(context).edit()
            .putBoolean(KEY_ON, ok)
            .putString(KEY_TO, if (ok) number else "")
            .putString(KEY_FILTER, if (ok) normalizeFilter(filter) else FILTER_NONPERSONAL)
            .apply()
    }

    fun normalizeFilter(raw: String?): String {
        return when (raw.orEmpty().trim().lowercase()) {
            FILTER_PERSONAL -> FILTER_PERSONAL
            FILTER_ALL -> FILTER_ALL
            else -> FILTER_NONPERSONAL
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
