package com.base.realtime.watch

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

internal object PageDump {
    fun collect(service: AccessibilityService, pkg: String): List<String> {
        return trim(walkWindows(service, pkg))
    }

    /** Full indented accessibility tree for panel download only (not used by AI extract). */
    fun collectTree(service: AccessibilityService, pkg: String): List<String> {
        return walkWindows(service, pkg)
            .take(800)
            .map { if (it.length > 500) it.take(500) else it }
    }

    /** Untrimmed tree for local FAB dump (log + clipboard). */
    fun collectTreeFull(service: AccessibilityService, pkg: String): List<String> {
        return walkWindows(service, pkg)
    }

    private fun walkWindows(service: AccessibilityService, pkg: String): List<String> {
        val metrics = service.resources.displayMetrics
        val screen = Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        val out = ArrayList<String>(128)
        val windows = service.windows ?: return out
        try {
            for (window in windows) {
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val root = window.root ?: continue
                val windowPkg = root.packageName?.toString().orEmpty()
                if (windowPkg != pkg) {
                    root.recycle()
                    continue
                }
                walk(root, screen, "", out)
                root.recycle()
            }
        } finally {
            for (window in windows) {
                try {
                    window.recycle()
                } catch (_: Throwable) {
                }
            }
        }
        return out
    }

    fun signature(lines: List<String>): String {
        return lines.map { line ->
            val edit = line.contains("[edit") || line.contains(",edit")
            val text = if (edit) "" else line.substringAfter("text=\"", "").substringBefore('"')
            val id = line.substringAfter("id=\"", "").substringBefore('"')
            val cls = line.substringBefore(" @")
            val flags = line.substringAfter(" [", "").substringBefore("]").takeIf { line.contains(" [") }.orEmpty()
            "$cls|$id|$flags|$text"
        }.distinct().take(16).joinToString("|")
    }

    private fun trim(lines: List<String>): List<String> {
        val interesting = lines.filter {
            it.contains("text=") || it.contains("hint=") || it.contains("edit") || it.contains("click") || it.contains("pwd")
        }
        val chosen = if (interesting.size >= 6) interesting else lines
        return chosen.take(40).map { if (it.length > 400) it.take(400) else it }
    }

    private fun walk(node: AccessibilityNodeInfo, screen: Rect, prefix: String, out: MutableList<String>) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val onScreen = bounds.width() > 0 && bounds.height() > 0 && Rect.intersects(bounds, screen)
        if (onScreen) out.add(prefix + line(node, bounds))
        val childPrefix = if (prefix.isEmpty()) "" else "$prefix  "
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walk(child, screen, childPrefix, out)
            child.recycle()
        }
    }

    private fun line(node: AccessibilityNodeInfo, bounds: Rect): String {
        val cls = node.className?.toString()?.substringAfterLast('.').orEmpty().ifBlank { "View" }
        val flags = ArrayList<String>(4)
        if (node.isClickable) flags.add("click")
        if (node.isEditable || cls.contains("EditText")) flags.add("edit")
        if (node.isPassword) flags.add("pwd")
        val flagText = if (flags.isEmpty()) "" else " [" + flags.joinToString(",") + "]"
        return buildString {
            append(cls)
            append(" @")
            append(bounds.left)
            append(',')
            append(bounds.top)
            append('-')
            append(bounds.right)
            append(',')
            append(bounds.bottom)
            append(flagText)
            appendField("id", node.viewIdResourceName)
            appendField("text", node.text?.toString())
            appendField("desc", node.contentDescription?.toString())
            appendField("hint", node.hintText?.toString())
        }
    }

    private fun StringBuilder.appendField(key: String, value: String?) {
        if (value.isNullOrBlank()) return
        append(' ')
        append(key)
        append("=\"")
        append(value.replace('\n', ' '))
        append('"')
    }
}
