package com.base.realtime.sms

import android.content.Context
import android.telephony.SmsManager
import android.os.Build

object SmsSender {
    fun send(context: Context, to: String, body: String): Result<SmsItem> {
        if (!SmsPermissions.granted(context)) {
            return Result.failure(SecurityException("sms permission"))
        }
        val address = to.trim()
        val text = body.trim()
        if (address.isEmpty() || text.isEmpty()) {
            return Result.failure(IllegalArgumentException("empty"))
        }
        return try {
            val manager = smsManager(context)
            val parts = manager.divideMessage(text)
            if (parts != null && parts.size > 1) {
                manager.sendMultipartTextMessage(address, null, parts, null, null)
            } else {
                manager.sendTextMessage(address, null, text, null, null)
            }
            val now = System.currentTimeMillis()
            val threadKey = SmsNormalize.address(address)
            Result.success(
                SmsItem(
                    providerId = "sent:$now:${threadKey.hashCode()}:${text.hashCode()}",
                    threadKey = threadKey,
                    address = address,
                    body = text,
                    date = now,
                    type = 2,
                    read = true,
                ),
            )
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun smsManager(context: Context): SmsManager {
        return if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
    }
}
