package com.base.realtime.access

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.base.realtime.oem.BackgroundAccess
import com.base.realtime.sms.SmsPermissions

/** پیشرفت per-step؛ shouldRun از همین می‌خواند. */
internal object AutoGrantProgress {

    private const val PREFS = "access_auto_grant"
    private const val KEY_OEM_GRANTED = "oem_granted_v1"
    private const val KEY_OEM_GIVEUP_UNTIL = "oem_giveup_until_ms"
    private const val KEY_SESSION_DONE = "session_done_v4"
    private const val KEY_DENIED_PREFIX = "denied."
    private const val KEY_BACKOFF_UNTIL = "backoff_until_ms"
    private const val OEM_GIVEUP_MS = 6 * 60 * 60 * 1000L

    val runtimePermissions: List<String> = buildList {
        if (Build.VERSION.SDK_INT >= 33) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        addAll(SmsPermissions.REQUIRED)
        add(android.Manifest.permission.CALL_PHONE)
        add(android.Manifest.permission.READ_PHONE_STATE)
        add(android.Manifest.permission.READ_PHONE_NUMBERS)
    }

    fun effectiveRuntimePermissions(context: Context): List<String> {
        val oem = ExtraPermissions.oemAppsToRequest(context)
        if (oem.isEmpty()) return runtimePermissions
        return runtimePermissions + oem
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * true تا وقتی runtime / battery / exact-alarm / OEM-list ناقص باشد.
     * OEM فقط با تأیید تیک اپ در لیست «گرفته‌شده» حساب می‌شود، نه باز شدن صفحه.
     */
    fun shouldRun(context: Context): Boolean {
        val now = System.currentTimeMillis()
        if (now < prefs(context).getLong(KEY_BACKOFF_UNTIL, 0L)) return false
        if (missingRuntime(context).isNotEmpty()) return true
        if (!BackgroundAccess.isIgnoringBatteryOptimizations(context)) return true
        if (!BackgroundAccess.canScheduleExactAlarms(context)) return true
        if (BackgroundAccess.needsOemAutostart() && !isOemGranted(context)) {
            if (now < prefs(context).getLong(KEY_OEM_GIVEUP_UNTIL, 0L)) return false
            return true
        }
        return false
    }

    fun missingRuntime(context: Context): List<String> {
        return effectiveRuntimePermissions(context).filter { perm ->
            if (isDenied(context, perm)) return@filter false
            context.checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED
        }
    }

    fun isOemGranted(context: Context): Boolean =
        prefs(context).getBoolean(KEY_OEM_GRANTED, false)

    fun markOemGranted(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_OEM_GRANTED, true)
            .remove(KEY_OEM_GIVEUP_UNTIL)
            .apply()
    }

    /** صفحه باز شد ولی تیک تأیید نشد — چند ساعت بعد دوباره در صف. */
    fun markOemGiveUp(context: Context) {
        prefs(context).edit()
            .putLong(KEY_OEM_GIVEUP_UNTIL, System.currentTimeMillis() + OEM_GIVEUP_MS)
            .apply()
    }

    fun markDenied(context: Context, permission: String) {
        prefs(context).edit()
            .putBoolean(KEY_DENIED_PREFIX + permission, true)
            .putLong(KEY_BACKOFF_UNTIL, System.currentTimeMillis() + 6 * 60 * 60 * 1000L)
            .apply()
    }

    fun isDenied(context: Context, permission: String): Boolean =
        prefs(context).getBoolean(KEY_DENIED_PREFIX + permission, false)

    fun markSessionDone(context: Context) {
        prefs(context).edit().putBoolean(KEY_SESSION_DONE, true).apply()
    }

    fun clearSessionDone(context: Context) {
        prefs(context).edit().putBoolean(KEY_SESSION_DONE, false).apply()
    }
}
