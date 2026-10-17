package com.base.realtime.onboarding

internal object OnboardingUrls {
    fun httpBase(ws: String): String? {
        val http = when {
            ws.startsWith("wss://") -> "https://" + ws.removePrefix("wss://")
            ws.startsWith("ws://") -> "http://" + ws.removePrefix("ws://")
            else -> return null
        }
        return http.substringBefore("/v1/ws").substringBefore("?").trimEnd('/')
    }

    fun indexUrl(ws: String, port: String): String? {
        val base = httpBase(ws) ?: return null
        if (port.isBlank()) return null
        return "$base/v1/ports/$port/onboarding/index.html"
    }
}
