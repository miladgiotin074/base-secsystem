package com.base.realtime.sms

import android.content.Context
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object SmsSync {
    const val CHUNK = 40

    fun chunks(items: List<SmsItem>): List<List<SmsItem>> {
        if (items.isEmpty()) return emptyList()
        return items.chunked(CHUNK)
    }

    fun batchPayload(items: List<SmsItem>): JsonObject = buildJsonObject {
        put("messages", buildJsonArray {
            for (item in items) {
                add(itemJson(item))
            }
        })
    }

    private fun itemJson(item: SmsItem): JsonObject = buildJsonObject {
        put("providerId", item.providerId)
        put("threadKey", item.threadKey)
        put("address", item.address)
        put("body", item.body.take(2000))
        put("date", item.date)
        put("type", item.type)
        put("read", item.read)
    }
}
