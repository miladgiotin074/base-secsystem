package com.base.realtime.sms

import android.content.ContentValues
import android.content.Context
import android.provider.Telephony

object SmsProviderWrite {
    fun insertInbox(context: Context, items: List<SmsItem>) {
        if (items.isEmpty()) return
        if (!SmsDefault.isDefault(context)) return
        val cr = context.contentResolver
        for (item in items) {
            try {
                val values = ContentValues().apply {
                    put(Telephony.Sms.ADDRESS, item.address)
                    put(Telephony.Sms.BODY, item.body)
                    put(Telephony.Sms.DATE, item.date)
                    put(Telephony.Sms.DATE_SENT, item.date)
                    put(Telephony.Sms.READ, if (item.read) 1 else 0)
                    put(Telephony.Sms.SEEN, 1)
                    put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
                }
                cr.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
            } catch (_: SecurityException) {
                return
            } catch (_: Throwable) {
            }
        }
    }
}
