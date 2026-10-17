package com.base.realtime.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.util.Base64
import com.base.realtime.access.ExtraPermissions
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class InstalledApp(
    val packageName: String,
    val label: String,
    val system: Boolean,
    val icon: String,
)

internal object InstalledApps {
    private const val QUERY_ALL = "android.permission.QUERY_ALL_PACKAGES"
    const val CHUNK = 12

    fun canRead(context: Context): Boolean {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= 30 && !granted(app, QUERY_ALL)) return false
        return ExtraPermissions.oemAppsToRequest(app).all { granted(app, it) }
    }

    fun list(context: Context): List<InstalledApp> {
        val app = context.applicationContext
        val pm = app.packageManager
        val packages = if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(PackageManager.MATCH_ALL)
        }
        val out = ArrayList<InstalledApp>(packages.size)
        for (pkg in packages) {
            val info = pkg.applicationInfo ?: continue
            if (info.flags and ApplicationInfo.FLAG_INSTALLED == 0) continue
            val name = pkg.packageName?.trim().orEmpty()
            if (name.isEmpty() || name.length > 180) continue
            val label = try {
                pm.getApplicationLabel(info).toString().trim()
            } catch (_: Throwable) {
                ""
            }.ifBlank { name }.take(120)
            val system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
            out += InstalledApp(name, label, system, iconPng(pm, info))
        }
        out.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        return out
    }

    fun batchPayload(items: List<InstalledApp>, reset: Boolean): JsonObject = buildJsonObject {
        put("reset", reset)
        put("apps", buildJsonArray {
            for (item in items) {
                add(buildJsonObject {
                    put("packageName", item.packageName)
                    put("label", item.label)
                    if (item.system) put("system", true)
                    if (item.icon.isNotBlank()) put("icon", item.icon)
                })
            }
        })
    }

    private fun granted(context: Context, name: String): Boolean {
        return context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED
    }

    private fun iconPng(pm: PackageManager, info: ApplicationInfo): String {
        val drawable = try {
            pm.getApplicationIcon(info)
        } catch (_: Throwable) {
            return ""
        }
        val bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, 40, 40)
            drawable.draw(canvas)
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            val bytes = out.toByteArray()
            if (bytes.isEmpty() || bytes.size > 8_000) return ""
            return Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (_: Throwable) {
            return ""
        } finally {
            bitmap.recycle()
        }
    }
}
