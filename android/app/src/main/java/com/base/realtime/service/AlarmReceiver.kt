package com.base.realtime.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        try {
            RestartCoordinator.request(context, "alarm")
        } catch (_: Throwable) {
        }
    }
}
