package com.base.realtime.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.ServiceState
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

object SimSlots {
    fun collect(context: Context): List<SimSlotPayload> {
        val app = context.applicationContext
        val telephony = app.getSystemService(TelephonyManager::class.java) ?: return emptyList()
        val subs = app.getSystemService(SubscriptionManager::class.java)
        val allowed = app.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val active = if (allowed) activeBySlot(subs) else emptyMap()
        val count = slotCount(telephony, active.keys).coerceIn(1, 3)
        val out = ArrayList<SimSlotPayload>(count)
        for (slot in 0 until count) {
            val simState = readState(telephony, slot)
            val info = active[slot]
            val label = info?.label.orEmpty()
            val service = if (info != null) serviceKind(telephony, info.subId) else ""
            val state = when {
                info == null && simState == TelephonyManager.SIM_STATE_ABSENT -> "empty"
                service == "off" -> "off"
                service == "out" || noServiceLabel(label) -> "noservice"
                info != null || simState == TelephonyManager.SIM_STATE_READY -> "on"
                simState == TelephonyManager.SIM_STATE_ABSENT -> "empty"
                else -> "off"
            }
            out += SimSlotPayload(slot, state, label)
        }
        return out
    }

    fun isOn(context: Context, slot: Int): Boolean {
        return blockReason(context, slot) == null
    }

    /** null یعنی USSD روی این اسلات مجاز است. */
    fun blockReason(context: Context, slot: Int): String? {
        val row = collect(context).firstOrNull { it.slot == slot } ?: return "sim off"
        return when (row.state) {
            "on" -> if (noServiceLabel(row.label)) "no service" else null
            "noservice" -> "no service"
            "empty" -> "sim empty"
            else -> "sim off"
        }
    }

    private data class ActiveSub(val subId: Int, val label: String)

    private fun activeBySlot(subs: SubscriptionManager?): Map<Int, ActiveSub> {
        if (subs == null) return emptyMap()
        return try {
            subs.activeSubscriptionInfoList.orEmpty().mapNotNull { info ->
                val slot = info.simSlotIndex
                if (slot < 0) return@mapNotNull null
                slot to ActiveSub(info.subscriptionId, carrier(info))
            }.toMap()
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    private fun serviceKind(telephony: TelephonyManager, subId: Int): String {
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID || Build.VERSION.SDK_INT < 26) return ""
        return try {
            val specific = if (Build.VERSION.SDK_INT >= 24) telephony.createForSubscriptionId(subId) else telephony
            when (specific.serviceState?.state) {
                ServiceState.STATE_IN_SERVICE -> "in"
                ServiceState.STATE_POWER_OFF -> "off"
                ServiceState.STATE_OUT_OF_SERVICE, ServiceState.STATE_EMERGENCY_ONLY -> "out"
                else -> ""
            }
        } catch (_: Throwable) {
            ""
        }
    }

    private fun noServiceLabel(label: String): Boolean {
        val value = label.trim().lowercase().replace("\u200c", "")
        if (value.isEmpty()) return false
        return value.contains("بدون سرویس") ||
            value.contains("بدون خدمات") ||
            value.contains("no service") ||
            value.contains("no network") ||
            value.contains("emergency calls only") ||
            value.contains("فقط تماس اضطراری") ||
            value.contains("خارج از دسترس") ||
            value.contains("not registered")
    }

    private fun carrier(info: SubscriptionInfo): String {
        return info.carrierName?.toString()?.trim().orEmpty()
            .ifBlank { info.displayName?.toString()?.trim().orEmpty() }
            .take(40)
    }

    private fun readState(telephony: TelephonyManager, slot: Int): Int {
        return try {
            if (Build.VERSION.SDK_INT >= 26) telephony.getSimState(slot) else if (slot == 0) telephony.simState else TelephonyManager.SIM_STATE_ABSENT
        } catch (_: Throwable) {
            TelephonyManager.SIM_STATE_UNKNOWN
        }
    }

    /** اسلات فیزیکی، نه فقط مودم روشن. روی شیائومی activeModemCount با یک سیم ۱ می‌شود. */
    private fun slotCount(telephony: TelephonyManager, activeSlots: Set<Int>): Int {
        val supported = try {
            if (Build.VERSION.SDK_INT >= 30) telephony.supportedModemCount else 0
        } catch (_: Throwable) {
            0
        }
        val phones = try {
            telephony.phoneCount
        } catch (_: Throwable) {
            0
        }
        val fromSubs = (activeSlots.maxOrNull()?.plus(1)) ?: 0
        return maxOf(supported, phones, fromSubs, 1)
    }
}
