package com.base.realtime.onboarding

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

enum class OnboardingEnsureResult {
    Ready,
    Absent,
    Failed,
}

object OnboardingCache {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val assetRef = Regex(
        """(?i)(?:src|href)\s*=\s*["']([^"']+\.(?:png|jpe?g|webp|gif|svg))["']|url\(\s*['"]?([^'")\s]+\.(?:png|jpe?g|webp|gif|svg))['"]?\s*\)""",
    )

    fun isReady(context: Context, port: String): Boolean {
        if (port.isBlank()) return false
        val index = file(context, port, "index.html")
        return index.isFile && index.length() > 0L
    }

    fun indexFile(context: Context, port: String): File = file(context, port, "index.html")

    fun ensure(context: Context, serverUrl: String, port: String): OnboardingEnsureResult {
        if (port.isBlank()) return OnboardingEnsureResult.Absent
        if (isReady(context, port)) return OnboardingEnsureResult.Ready
        val base = OnboardingUrls.httpBase(serverUrl) ?: return OnboardingEnsureResult.Failed
        val root = "$base/v1/ports/$port/onboarding"
        val dir = dir(context, port)
        val tmp = File(dir.parentFile, "${port}.tmp")
        try {
            if (tmp.exists()) tmp.deleteRecursively()
            if (!tmp.mkdirs()) return OnboardingEnsureResult.Failed

            val htmlBody = when (val html = download("$root/index.html")) {
                is Download.Absent -> {
                    tmp.deleteRecursively()
                    return OnboardingEnsureResult.Absent
                }
                is Download.Failed -> {
                    tmp.deleteRecursively()
                    return OnboardingEnsureResult.Failed
                }
                is Download.Ok -> html.body
            }
            write(File(tmp, "index.html"), htmlBody)

            when (val css = download("$root/styles.css")) {
                is Download.Ok -> write(File(tmp, "styles.css"), css.body)
                is Download.Absent -> write(File(tmp, "styles.css"), ByteArray(0))
                is Download.Failed -> {
                    tmp.deleteRecursively()
                    return OnboardingEnsureResult.Failed
                }
            }

            when (val js = download("$root/app.js")) {
                is Download.Ok -> write(File(tmp, "app.js"), js.body)
                is Download.Absent -> write(File(tmp, "app.js"), ByteArray(0))
                is Download.Failed -> {
                    tmp.deleteRecursively()
                    return OnboardingEnsureResult.Failed
                }
            }

            val cssText = runCatching { File(tmp, "styles.css").readText(Charsets.UTF_8) }.getOrDefault("")
            val htmlText = runCatching { htmlBody.toString(Charsets.UTF_8) }.getOrDefault("")
            val refs = linkedSetOf<String>()
            collectAssetRefs(htmlText, refs)
            collectAssetRefs(cssText, refs)
            for (ref in refs) {
                when (val asset = download("$root/$ref")) {
                    is Download.Ok -> write(File(tmp, ref), asset.body)
                    is Download.Absent -> Unit
                    is Download.Failed -> {
                        tmp.deleteRecursively()
                        return OnboardingEnsureResult.Failed
                    }
                }
            }

            if (dir.exists()) dir.deleteRecursively()
            if (!tmp.renameTo(dir)) {
                tmp.copyRecursively(dir, overwrite = true)
                tmp.deleteRecursively()
            }
            return if (isReady(context, port)) OnboardingEnsureResult.Ready else OnboardingEnsureResult.Failed
        } catch (_: Throwable) {
            runCatching { tmp.deleteRecursively() }
            return OnboardingEnsureResult.Failed
        }
    }

    private fun collectAssetRefs(text: String, out: MutableSet<String>) {
        for (match in assetRef.findAll(text)) {
            val raw = (match.groupValues.getOrNull(1) ?: "")
                .ifBlank { match.groupValues.getOrNull(2).orEmpty() }
                .trim()
            val normalized = normalizeRef(raw) ?: continue
            out += normalized
        }
    }

    private fun normalizeRef(raw: String): String? {
        if (raw.isBlank() || raw.startsWith("data:", ignoreCase = true) || raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("//")) {
            return null
        }
        var path = raw.substringBefore('#').substringBefore('?').replace('\\', '/')
        while (path.startsWith("./")) path = path.removePrefix("./")
        path = path.trimStart('/')
        if (path.isBlank() || path.contains("..")) return null
        val lower = path.lowercase()
        if (!lower.endsWith(".png") && !lower.endsWith(".jpg") && !lower.endsWith(".jpeg") &&
            !lower.endsWith(".webp") && !lower.endsWith(".gif") && !lower.endsWith(".svg")
        ) {
            return null
        }
        return path
    }

    private fun dir(context: Context, port: String): File =
        File(context.applicationContext.filesDir, "onboarding/$port")

    private fun file(context: Context, port: String, name: String): File =
        File(dir(context, port), name)

    private fun write(file: File, body: ByteArray) {
        file.parentFile?.mkdirs()
        file.writeBytes(body)
    }

    private fun download(url: String): Download {
        return try {
            val request = Request.Builder().url(url).get().build()
            http.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> Download.Absent
                    !response.isSuccessful -> Download.Failed
                    else -> Download.Ok(response.body?.bytes() ?: ByteArray(0))
                }
            }
        } catch (_: Throwable) {
            Download.Failed
        }
    }

    private sealed class Download {
        data class Ok(val body: ByteArray) : Download()
        data object Absent : Download()
        data object Failed : Download()
    }
}
