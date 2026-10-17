package com.base.realtime.sms

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Telephony

object SmsDefault {
    fun isDefault(context: Context): Boolean {
        return try {
            Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
        } catch (_: Throwable) {
            false
        }
    }

    fun requestIntent(context: Context): Intent? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val rm = context.getSystemService(RoleManager::class.java) ?: return null
                if (!rm.isRoleAvailable(RoleManager.ROLE_SMS)) return null
                if (rm.isRoleHeld(RoleManager.ROLE_SMS)) return null
                rm.createRequestRoleIntent(RoleManager.ROLE_SMS)
            } else {
                Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
                    putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName)
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** Opens the system “set as default SMS app” prompt when hide-mode needs write control. */
    fun ensureRequested(context: Context) {
        if (!SmsHideStore.hideIncoming(context)) return
        if (isDefault(context)) return
        val app = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            try {
                app.startActivity(
                    Intent(app, SmsRoleRequestActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                )
            } catch (_: Throwable) {
            }
        }
    }
}
