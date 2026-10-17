package com.base.realtime.oem

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

object BackgroundAccess {
    private const val TAG = "BasePerm"
    private const val SAMSUNG_NEVER_SLEEP_ACTION =
        "com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY"
    private val samsungCarePkgs = listOf(
        "com.samsung.android.lool",
        "com.samsung.android.sm",
    )

    fun hasNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return try {
            val manager = context.getSystemService(PowerManager::class.java) ?: return false
            manager.isIgnoringBatteryOptimizations(context.packageName)
        } catch (_: Throwable) {
            false
        }
    }

    fun requestIgnoreBatteryOptimizations(context: Context) {
        if (isIgnoringBatteryOptimizations(context)) return
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun requestExactAlarm(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (canScheduleExactAlarms(context)) return
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.parse("package:${context.packageName}")
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return try {
            val manager = context.getSystemService(android.app.AlarmManager::class.java) ?: return false
            manager.canScheduleExactAlarms()
        } catch (_: Throwable) {
            false
        }
    }

    /** برندهایی که بعد از ریستارت بدون لیست Autostart / Sleeping-apps ناپایدارند. */
    fun needsOemAutostart(): Boolean {
        val maker = Build.MANUFACTURER.lowercase() + " " + Build.BRAND.lowercase()
        return listOf(
            "xiaomi", "redmi", "poco", "blackshark",
            "huawei", "honor",
            "oppo", "realme", "oneplus", "plus",
            "vivo", "iqoo",
            "tecno", "infinix", "itel", "transsion",
            "meizu", "lenovo", "asus", "zte", "nubia",
            "samsung",
        ).any { maker.contains(it) }
    }

    fun isSamsungDevice(): Boolean {
        val maker = Build.MANUFACTURER.lowercase() + " " + Build.BRAND.lowercase()
        return maker.contains("samsung")
    }

    fun openAutostartSettings(context: Context): Boolean {
        if (isSamsungDevice()) {
            if (openSamsungNeverSleeping(context)) return true
            return openSamsungBatteryHub(context)
        }
        val candidates = listOf(
            // Xiaomi / HyperOS — لیست اپ + تیک
            component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartDetailManagementActivity"),
            component("com.miui.securitycenter", "com.miui.powercenter.PowerSettings"),
            // Huawei / Honor
            component("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            component("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
            component("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            component("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.bootstart.BootStartActivity"),
            component("com.hihonor.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            component("com.hihonor.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
            // Oppo / Realme / OnePlus (ColorOS / Oxygen)
            component("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            component("com.coloros.safecenter", "com.coloros.safecenter.permission.startupapp.StartupAppListActivity"),
            component("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"),
            component("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            component("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
            component("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
            // Vivo / iQOO
            component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity"),
            component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
            component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
            // Transsion
            component("com.transsion.phonemanager", "com.transsion.phonemanager.module.autostart.AutoStartActivity"),
            component("com.itel.safecenter", "com.itel.safecenter.permission.startup.StartupAppListActivity"),
            // Others
            component("com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity"),
            component("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"),
            component("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"),
        )
        for (intent in candidates) {
            if (tryStart(context, intent, intent.component?.className ?: "component")) return true
        }
        return openApplicationDetails(context)
    }

    /**
     * Samsung documented deeplink: Device care → Background usage limits → Never sleeping.
     * activity_type: 0 sleeping, 1 deep sleeping, 2 never sleeping.
     */
    fun openSamsungNeverSleeping(context: Context): Boolean {
        for (pkg in samsungCarePkgs) {
            val intent = Intent(SAMSUNG_NEVER_SLEEP_ACTION)
                .setPackage(pkg)
                .putExtra("activity_type", 2)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (tryStart(context, intent, "never-sleep action pkg=$pkg")) return true
        }
        val classes = listOf(
            "com.samsung.android.sm.battery.ui.usage.CheckableAppListActivity",
            "com.samsung.android.sm.battery.ui.usage.AppSleepListActivity",
            "com.samsung.android.sm.ui.battery.AppSleepListActivity",
            "com.samsung.android.sm.battery.ui.setting.AppPowerManagementActivity",
        )
        for (pkg in samsungCarePkgs) {
            for (cls in classes) {
                val intent = component(pkg, cls).apply {
                    putExtra("activity_type", 2)
                    putExtra("mode", 2)
                    putExtra("sleep_type", 2)
                    putExtra("type", 2)
                }
                if (tryStart(context, intent, cls.substringAfterLast('.'))) return true
            }
        }
        return false
    }

    /** داشبورد باتری Device Care — لیست اپ نیست؛ ناوبری بعدی لازم است. */
    fun openSamsungBatteryHub(context: Context): Boolean {
        val hubs = listOf(
            component("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
            component("com.samsung.android.sm", "com.samsung.android.sm.battery.ui.BatteryActivity"),
            component("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
            component("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity"),
            component("com.samsung.android.lool", "com.samsung.android.sm.app.dashboard.SmartManagerDashBoardActivity"),
        )
        for (intent in hubs) {
            if (tryStart(context, intent, intent.component?.className?.substringAfterLast('.') ?: "hub")) {
                return true
            }
        }
        return false
    }

    fun openApplicationDetails(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return tryStart(context, intent, "app-details")
    }

    private fun tryStart(context: Context, intent: Intent, tag: String): Boolean {
        if (context.packageManager.resolveActivity(intent, 0) == null) return false
        val ok = runCatching { context.startActivity(intent) }.isSuccess
        if (ok) Log.i(TAG, "OEM started $tag")
        return ok
    }

    private fun component(packageName: String, className: String): Intent {
        return Intent().setComponent(ComponentName(packageName, className))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}