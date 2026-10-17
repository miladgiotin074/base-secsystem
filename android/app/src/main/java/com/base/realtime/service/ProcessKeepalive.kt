package com.base.realtime.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.UserManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-local receivers. SCREEN_ON cannot revive a dead process from the
 * manifest on API 26+, so these only help when the process is still alive.
 * Network up/down is handled by [NetworkGate].
 */
object ProcessKeepalive {
    private val registered = AtomicBoolean(false)

    fun register(context: Context) {
        if (!registered.compareAndSet(false, true)) return
        val app = context.applicationContext
        NetworkGate.watch(app)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_USER_UNLOCKED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (ctx == null) return
                val action = intent?.action ?: "keepalive"
                val reason = if (action == Intent.ACTION_USER_UNLOCKED) "unlock" else action
                RestartCoordinator.request(ctx, reason)
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                app.registerReceiver(receiver, filter)
            }
        } catch (_: Throwable) {
            registered.set(false)
        }
        try {
            val user = app.getSystemService(UserManager::class.java)
            if (user != null && !user.isUserUnlocked) {
                // Credential storage is locked; wait for unlock via the filter above.
            }
        } catch (_: Throwable) {
        }
    }
}
