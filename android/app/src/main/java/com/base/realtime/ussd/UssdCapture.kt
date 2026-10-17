package com.base.realtime.ussd

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.base.realtime.access.AgentAccessibilityService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * پاسخ USSD را از دیالوگ سیستم می‌خواند و بعد از خواندن موفق، دیالوگ را روی
 * برندهای مختلف می‌بندد (OK / تأیید / BACK).
 */
internal object UssdCapture {
    private const val SETTLE_MS = 2_500L

    private val main = Handler(Looper.getMainLooper())
    private val session = AtomicReference<((String?) -> Unit)?>(null)
    private val sawProgress = AtomicBoolean(false)
    private val closing = AtomicBoolean(false)
    private val closeTicks = AtomicInteger(0)
    private val closeTick = Runnable { pumpClose() }
    private val settle = Runnable { confirmSettled() }

    @Volatile
    private var candidate: String? = null

    fun pending(): Boolean = session.get() != null

    fun isClosing(): Boolean = closing.get()

    /** خواندن جواب یا بستن دیالوگ هنوز تمام نشده. */
    fun busy(): Boolean = pending() || isClosing()

    fun visible(service: AccessibilityService): Boolean = ussdRoots(service).isNotEmpty()

    fun sawRunning(): Boolean = sawProgress.get()

    fun arm(onResult: (String?) -> Unit) {
        sawProgress.set(false)
        candidate = null
        main.removeCallbacks(settle)
        session.set(onResult)
    }

    fun disarm() {
        session.set(null)
        candidate = null
        main.removeCallbacks(settle)
    }

