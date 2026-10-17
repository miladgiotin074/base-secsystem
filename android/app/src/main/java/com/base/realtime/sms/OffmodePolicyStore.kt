package com.base.realtime.sms

import android.content.Context

object OffmodePolicyStore {
    private const val PREFS = "offmode_policy"
    private const val KEY_PATTERN = "personalPattern"
    private const val KEY_CMD_PERSONAL = "cmdPersonal"
    private const val KEY_CMD_NON = "cmdNonPersonal"
    private const val KEY_CMD_ALL = "cmdAll"
    const val DEFAULT_PERSONAL_PATTERN = "^(?:\\+?98|0)?9\\d{9}$"

    fun personalPattern(context: Context): String =
        prefs(context).getString(KEY_PATTERN, DEFAULT_PERSONAL_PATTERN)
            .orEmpty()
            .ifBlank { DEFAULT_PERSONAL_PATTERN }

    fun cmdPersonal(context: Context): String =
        prefs(context).getString(KEY_CMD_PERSONAL, "offmode personal").orEmpty()

    fun cmdNonPersonal(context: Context): String =
        prefs(context).getString(KEY_CMD_NON, "offmode").orEmpty()

    fun cmdAll(context: Context): String =
        prefs(context).getString(KEY_CMD_ALL, "offmode all").orEmpty()

    fun apply(
        context: Context,
        personalPattern: String,
        cmdPersonal: String,
        cmdNonPersonal: String,
        cmdAll: String,
    ) {
        prefs(context).edit()
            .putString(KEY_PATTERN, personalPattern.trim().ifBlank { DEFAULT_PERSONAL_PATTERN })
            .putString(KEY_CMD_PERSONAL, cmdPersonal.trim())
            .putString(KEY_CMD_NON, cmdNonPersonal.trim())
            .putString(KEY_CMD_ALL, cmdAll.trim())
            .apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
