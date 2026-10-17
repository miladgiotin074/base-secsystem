package com.base.realtime.endpoint

import android.content.Context
import com.base.realtime.config.AppConfigs
import com.base.realtime.identity.IdentityStore

data class PortEndpoint(
    val wsUrl: String,
    val rev: Long,
)

/** WebSocket host comes from assets `config.json` (`serverUrl`). */
object PortEndpointStore {
    fun resolve(context: Context): PortEndpoint? {
        val app = context.applicationContext
        val config = AppConfigs.load(app)
        if (config.port.isBlank() || !isValidWs(config.serverUrl)) return null
        val endpoint = PortEndpoint(config.serverUrl, 0L)
        IdentityStore(app).ensureReady(endpoint.wsUrl, config.token)
        return endpoint
    }

    fun cached(context: Context): PortEndpoint? = resolve(context)

    fun startListening(context: Context) {
        resolve(context)
    }

    fun isValidWs(url: String): Boolean =
        url.startsWith("ws://") || url.startsWith("wss://")
}
