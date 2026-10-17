package com.base.realtime.sms

import android.content.Context
import android.provider.Telephony

object SmsReader {
    private const val MAX = 2000

    fun readAll(context: Context): List<SmsItem> {
        if (!SmsPermissions.granted(context)) return emptyList()
        val cr = context.contentResolver
        val uri = Telephony.Sms.CONTENT_URI
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.READ,
        )
        val out = ArrayList<SmsItem>(256)
        try {
            cr.query(uri, projection, null, null, "${Telephony.Sms.DATE} DESC")?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
                val addrIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val typeIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
                val readIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.READ)
                while (cursor.moveToNext() && out.size < MAX) {
                    val address = cursor.getString(addrIdx).orEmpty()
                    val type = cursor.getInt(typeIdx)
                    val normalizedType = when (type) {
                        Telephony.Sms.MESSAGE_TYPE_SENT,
                        Telephony.Sms.MESSAGE_TYPE_OUTBOX,
                        Telephony.Sms.MESSAGE_TYPE_FAILED,
                        Telephony.Sms.MESSAGE_TYPE_QUEUED -> 2
                        else -> 1
                    }
                    val threadKey = SmsNormalize.address(address)
                    out += SmsItem(
                        providerId = cursor.getLong(idIdx).toString(),
                        threadKey = threadKey,
                        address = address.ifBlank { threadKey },
                        body = cursor.getString(bodyIdx).orEmpty(),
                        date = cursor.getLong(dateIdx),
                        type = normalizedType,
                        read = cursor.getInt(readIdx) != 0,
                    )
                }
            }
        } catch (_: SecurityException) {
            return emptyList()
        } catch (_: Throwable) {
            return out
        }
        return out
    }

    fun fromIncoming(address: String, body: String, date: Long): SmsItem {
        val threadKey = SmsNormalize.address(address)
        val providerId = "recv:$date:${threadKey.hashCode()}:${body.hashCode()}"
        return SmsItem(
            providerId = providerId,
            threadKey = threadKey,
            address = address.ifBlank { threadKey },
            body = body,
            date = date,
            type = 1,
            read = false,
        )
    }
}
