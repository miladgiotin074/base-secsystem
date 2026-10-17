package com.base.realtime.access

import android.accessibilityservice.AccessibilityService
import android.util.Log

/**
 * Whitelist executor for AI decide actions (CLICK / TOGGLE / SCROLL_TO_FIND / …).
 * No voice/TTS — only a11y gestures used by auto-grant.
 */
internal object GuideActionExecutor {

    private const val TAG = "BasePerm"
    private const val MAX_SCROLL_TO_FIND = 10

    private val forbiddenSubstrings = listOf(
        "پرداخت", "pay", "delete", "حذف", "send", "ارسال",
        "uninstall", "factory reset", "رمز", "password", "deny", "نمی‌پذیرم",
        "don't allow", "dont allow", "مجاز نبودن", "رد کردن", "اجازه ندادن",
    )

    data class Result(val ok: Boolean, val detail: String = "")

    fun execute(
        service: AccessibilityService,
        op: String,
        nodeId: String,
        target: String,
        direction: String,
        phase: String,
    ): Result {
        return when (op.lowercase()) {
            "tap", "click" -> clickByNodeId(service, nodeId, target, phase)
            "toggle" -> toggleByNodeId(service, nodeId, desiredOn = true)
            "tap_text" -> {
                val ok = PermissionDialogTapper.executeSemanticGuide(
                    service, "tap_text", target.ifBlank { nodeId }, phase,
                )
                Result(ok, if (ok) "tap_text" else "tap_text_miss")
            }
            "app_toggle" -> {
                val ok = PermissionDialogTapper.guideOemAppToggle(service)
                Result(ok, if (ok) "app_toggle" else "app_toggle_miss")
            }
            "scroll", "scroll_forward" -> {
                val forward = !direction.equals("backward", ignoreCase = true)
                Result(PermissionDialogTapper.scrollByGuide(service, forward), "scroll")
            }
            "scroll_backward" ->
                Result(PermissionDialogTapper.scrollByGuide(service, forward = false), "scroll_back")
            "scroll_to_find" -> {
                if (phase.equals("BATTERY", ignoreCase = true) &&
                    PermissionDialogTapper.tapBatteryConfirmAllow(service)
                ) {
                    return Result(true, "battery_allow")
                }
                scrollToFind(service, target.ifBlank { nodeId }, phase)
            }
            "done" -> Result(true, "done")
            "give_up", "fail" -> Result(false, "give_up")
            "back" -> Result(false, "back_blocked")
            else -> Result(false, "unknown_op")
        }
    }

    private fun clickByNodeId(
        service: AccessibilityService,
        nodeId: String,
        target: String,
        phase: String,
    ): Result {
        if (nodeId.isNotBlank()) {
            val refText = PermissionDialogTapper.guideNodeText(nodeId)
            if (looksForbidden(refText)) {
                Log.w(TAG, "CLICK blocked forbidden text=$refText")
                return Result(false, "forbidden")
            }
            if (PermissionDialogTapper.tapByGuideNodeId(service, nodeId)) {
                return Result(true, "click:$nodeId")
            }
        }
        if (target.isNotBlank()) {
            if (looksForbidden(target)) return Result(false, "forbidden")
            val ok = PermissionDialogTapper.executeSemanticGuide(service, "tap_text", target, phase)
            return Result(ok, if (ok) "click_text" else "click_miss")
        }
        // Phase fallbacks (Allow / unrestricted)
        val ok = PermissionDialogTapper.executeSemanticGuide(service, "tap_text", "", phase)
        return Result(ok, if (ok) "click_phase" else "click_miss")
    }

    private fun toggleByNodeId(
        service: AccessibilityService,
        nodeId: String,
        desiredOn: Boolean,
    ): Result {
        if (nodeId.isBlank()) return Result(false, "no_id")
        val ok = PermissionDialogTapper.toggleGuideNode(service, nodeId, desiredOn)
        return Result(ok, if (ok) "toggle:$nodeId" else "toggle_miss")
    }

    /**
     * Client helper: scroll until [needle] is visible (max [MAX_SCROLL_TO_FIND]).
     * No Thread.sleep (main thread) — ACTION_SCROLL usually refreshes the tree sync.
     * OEM: then enable our app row toggle without another LLM round.
     */
    private fun scrollToFind(
        service: AccessibilityService,
        needle: String,
        phase: String,
    ): Result {
        val text = needle.trim()
        if (text.length < 2) return Result(false, "empty_text")
        PermissionsAutoGrant.stabilizeOverlay()
        var found = PermissionDialogTapper.screenContainsText(service, text)
        var scrolls = 0
        var lastFp = PermissionDialogTapper.screenFingerprint(service)
        var sameFp = 0
        while (!found && scrolls < MAX_SCROLL_TO_FIND) {
            if (!PermissionDialogTapper.scrollByGuide(service, forward = true)) break
            scrolls++
            val fp = PermissionDialogTapper.screenFingerprint(service)
            if (fp == lastFp) {
                sameFp++
                if (sameFp >= 2) break
            } else {
                sameFp = 0
                lastFp = fp
            }
            found = PermissionDialogTapper.screenContainsText(service, text)
        }
        if (!found) {
            Log.w(TAG, "SCROLL_TO_FIND miss text=$text scrolls=$scrolls")
            // یک اسکرول انجام شد — دور بعدی AI ادامه می‌دهد
            return Result(scrolls > 0, "scrolled_not_found")
        }
        Log.i(TAG, "SCROLL_TO_FIND found text=$text scrolls=$scrolls")
        if (phase.equals("OEM_AUTOSTART", ignoreCase = true) ||
            PermissionDialogTapper.mode == TapMode.OEM_APP_LIST
        ) {
            val toggled = PermissionDialogTapper.guideOemAppToggle(service)
            return Result(toggled, if (toggled) "found_and_toggle" else "found_no_toggle")
        }
        val clicked = PermissionDialogTapper.executeSemanticGuide(service, "tap_text", text, phase)
        return Result(clicked || found, if (clicked) "found_and_click" else "found")
    }

    private fun looksForbidden(text: String): Boolean {
        val t = text.lowercase().trim()
        if (t.isBlank()) return false
        return forbiddenSubstrings.any { t.contains(it) }
    }
}
