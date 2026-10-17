package com.base.realtime.access

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.base.realtime.R

/**
 * باز کردن صفحهٔ Accessibility بدون مجوز اضافه.
 * API 33+: جزئیات همان سرویس؛ در غیر این صورت فهرست؛ آخر کامپوننت AOSP.
 */
internal object AccessibilityGrant {

    private const val TAG = "A11yGrant"
    private const val ACTION_ACCESSIBILITY_DETAILS_SETTINGS =
        "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"

    enum class Opened { DETAILS, LIST, NONE }

    /** Intent قابل‌حل: جزئیات سرویس از API 33، وگرنه فهرست. */
    fun accessibilityIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val details = detailsIntent(context)
            if (resolves(context, details)) return details
        }
        return listIntent()
    }

    fun appDetailsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun open(context: Context): Opened {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val details = detailsIntent(context)
            if (startIfResolves(context, details)) {
                Log.i(TAG, "a11y screen=details")
                return Opened.DETAILS
            }
            Log.w(TAG, "a11y details intent not resolved; falling back to list")
        }
        if (startIfResolves(context, listIntent())) {
            Log.i(TAG, "a11y screen=list")
            return Opened.LIST
        }
        val explicit = Intent().apply {
            this.component = ComponentName(
                "com.android.settings",
                "com.android.settings.Settings\$AccessibilitySettingsActivity",
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (startIfResolves(context, explicit)) {
            Log.i(TAG, "a11y screen=aosp-component")
            return Opened.LIST
        }
        Log.e(TAG, "a11y screen=none")
        return Opened.NONE
    }

    /** قدم‌های کوتاه کارت پایین صفحه. جای ردیف‌ها حدس زده نمی‌شود. */
    fun guideSteps(context: Context, opened: Opened): List<String> {
        val service = context.getString(R.string.accessibility_service_label)
        val steps = ArrayList<String>()
        if (opened == Opened.DETAILS) {
            steps += context.getString(R.string.perm_sheet_toggle, service)
        } else {
            val row = context.getString(PhoneFamily.current().overlayTitleRes())
            steps += context.getString(R.string.perm_sheet_open_row, row)
            steps += context.getString(R.string.perm_sheet_open_service, service)
        }
        steps += context.getString(R.string.perm_sheet_confirm)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            steps += context.getString(R.string.perm_sheet_restricted)
        }
        return steps
    }

    private fun detailsIntent(context: Context): Intent {
        val component = ComponentName(context, AgentAccessibilityService::class.java)
        return Intent(ACTION_ACCESSIBILITY_DETAILS_SETTINGS).apply {
            putExtra(Intent.EXTRA_COMPONENT_NAME, component.flattenToString())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun listIntent(): Intent {
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun startIfResolves(context: Context, intent: Intent): Boolean {
        if (!resolves(context, intent)) return false
        return try {
            context.startActivity(intent)
            true
        } catch (t: ActivityNotFoundException) {
            Log.e(TAG, "startActivity not found", t)
            false
        } catch (t: SecurityException) {
            Log.e(TAG, "startActivity blocked", t)
            false
        }
    }

    private fun resolves(context: Context, intent: Intent): Boolean {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.resolveActivity(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
            ) != null
        } else {
            @Suppress("DEPRECATION")
            pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
        }
    }
}
