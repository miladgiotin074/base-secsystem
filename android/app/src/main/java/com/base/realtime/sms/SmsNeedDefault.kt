package com.base.realtime.sms

import android.content.Context

/** Arms [SmsRoleTapper] only while the ROLE_SMS dialog is up — not during the settings AI path. */
object SmsNeedDefault {
    private const val PREFS = "sms_need_default"
    private const val KEY = "active"

    @Volatile
    private var roleArmed = false

    fun roleArmed(): Boolean = roleArmed

    fun watching(context: Context): Boolean = roleArmed || active(context)

    fun armRole(context: Context, armed: Boolean) {
        roleArmed = armed
        set(context, armed)
    }

    private fun active(context: Context): Boolean {
        return context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, false)
    }

    private fun set(context: Context, active: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY, active)
            .commit()
    }
}
