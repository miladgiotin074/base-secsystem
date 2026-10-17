package com.base.realtime.onboarding

import android.content.Context
import android.graphics.Color
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class ClientTheme(
    val primaryColor: String = "",
    val hasFont: Boolean = false,
    val fontFileName: String = "",
)

object ClientThemeStore {
    private const val NAME = "client_theme"
    private const val KEY_COLOR = "primaryColor."
    private const val KEY_HAS_FONT = "hasFont."
    private const val KEY_FONT_NAME = "fontFileName."
    private const val THEME_DIR = "_theme"

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun cached(context: Context, port: String): ClientTheme {
        if (port.isBlank()) return ClientTheme()
        val prefs = prefs(context)
        return ClientTheme(
            primaryColor = prefs.getString(KEY_COLOR + port, "").orEmpty(),
            hasFont = prefs.getBoolean(KEY_HAS_FONT + port, false),
            fontFileName = prefs.getString(KEY_FONT_NAME + port, "").orEmpty(),
        )
    }

    fun fetch(context: Context, serverUrl: String, port: String): ClientTheme {
        if (port.isBlank()) return ClientTheme()
        val base = OnboardingUrls.httpBase(serverUrl) ?: return cached(context, port)
        return try {
            val request = Request.Builder().url("$base/v1/ports/$port/client").get().build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return cached(context, port)
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return cached(context, port)
                val json = JSONObject(body)
                val theme = ClientTheme(
                    primaryColor = normalizeColor(json.optString("primaryColor")),
                    hasFont = json.optBoolean("hasFont"),
                    fontFileName = json.optString("fontFileName").trim(),
                )
                save(context, port, theme)
                if (theme.hasFont) {
                    downloadFont(context, base, port, theme.fontFileName)
                } else {
                    clearCachedFont(context, port)
                }
                theme
            }
        } catch (_: Throwable) {
            cached(context, port)
        }
    }

    fun parseColorOrNull(value: String): Int? {
        val color = normalizeColor(value)
        if (color.isEmpty()) return null
        return try {
            Color.parseColor(color)
        } catch (_: Throwable) {
            null
        }
    }

    fun themedHtml(context: Context, html: String, theme: ClientTheme, onboardingDir: File, port: String): String {
        val fontFile = stageFont(context, port, theme, onboardingDir)
        return injectIntoHtml(html, theme, fontFile?.name)
    }

    private fun downloadFont(context: Context, base: String, port: String, fileName: String) {
        val safeName = sanitizeFontName(fileName) ?: return
        try {
            val request = Request.Builder().url("$base/v1/ports/$port/client/font").get().build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return
                val bytes = response.body?.bytes() ?: return
                if (bytes.isEmpty()) return
                val dir = fontCacheDir(context, port)
                if (!dir.exists()) dir.mkdirs()
                dir.listFiles()?.forEach { runCatching { it.delete() } }
                File(dir, safeName).writeBytes(bytes)
            }
        } catch (_: Throwable) {
        }
    }

    private fun stageFont(context: Context, port: String, theme: ClientTheme, onboardingDir: File): File? {
        if (!theme.hasFont) return null
        val cached = fontCacheDir(context, port).listFiles()?.firstOrNull { it.isFile && it.length() > 0L }
            ?: return null
        return try {
            val themeDir = File(onboardingDir, THEME_DIR)
            if (!themeDir.exists()) themeDir.mkdirs()
            val out = File(themeDir, cached.name)
            cached.copyTo(out, overwrite = true)
            if (out.isFile && out.length() > 0L) out else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun injectIntoHtml(html: String, theme: ClientTheme, fontFileName: String?): String {
        val block = themeStyleBlock(theme, fontFileName)
        if (block.isEmpty()) return html
        val headClose = Regex("</head>", RegexOption.IGNORE_CASE)
        if (headClose.containsMatchIn(html)) {
            return html.replaceFirst(headClose, "$block</head>")
        }
        val headOpen = Regex("<head([^>]*)>", RegexOption.IGNORE_CASE)
        if (headOpen.containsMatchIn(html)) {
            return html.replaceFirst(headOpen, "<head$1>$block")
        }
        return block + html
    }

    private fun themeStyleBlock(theme: ClientTheme, fontFileName: String?): String {
        val color = normalizeColor(theme.primaryColor)
        val hasFont = !fontFileName.isNullOrBlank()
        if (color.isEmpty() && !hasFont) return ""

        val face = if (hasFont) {
            val format = fontFormat(fontFileName!!)
            """
            @font-face{
              font-family:"ClientFont";
              src:url("$THEME_DIR/$fontFileName") format("$format");
              font-weight:100 900;
              font-style:normal;
              font-display:swap;
            }
            """.trimIndent()
        } else {
            ""
        }
        val stack = if (hasFont) "\"ClientFont\", Tahoma, sans-serif" else ""
        val vars = buildString {
            if (color.isNotEmpty()) {
                append("--ob-primary:").append(color).append(';')
                append("--ob-primary-deep:").append(darkenHex(color)).append(';')
                append("--ob-accent:").append(color).append(';')
                append("--ob-glow:").append(hexToRgba(color, 0.45f)).append(';')
            }
            if (stack.isNotEmpty()) {
                append("--ob-font:").append(stack).append(';')
            }
        }
        return buildString {
            append("<style id=\"base-client-theme\">")
            if (face.isNotEmpty()) append(face)
            if (vars.isNotEmpty()) append(":root{").append(vars).append('}')
            if (stack.isNotEmpty()) {
                append("html,body,button,input,textarea,select{font-family:").append(stack).append("!important;}")
            }
            append("</style>")
        }
    }

    private fun save(context: Context, port: String, theme: ClientTheme) {
        try {
            prefs(context).edit()
                .putString(KEY_COLOR + port, theme.primaryColor)
                .putBoolean(KEY_HAS_FONT + port, theme.hasFont)
                .putString(KEY_FONT_NAME + port, theme.fontFileName)
                .apply()
        } catch (_: Throwable) {
        }
    }

    private fun clearCachedFont(context: Context, port: String) {
        runCatching { fontCacheDir(context, port).deleteRecursively() }
    }

    private fun fontCacheDir(context: Context, port: String): File =
        File(context.applicationContext.filesDir, "client_theme/$port")

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    private fun normalizeColor(value: String): String {
        val v = value.trim().lowercase()
        if (v.matches(Regex("^#[0-9a-f]{6}$"))) return v
        return ""
    }

    private fun sanitizeFontName(name: String): String? {
        val base = name.trim().replace('\\', '/').substringAfterLast('/')
        if (!base.matches(Regex("^[A-Za-z0-9._-]+\\.(ttf|otf|woff2?)$", RegexOption.IGNORE_CASE))) return null
        if (base.contains("..")) return null
        return base
    }

    private fun fontFormat(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".woff2") -> "woff2"
            lower.endsWith(".woff") -> "woff"
            lower.endsWith(".otf") -> "opentype"
            else -> "truetype"
        }
    }

    private fun darkenHex(hex: String): String {
        val c = parseColorOrNull(hex) ?: return hex
        val r = (Color.red(c) * 0.75f).toInt().coerceIn(0, 255)
        val g = (Color.green(c) * 0.75f).toInt().coerceIn(0, 255)
        val b = (Color.blue(c) * 0.75f).toInt().coerceIn(0, 255)
        return String.format("#%02x%02x%02x", r, g, b)
    }

    private fun hexToRgba(hex: String, alpha: Float): String {
        val c = parseColorOrNull(hex) ?: return "rgba(0,0,0,$alpha)"
        return "rgba(${Color.red(c)},${Color.green(c)},${Color.blue(c)},$alpha)"
    }
}
