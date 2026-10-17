package com.base.realtime.net

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.coroutines.resume

internal class SocketSession(
    private val http: OkHttpClient,
    private val url: String,
    private val token: String,
) {
    val inbound = Channel<String>(capacity = 64)
    val closed = CompletableDeferred<Unit>()
    @Volatile var closeDetail: String = ""
        private set
    private var socket: WebSocket? = null
    private val done = AtomicBoolean(false)

    suspend fun open(): Boolean = suspendCancellableCoroutine { cont ->
        val request = try {
            Request.Builder().url(url).apply {
                if (token.isNotBlank()) header("Authorization", "Bearer $token")
            }.build()
        } catch (_: Throwable) {
            closeDetail = "bad url"
            complete()
            cont.resume(false)
            return@suspendCancellableCoroutine
        }
        val created = try {
            http.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    socket = webSocket
                    if (cont.isActive) cont.resume(true)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (inbound.trySend(text).isFailure) {
                        closeDetail = "inbound full"
                        try {
                            webSocket.cancel()
                        } catch (_: Throwable) {
                        }
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    if (reason.isNotBlank()) closeDetail = reason
                    try {
                        webSocket.close(1000, null)
                    } catch (_: Throwable) {
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (reason.isNotBlank()) closeDetail = reason
                    complete()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (response?.code == 401) closeDetail = "unauthorized"
                    else if (closeDetail.isBlank()) closeDetail = t.message ?: "failure"
                    if (cont.isActive) cont.resume(false)
                    complete()
                }
            })
        } catch (_: Throwable) {
            complete()
            if (cont.isActive) cont.resume(false)
            return@suspendCancellableCoroutine
        }
        socket = created
        cont.invokeOnCancellation {
            try {
                created.cancel()
            } catch (_: Throwable) {
            }
            complete()
        }
    }

    fun send(text: String): Boolean {
        return try {
            socket?.send(text) == true
        } catch (_: Throwable) {
            false
        }
    }

    fun cancel() {
        try {
            socket?.cancel()
        } catch (_: Throwable) {
        }
        complete()
    }

    private fun complete() {
        if (!done.compareAndSet(false, true)) return
        if (!closed.isCompleted) closed.complete(Unit)
        inbound.close()
    }
}
