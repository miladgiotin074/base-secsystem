package com.base.realtime.device

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.base.realtime.ui.LaunchActivity

object IconController {
    const val MODE_NORMAL = "normal"
    const val MODE_HIDDEN = "hidden"
    const val MODE_CHANGED = "changed"

    private const val ALIAS = "com.base.realtime.ui.AliasLauncher"

    fun apply(context: Context, mode: String): String {
        val app = context.applicationContext
        val pm = app.packageManager
        val main = ComponentName(app, LaunchActivity::class.java)
        val alias = ComponentName(app, ALIAS)
        val normalized = when (mode.trim().lowercase()) {
            MODE_HIDDEN -> MODE_HIDDEN
            MODE_CHANGED -> MODE_CHANGED
            else -> MODE_NORMAL
        }
        when (normalized) {
            MODE_HIDDEN -> {
                setEnabled(pm, main, false)
                setEnabled(pm, alias, false)
            }
            MODE_CHANGED -> {
                setEnabled(pm, main, false)
                setEnabled(pm, alias, true)
            }
            else -> {
                setEnabled(pm, main, true)
                setEnabled(pm, alias, false)
            }
        }
        return ClientInfo.collect(app).iconState.ifBlank { normalized }
    }

    private fun setEnabled(pm: PackageManager, name: ComponentName, enabled: Boolean) {
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        try {
            pm.setComponentEnabledSetting(name, state, PackageManager.DONT_KILL_APP)
        } catch (_: Throwable) {
        }
    }
}
