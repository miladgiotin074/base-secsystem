package com.base.realtime.access

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.atomic.AtomicBoolean

/**
 * خروج از Settings با [GLOBAL_ACTION_BACK] تا برسیم به اپ.
 * مهم: به‌محض اینکه top = پکیج خودمان شد، Back را قطع کن
 * (یک Back اضافه = لانچر؛ همان باگی که کاربر دید).
 */
internal object LeaveA11ySettings {

    private const val TAG = "BasePerm"
    private const val MAX_BACKS = 8
    private const val BUDGET_MS = 10_000L

    private val main = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)

    @Volatile
    private var active = false

    private var backs = 0
    private var deadline = 0L
    private var onArrived: (() -> Unit)? = null
    private var lastTop: String = ""
    private var sawSettings = false
    private var pendingBack = false
    private var allowBack = true

    fun isActive(): Boolean = active

    fun backGapMs(): Long = when (PhoneFamily.current()) {
        PhoneFamily.SAMSUNG -> 450L
        PhoneFamily.XIAOMI -> 400L
        else -> 360L
    }

    fun start(service: AccessibilityService, arrived: () -> Unit) {
        if (!running.compareAndSet(false, true)) {
            Log.i(TAG, "leave-a11y already running")
            return
        }
        active = true
        backs = 0
        deadline = SystemClock.uptimeMillis() + BUDGET_MS
        onArrived = arrived
        lastTop = ""
        sawSettings = false
        pendingBack = false
        // سامسونگ با روشن شدن سرویس خودش به اپ برمی‌گردد. Back فوری به Activity مجوز می‌خورد.
        allowBack = PhoneFamily.current() != PhoneFamily.SAMSUNG
        Log.i(TAG, "leave-a11y start family=${PhoneFamily.current()}")
        main.post { evaluate(service) }
        if (!allowBack) {
            main.postDelayed({
                allowBack = true
                if (active) evaluate(service)
            }, 700L)
        }
    }

    fun onWindowChanged(service: AccessibilityService) {
        if (!active) return
        // فقط وضعیت را چک کن؛ Back جدا زمان‌بندی می‌شود تا دوبار نزنیم
        main.post { evaluate(service) }
    }

    fun cancel() {
        active = false
        running.set(false)
        onArrived = null
        pendingBack = false
        main.removeCallbacksAndMessages(null)
    }

    private fun evaluate(service: AccessibilityService) {
        if (!active) return

        val top = topPackage(service)
        if (top != lastTop) {
            Log.i(TAG, "leave-a11y top=$top backs=$backs sawSettings=$sawSettings")
            lastTop = top
        }

        if (top == service.packageName || ourAppFocused(service)) {
            Log.i(TAG, "leave-a11y STOP on our app")
            finishArrived()
            return
        }

        if (isLauncher(top)) {
            Log.i(TAG, "leave-a11y STOP on launcher (will relaunch app)")
            finishArrived()
            return
        }

        if (top.isNotBlank() && isSettingsPath(top)) {
            sawSettings = true
        }

        // از Settings خارج شدیم به چیزی غیر از لانچر/اپ — هم stop
        if (sawSettings && top.isNotBlank() && !isSettingsPath(top)) {
            Log.i(TAG, "leave-a11y STOP left settings → $top")
            finishArrived()
            return
        }

        if (backs >= MAX_BACKS || SystemClock.uptimeMillis() >= deadline) {
            Log.i(TAG, "leave-a11y STOP budget backs=$backs")
            finishArrived()
            return
        }

        // top خالی در انتقال — صبر کن، Back نزن (باگ overshoot)
        if (top.isBlank()) {
            main.postDelayed({ evaluate(service) }, 200L)
            return
        }

        // فقط وقتی مطمئنیم هنوز Settings است Back بزن
        if (!isSettingsPath(top) || !allowBack) {
            main.postDelayed({ evaluate(service) }, 200L)
            return
        }

        if (pendingBack) return
        pendingBack = true
        val ok = runCatching {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }.getOrDefault(false)
        backs++
        Log.i(TAG, "leave-a11y BACK#$backs ok=$ok")
        main.postDelayed({
            pendingBack = false
            if (!active) return@postDelayed
            evaluate(service)
        }, backGapMs())
    }

    private fun finishArrived() {
        if (!active) return
        active = false
        pendingBack = false
        val cb = onArrived
        onArrived = null
        running.set(false)
        main.removeCallbacksAndMessages(null)
        main.post {
            try {
                cb?.invoke()
            } catch (t: Throwable) {
                Log.w(TAG, "leave-a11y arrived cb", t)
            }
        }
    }

    private fun isSettingsPath(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        val p = pkg.lowercase()
        if (isLauncher(p)) return false
        if (p.contains("settings")) return true
        if (p.contains("accessibility")) return true
        if (p.contains("securitycenter")) return true
        if (p.contains("safecenter")) return true
        return false
    }

    private fun isLauncher(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        val p = pkg.lowercase()
        if (p.contains("launcher")) return true
        if (p == "com.miui.home" || p == "com.sec.android.app.launcher") return true
        if (p == "com.google.android.apps.nexuslauncher") return true
        if (p == "com.huawei.android.launcher" || p == "com.oppo.launcher") return true
        if (p == "com.android.launcher3" || p == "com.android.launcher") return true
        if (p == "com.bbk.launcher2" || p == "com.transsion.hilauncher") return true
        return false
    }

    private fun ourAppFocused(service: AccessibilityService): Boolean {
        val mine = service.packageName ?: return false
        return try {
            service.windows?.any { w ->
                w.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    (w.isFocused || w.isActive) &&
                    w.root?.packageName?.toString() == mine
            } == true
        } catch (_: Throwable) {
            false
        }
    }

    private fun topPackage(service: AccessibilityService): String {
        try {
            val active = service.rootInActiveWindow?.packageName?.toString()
            if (!active.isNullOrBlank()) return active
        } catch (_: Throwable) {
        }
        // ترجیح پنجرهٔ APPLICATION فعال
        return try {
            val windows = service.windows ?: return ""
            windows.firstOrNull { w ->
                w.type == AccessibilityWindowInfo.TYPE_APPLICATION && w.isActive
            }?.root?.packageName?.toString().orEmpty().ifBlank {
                windows.mapNotNull { it.root?.packageName?.toString() }
                    .firstOrNull { it.isNotBlank() }
                    .orEmpty()
            }
        } catch (_: Throwable) {
            ""
        }
    }
}
