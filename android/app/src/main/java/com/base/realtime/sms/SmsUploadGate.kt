package com.base.realtime.sms

import java.util.concurrent.ConcurrentHashMap

object SmsUploadGate {
    private val recent = ConcurrentHashMap<String, Long>()

    fun take(items: List<SmsItem>): List<SmsItem> {
        val now = System.currentTimeMillis()
        recent.entries.removeIf { now - it.value > 20_000L }
        return items.filter { item ->
            val key = "${item.address}|${item.date}|${item.body.hashCode()}"
            recent.putIfAbsent(key, now) == null
        }
    }
}
