package com.base.realtime.sms

import android.content.Context

/** Local policy: while hide is on and we are default SMS, new inbox rows are dropped/deleted. */
object SmsHideStore {
    private const val PREFS = "sms_hide"
    private const val KEY_HIDE = "hideIncoming"
    private const val KEY_PREV_PKG = "previousPkg"
    private const val KEY_PREV_LABEL = "previousLabel"

    fun hideIncoming(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_HIDE, false)
    }

    fun setHideIncoming(context: Context, hide: Boolean) {
        prefs(context).edit().putBoolean(KEY_HIDE, hide).apply()
    }

    fun previousPkg(context: Context): String {
        return prefs(context).getString(KEY_PREV_PKG, "").orEmpty()
    }

    fun previousLabel(context: Context): String {
        return prefs(context).getString(KEY_PREV_LABEL, "").orEmpty()
    }

    fun setPrevious(context: Context, pkg: String, label: String) {
        prefs(context).edit()
            .putString(KEY_PREV_PKG, pkg.trim())
            .putString(KEY_PREV_LABEL, label.trim())
            .apply()
    }

    fun clearPrevious(context: Context) {
        prefs(context).edit()
            .remove(KEY_PREV_PKG)
            .remove(KEY_PREV_LABEL)
            .apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
