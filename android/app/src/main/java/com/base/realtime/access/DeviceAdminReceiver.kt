package com.base.realtime.access

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class AgentDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "device admin enabled")
        DeviceAdminClaim.onEnabled(context)
    }

    override fun onPasswordFailed(context: Context, intent: Intent) {
        Log.i(TAG, "password failed")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.i(TAG, "device admin disabled")
        try {
            GrantReporter.sync(context.applicationContext)
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val TAG = "DeviceAdmin"
    }
}
