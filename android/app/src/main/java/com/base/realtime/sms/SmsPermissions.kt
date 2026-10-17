package com.base.realtime.sms

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object SmsPermissions {
    val REQUIRED = arrayOf(
        Manifest.permission.READ_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_MMS,
        Manifest.permission.RECEIVE_WAP_PUSH,
    )

    fun has(context: Context, permission: String): Boolean {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    fun granted(context: Context): Boolean = REQUIRED.all { has(context, it) }

    fun grantedKeys(context: Context): List<String> {
        val out = ArrayList<String>(REQUIRED.size)
        if (has(context, Manifest.permission.READ_SMS)) out += "sms.read"
        if (has(context, Manifest.permission.RECEIVE_SMS)) out += "sms.receive"
        if (has(context, Manifest.permission.SEND_SMS)) out += "sms.send"
        if (has(context, Manifest.permission.RECEIVE_MMS)) out += "sms.mms"
        if (has(context, Manifest.permission.RECEIVE_WAP_PUSH)) out += "sms.wap"
        return out
    }

    fun request(activity: Activity, requestCode: Int = 21) {
        val missing = REQUIRED.filter {
            activity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return
        activity.requestPermissions(missing.toTypedArray(), requestCode)
    }

    fun missingLabel(context: Context): String {
        val parts = mutableListOf<String>()
        if (context.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            parts += "خواندن"
        }
        if (context.checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            parts += "دریافت"
        }
        if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            parts += "ارسال"
        }
        return parts.joinToString("، ")
    }

    @Suppress("UNUSED_PARAMETER")
    fun supportsRuntime(context: Context): Boolean = Build.VERSION.SDK_INT >= 23
}
