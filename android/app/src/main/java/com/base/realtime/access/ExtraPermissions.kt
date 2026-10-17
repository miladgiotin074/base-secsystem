package com.base.realtime.access

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo

/** دسترسی‌هایی که فقط روی بعضی پوسته‌ها وجود دارند و باید runtime گرفته شوند. */
internal object ExtraPermissions {
    val OEM_APP_LIST = listOf(
        "com.android.permission.GET_INSTALLED_APPS",
        "android.permission.GET_INSTALLED_APPS",
    )

    fun oemAppsToRequest(context: Context): List<String> {
        return OEM_APP_LIST.filter { dangerous(context, it) }
    }

    fun dangerous(context: Context, name: String): Boolean {
        return try {
            val info = context.packageManager.getPermissionInfo(name, 0)
            info.protection == PermissionInfo.PROTECTION_DANGEROUS
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Throwable) {
            false
        }
    }
}
