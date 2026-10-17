package com.base.realtime.service

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.os.UserManager
import android.util.Log
import com.base.realtime.identity.IdentityStore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object RestartCoordinator {
    private const val TAG = "Realtime"
    private const val DEBOUNCE_MS = 2_000L
    private val identityKnown = AtomicBoolean(false)
    private val identityCached = AtomicBoolean(false)
    private val lastAttempt = AtomicLong(0)

    fun warm(context: Context) {
        try {
            rememberIdentity(IdentityStore(context.applicationContext).hasIdentity())
        } catch (t: Throwable) {
            Log.w(TAG, "identity warm", t)
        }
    }

    fun rememberIdentity(has: Boolean) {
        identityKnown.set(true)
        identityCached.set(has)
    }

    fun hasIdentity(context: Context): Boolean {
        if (identityKnown.get()) return identityCached.get()
        return try {
            IdentityStore(context.applicationContext).hasIdentity().also { rememberIdentity(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "identity read", t)
            false
        }
    }

    fun userUnlocked(context: Context): Boolean {
        return try {
            val users = context.applicationContext.getSystemService(UserManager::class.java)
            users == null || users.isUserUnlocked
        } catch (_: Throwable) {
            true
        }
    }

    fun request(context: Context, reason: String) {
        val app = context.applicationContext
        try {
            if (!hasIdentity(app)) return
            if (!userUnlocked(app)) {
                Watchdog.scheduleSoon(app)
                return
            }
            if (!NetworkGate.isOnline(app)) return
            val force = isForce(reason)
            if (RealtimeService.isRunning() && !force) return
            val now = SystemClock.elapsedRealtime()
            if (!force && now - lastAttempt.get() < DEBOUNCE_MS) return
            lastAttempt.set(now)
            if (!RealtimeService.start(app)) {
                Watchdog.scheduleSoon(app)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "restart $reason", t)
            Watchdog.scheduleSoon(app)
        }
    }

    private fun isForce(reason: String): Boolean {
        return reason == "boot" ||
            reason == "alarm" ||
            reason == "watchdog" ||
            reason == "destroy" ||
            reason == "fcm" ||
            reason == "unlock" ||
            reason == "network" ||
            reason == Intent.ACTION_USER_UNLOCKED ||
            reason.contains("BOOT", ignoreCase = true)
    }
}