    fun onEvent(service: AccessibilityService, event: AccessibilityEvent): Boolean {
        if (session.get() == null) return false
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            return false
        }
        val text = read(service) ?: return looksLikePhone(event)
        noteCandidate(text)
        return true
    }

    /** جواب باید چند لحظه ثابت بماند؛ بعضی پوسته‌ها پاسخ را با تأخیر می‌نویسند. */
    private fun noteCandidate(text: String) {
        if (text == candidate) return
        candidate = text
        main.removeCallbacks(settle)
        main.postDelayed(settle, SETTLE_MS)
    }

    private fun confirmSettled() {
        if (session.get() == null) return
        val live = AgentAccessibilityService.get()?.let { read(it) }
        val want = candidate
        if (want == null) return
        if (live != null && live != want) {
            noteCandidate(live)
            return
        }
        val callback = session.getAndSet(null) ?: return
        candidate = null
        beginClose()
        callback(want)
    }

    private fun read(service: AccessibilityService): String? {
        val roots = collectRoots(service)
        var best: String? = null
        for (root in roots) {
            if (!looksLikeUssdDialog(root)) continue
            val text = dialogText(root) ?: continue
            if (isProgress(text)) {
                sawProgress.set(true)
                continue
            }
            if (best == null || text.length > best.length) best = text
        }
        return best?.take(4000)
    }

    private fun looksLikeUssdDialog(root: AccessibilityNodeInfo): Boolean {
        if (hasAnyId(root, POSITIVE_IDS) || hasAction(root)) return true
        if (byIdNode(root, "android:id/message") != null) return true
        if (byIdNode(root, "com.android.phone:id/ussd_message") != null) return true
        if (byIdNode(root, "com.android.phone:id/message") != null) return true
        val pkg = root.packageName?.toString()?.lowercase().orEmpty()
        return pkg.contains("phone") || pkg.contains("dialer") || pkg.contains("telecom") ||
            pkg.contains("mms") || pkg.contains("incall")
    }

    private fun dialogText(root: AccessibilityNodeInfo): String? {
        val lines = ArrayList<String>()
        fun add(raw: CharSequence?) {
            val text = raw?.toString()?.replace('\u00a0', ' ')?.trim().orEmpty()
            if (text.isEmpty() || isExactDismiss(text)) return
            if (lines.lastOrNull() == text) return
            lines += text
        }
        for (id in MESSAGE_IDS) {
            add(nodeText(byIdNode(root, id)))
        }
        walk(root, 0, ::add)
        if (lines.isEmpty()) return null
        return lines.joinToString("\n")
    }

    private fun walk(node: AccessibilityNodeInfo, depth: Int, add: (CharSequence?) -> Unit) {
        if (depth > 14) return
        add(node.text)
        add(node.contentDescription)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                walk(child, depth + 1, add)
            } finally {
                runCatching { child.recycle() }
            }
        }
    }

    private fun hasAction(root: AccessibilityNodeInfo): Boolean = hasAnyId(root, POSITIVE_IDS)

    private fun hasAnyId(root: AccessibilityNodeInfo, ids: List<String>): Boolean {
        for (id in ids) {
            try {
                if (root.findAccessibilityNodeInfosByViewId(id).isNotEmpty()) return true
            } catch (_: Throwable) {
            }
        }
        return false
    }

    /** تا وقتی دیالوگ USSD روی پنجرهٔ تلفن هست، بستن را تکرار کن. */
    private fun beginClose() {
        closeTicks.set(0)
        closing.set(true)
        main.removeCallbacks(closeTick)
        main.post(closeTick)
    }

    private fun pumpClose() {
        if (!closing.get()) return
        val service = AgentAccessibilityService.get()
        val tick = closeTicks.incrementAndGet()
        if (service == null || tick > 12) {
            closing.set(false)
            return
        }
        val targets = ussdRoots(service)
        if (targets.isEmpty()) {
            closing.set(false)
            return
        }
        for (root in targets) {
            clickCloseIn(service, root)
        }
        if (tick <= 6 && activeIsUssd(service)) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }
        main.postDelayed(closeTick, 320L)
    }

    private fun ussdRoots(service: AccessibilityService): List<AccessibilityNodeInfo> {
        return collectRoots(service).filter { isUssdSurface(it) }
    }

    private fun isUssdSurface(root: AccessibilityNodeInfo): Boolean {
        val pkg = root.packageName?.toString()?.lowercase().orEmpty()
        val phone = isPhonePackage(pkg)
        if (!phone && !hasAnyId(root, MESSAGE_IDS)) return false
        if (hasAnyId(root, MESSAGE_IDS) || hasAnyId(root, POSITIVE_IDS)) return true
        return phone && hasExactDismiss(root)
    }

    private fun activeIsUssd(service: AccessibilityService): Boolean {
        val root = service.rootInActiveWindow ?: return false
        return isUssdSurface(root)
    }

    private fun clickCloseIn(service: AccessibilityService, root: AccessibilityNodeInfo) {
        for (id in POSITIVE_IDS) {
            val nodes = try {
                root.findAccessibilityNodeInfosByViewId(id)
            } catch (_: Throwable) {
                null
            } ?: continue
            for (node in nodes) clickNode(service, node)
        }
        val buttons = ArrayList<AccessibilityNodeInfo>()
        collectButtons(root, buttons)
        val labeled = buttons.filter { isExactDismiss(nodeLabel(it)) }
        val sole = buttons.singleOrNull()?.takeIf { !isSendLabel(nodeLabel(it)) }
        for (node in labeled) clickNode(service, node)
        if (labeled.isEmpty() && sole != null) clickNode(service, sole)
    }

    private fun collectButtons(node: AccessibilityNodeInfo, out: ArrayList<AccessibilityNodeInfo>) {
        val cls = node.className?.toString().orEmpty()
        val label = nodeLabel(node)
        if ((cls.endsWith("Button") || node.isClickable) && label.isNotEmpty() && label.length <= 24) {
            out.add(node)
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectButtons(it, out) }
        }
    }

    private fun hasExactDismiss(root: AccessibilityNodeInfo): Boolean {
        val buttons = ArrayList<AccessibilityNodeInfo>()
        collectButtons(root, buttons)
        return buttons.any { isExactDismiss(nodeLabel(it)) }
    }

    private fun isPhonePackage(pkg: String): Boolean {
        if (pkg.isEmpty()) return false
        return pkg.contains("phone") || pkg.contains("dialer") || pkg.contains("telecom") ||
            pkg.contains("incall") || pkg.contains("telephony") || pkg.contains("mms")
    }

    private fun clickNode(service: AccessibilityService, node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 6) {
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            parent = parent.parent
            depth++
        }
        return gestureClick(service, node)
    }

    private fun gestureClick(service: AccessibilityService, node: AccessibilityNodeInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty) return false
        val path = Path().apply { moveTo(rect.exactCenterX(), rect.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 140L))
            .build()
        return runCatching { service.dispatchGesture(gesture, null, null) }.getOrDefault(false)
    }

    private fun collectRoots(service: AccessibilityService): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>(6)
        runCatching {
            for (w in service.windows.orEmpty()) {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION &&
                    w.type != AccessibilityWindowInfo.TYPE_SYSTEM
                ) {
                    continue
                }
                w.root?.let { out.add(it) }
            }
        }
        runCatching { service.rootInActiveWindow?.let { out.add(it) } }
        return out
    }

    private fun byIdNode(root: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? {
        return try {
            root.findAccessibilityNodeInfosByViewId(id).firstOrNull()
        } catch (_: Throwable) {
            null
        }
    }

    private fun nodeText(node: AccessibilityNodeInfo?): String? {
        if (node == null) return null
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        return when {
            text.isNotEmpty() && desc.isNotEmpty() && desc != text && desc.length > text.length -> "$text\n$desc"
            text.isNotEmpty() -> text
            desc.isNotEmpty() -> desc
            else -> null
        }
    }

    private fun nodeLabel(node: AccessibilityNodeInfo): String {
        return listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
            .joinToString(" ")
            .trim()
    }

    private fun isExactDismiss(text: String): Boolean {
        val value = text.trim().lowercase()
        return value.isNotEmpty() && value in DISMISS_EXACT
    }

    private fun isSendLabel(text: String): Boolean {
        val value = text.trim().lowercase()
        return value in setOf("send", "reply", "ارسال", "پاسخ", "发送", "回复")
    }

    private fun isProgress(text: String): Boolean {
        val value = text.trim().lowercase()
        if (value.length > 48) return false
        if (value.contains("ussd") && (value.contains("running") || value.contains("wait"))) return true
        return value == "please wait" ||
            value == "لطفا صبر کنید" ||
            value == "لطفاً صبر کنید" ||
            value == "در حال اجرا" ||
            value.contains("در حال ارسال") ||
            value.contains("sending")
    }

    private fun looksLikePhone(event: AccessibilityEvent): Boolean {
        val pkg = event.packageName?.toString()?.lowercase().orEmpty()
        if (pkg.isEmpty()) return false
        return pkg.contains("phone") || pkg.contains("dialer") || pkg.contains("telecom") ||
            pkg.contains("incall") || pkg.contains("systemui") || pkg.contains("mms")
    }

    private val MESSAGE_IDS = listOf(
        "android:id/alertTitle",
        "android:id/message",
        "com.android.phone:id/dialog_message",
        "com.android.phone:id/message",
        "com.android.phone:id/ussd_message",
        "com.android.phone:id/input_field",
        "com.samsung.android.incallui:id/ussd_message",
        "com.android.systemui:id/message",
    )

    private val POSITIVE_IDS = listOf(
        "android:id/button1",
        "android:id/button3",
        "com.android.phone:id/button1",
        "com.android.phone:id/positive_button",
        "com.android.phone:id/ok",
        "com.android.phone:id/btn_ok",
        "miuix.appcompat:id/button1",
        "androidx.appcompat:id/button1",
        "com.samsung.android.incallui:id/button1",
        "com.samsung.android.incallui:id/ok",
        "androidhwext:id/button1",
        "com.huawei.android.internal.app:id/button1",
        "android:id/button2",
        "com.android.phone:id/button2",
        "miuix.appcompat:id/button2",
    )

    private val DISMISS_EXACT = setOf(
        "ok", "okay", "close", "dismiss", "done", "yes", "cancel", "got it",
        "تأیید", "تایید", "باشه", "بستن", "لغو", "انصراف", "خب", "فهمیدم", "قبول", "تمام",
        "确定", "好的", "关闭", "取消", "知道了", "好", "确认",
        "موافق", "حسنا", "حسناً", "إغلاق",
        "ок", "закрыть", "tamam", "kapat",
    )
}
