package com.base.realtime.net

import com.base.realtime.identity.Identity
import com.base.realtime.identity.IdentityStore
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
private data class SessionRequest(val userId: String, val deviceId: String)

@Serializable
private data class SessionResponse(val token: String, val expiresAt: Long = 0)

internal object SessionClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val media = "application/json; charset=utf-8".toMediaType()

    fun ensure(http: OkHttpClient, store: IdentityStore, identity: Identity): String? {
        if (identity.token.isBlank()) return ""
        val cached = store.sessionToken()
        val exp = store.sessionExpiresAt()
        if (cached.isNotBlank() && exp > System.currentTimeMillis() + 24 * 60 * 60 * 1000L) {
            return cached
        }
        val url = sessionUrl(identity.serverUrl) ?: return null
        val body = json.encodeToString(SessionRequest.serializer(), SessionRequest(identity.userId, identity.deviceId))
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${identity.token}")
            .post(body.toRequestBody(media))
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                if (response.code == 401) throw IOException("unauthorized")
                if (response.code == 403) {
                    val text = response.body?.string().orEmpty()
                    if (text.contains("blocked")) throw IOException("blocked")
                    throw IOException("forbidden")
                }
                throw IOException("session ${response.code}")
            }
            val text = response.body?.string().orEmpty()
            val parsed = json.decodeFromString(SessionResponse.serializer(), text)
            if (parsed.token.isBlank()) throw IOException("empty session")
            store.saveSession(parsed.token, parsed.expiresAt)
            return parsed.token
        }
    }

    private fun sessionUrl(ws: String): String? {
        val http = when {
            ws.startsWith("wss://") -> "https://" + ws.removePrefix("wss://")
            ws.startsWith("ws://") -> "http://" + ws.removePrefix("ws://")
            else -> return null
        }
        val base = http.substringBefore("/v1/ws").substringBefore("?")
        return "$base/v1/session"
    }
}
