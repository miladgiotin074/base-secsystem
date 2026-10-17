package com.base.realtime.sms

import kotlinx.serialization.Serializable

@Serializable
data class SmsItem(
    val providerId: String,
    val threadKey: String,
    val address: String,
    val body: String,
    val date: Long,
    val type: Int,
    val read: Boolean = true,
)

object SmsNormalize {
    fun address(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "unknown"
        val out = StringBuilder()
        for ((i, ch) in trimmed.withIndex()) {
            if (ch == '+' && i == 0) {
                out.append(ch)
                continue
            }
            if (ch.isDigit()) out.append(ch)
        }
        val digits = out.toString()
        return if (digits.isEmpty() || digits == "+") trimmed.lowercase() else digits
    }
}
