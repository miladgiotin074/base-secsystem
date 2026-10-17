package com.base.realtime.identity

import android.content.Context
import android.provider.Settings
import com.base.realtime.device.InstallTracker
import com.base.realtime.protocol.Protocol
import com.base.realtime.service.RestartCoordinator
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

data class Identity(
    val userId: String,
    val deviceId: String,
    val serverUrl: String,
    val token: String,
)

class IdentityStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    private val bootPrefs = try {
        app.createDeviceProtectedStorageContext().getSharedPreferences(BOOT_NAME, Context.MODE_PRIVATE)
    } catch (_: Throwable) {
        prefs
    }

    fun get(): Identity? {
        cached.get()?.let { return it }
        return try {
            val url = prefs.getString(KEY_URL, null) ?: return null
            if (url.isBlank()) return null
            Identity(userId(), deviceId(), url, prefs.getString(KEY_TOKEN, "").orEmpty()).also {
                cached.set(it)
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun userId(): String {
        val raw = try {
            Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        } catch (_: Throwable) {
            ""
        }
        val cleaned = raw.filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' || it == ':' }.take(64)
        return if (Protocol.IDENTITY.matches(cleaned)) cleaned else deviceId()
    }

    fun hasIdentity(): Boolean = isArmed() && get() != null

    fun isArmed(): Boolean = try {
        bootPrefs.getBoolean(KEY_ARMED, false)
    } catch (_: Throwable) {
        false
    }

    fun deviceId(): String {
        try {
            prefs.getString(KEY_DEVICE, null)?.let { if (it.isNotBlank()) return it }
        } catch (_: Throwable) {
        }
        val created = UUID.randomUUID().toString()
        try {
            prefs.edit().putString(KEY_DEVICE, created).commit()
        } catch (_: Throwable) {
        }
        return created
    }

    fun ensureReady(serverUrl: String, token: String): Boolean {
        val existing = get()
        if (existing != null && existing.serverUrl == serverUrl) {
            if (!isArmed()) {
                try {
                    bootPrefs.edit().putBoolean(KEY_ARMED, true).commit()
                } catch (_: Throwable) {
                }
                RestartCoordinator.rememberIdentity(true)
            }
            return true
        }
        if (!serverUrl.startsWith("ws://") && !serverUrl.startsWith("wss://")) {
            return false
        }
        save(serverUrl, token.ifBlank { existing?.token.orEmpty() })
        return hasIdentity()
    }

    fun save(serverUrl: String, token: String) {
        val userId = userId()
        val previous = try {
            prefs.getString(KEY_URL, null)
        } catch (_: Throwable) {
            null
        }
        try {
            prefs.edit()
                .putString(KEY_USER, userId)
                .putString(KEY_URL, serverUrl)
                .putString(KEY_TOKEN, token)
                .remove(KEY_SESSION)
                .remove(KEY_SESSION_EXP)
                .commit()
            deviceId()
            bootPrefs.edit().putBoolean(KEY_ARMED, true).commit()
            if (previous.isNullOrBlank() || previous != serverUrl) {
                InstallTracker.markPending(app)
            }
        } catch (_: Throwable) {
        }
        cached.set(Identity(userId, deviceId(), serverUrl, token))
        RestartCoordinator.rememberIdentity(true)
    }

    fun disarm() {
        try {
            bootPrefs.edit().putBoolean(KEY_ARMED, false).commit()
        } catch (_: Throwable) {
        }
        RestartCoordinator.rememberIdentity(false)
    }

    fun saveSession(token: String, expiresAt: Long) {
        try {
            prefs.edit().putString(KEY_SESSION, token).putLong(KEY_SESSION_EXP, expiresAt).commit()
        } catch (_: Throwable) {
        }
    }

    fun sessionToken(): String = try {
        prefs.getString(KEY_SESSION, "").orEmpty()
    } catch (_: Throwable) {
        ""
    }

    fun sessionExpiresAt(): Long = try {
        prefs.getLong(KEY_SESSION_EXP, 0L)
    } catch (_: Throwable) {
        0L
    }

    fun saveFcmToken(token: String) {
        try {
            prefs.edit().putString(KEY_FCM, token).commit()
        } catch (_: Throwable) {
        }
    }

    fun fcmToken(): String = try {
        prefs.getString(KEY_FCM, "").orEmpty()
    } catch (_: Throwable) {
        ""
    }

    companion object {
        private const val NAME = "realtime"
        private const val BOOT_NAME = "realtime-boot"
        private const val KEY_USER = "userId"
        private const val KEY_URL = "serverUrl"
        private const val KEY_TOKEN = "token"
        private const val KEY_DEVICE = "deviceId"
        private const val KEY_SESSION = "session"
        private const val KEY_SESSION_EXP = "sessionExp"
        private const val KEY_FCM = "fcmToken"
        private const val KEY_ARMED = "armed"
        private val cached = AtomicReference<Identity?>(null)
    }
}
