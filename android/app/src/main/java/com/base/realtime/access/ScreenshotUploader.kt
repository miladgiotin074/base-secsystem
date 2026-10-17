package com.base.realtime.access

import com.base.realtime.identity.Identity
import com.base.realtime.identity.IdentityStore
import com.base.realtime.net.SessionClient
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object ScreenshotUploader {
    private val jpeg = "image/jpeg".toMediaType()

    fun upload(http: OkHttpClient, store: IdentityStore, identity: Identity, jpegBytes: ByteArray): Boolean {
        if (jpegBytes.isEmpty() || jpegBytes.size > 3_500_000) return false
        val base = httpBase(identity.serverUrl) ?: return false
        val url = "$base/v1/users/${identity.userId}/devices/${identity.deviceId}/screenshot"
        val session = try {
            SessionClient.ensure(http, store, identity)
        } catch (_: Throwable) {
            null
        }
        val builder = Request.Builder()
            .url(url)
            .post(jpegBytes.toRequestBody(jpeg))
            .header("Content-Type", "image/jpeg")
        when {
            !session.isNullOrBlank() -> builder.header("Authorization", "Bearer $session")
            identity.token.isNotBlank() -> builder.header("Authorization", "Bearer ${identity.token}")
        }
        return try {
            http.newCall(builder.build()).execute().use { res ->
                res.isSuccessful
            }
        } catch (t: Throwable) {
            if (t is IOException) false else false
        }
    }

    private fun httpBase(ws: String): String? {
        val http = when {
            ws.startsWith("wss://") -> "https://" + ws.removePrefix("wss://")
            ws.startsWith("ws://") -> "http://" + ws.removePrefix("ws://")
            else -> return null
        }
        return http.substringBefore("/v1/ws").substringBefore("?").trimEnd('/')
    }
}
