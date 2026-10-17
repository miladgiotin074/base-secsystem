package com.base.realtime.access

import android.os.Build
import com.base.realtime.R

/** گروه‌بندی برند برای مسیر تنظیمات؛ تشخیص از MANUFACTURER و BRAND. */
internal enum class PhoneFamily {
    SAMSUNG,
    XIAOMI,
    HUAWEI,
    HONOR,
    OPPO,
    VIVO,
    TRANSSION,
    STOCK;

    fun labelRes(): Int = when (this) {
        SAMSUNG -> R.string.perm_family_samsung
        XIAOMI -> R.string.perm_family_xiaomi
        HUAWEI -> R.string.perm_family_huawei
        HONOR -> R.string.perm_family_honor
        OPPO -> R.string.perm_family_oppo
        VIVO -> R.string.perm_family_vivo
        TRANSSION -> R.string.perm_family_transsion
        STOCK -> R.string.perm_family_stock
    }

    fun overlayTitleRes(): Int = when (this) {
        SAMSUNG -> R.string.perm_overlay_title_samsung
        XIAOMI -> R.string.perm_overlay_title_xiaomi
        HUAWEI -> R.string.perm_overlay_title_huawei
        HONOR -> R.string.perm_overlay_title_honor
        OPPO -> R.string.perm_overlay_title_oppo
        VIVO -> R.string.perm_overlay_title_vivo
        TRANSSION -> R.string.perm_overlay_title_transsion
        STOCK -> R.string.perm_overlay_title_stock
    }

    companion object {
        fun current(): PhoneFamily {
            val blob = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase()
            return when {
                "samsung" in blob -> SAMSUNG
                listOf("xiaomi", "redmi", "poco", "blackshark").any { it in blob } -> XIAOMI
                "honor" in blob -> HONOR
                "huawei" in blob -> HUAWEI
                listOf("oppo", "realme", "oneplus").any { it in blob } -> OPPO
                listOf("vivo", "iqoo").any { it in blob } -> VIVO
                listOf("tecno", "infinix", "itel").any { it in blob } -> TRANSSION
                else -> STOCK
            }
        }
    }
}
