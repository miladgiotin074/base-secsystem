package com.base.realtime.device

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import com.base.realtime.RealtimeApp
import com.base.realtime.protocol.Protocol
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class ScreenSnapshot(val on: Boolean, val locked: Boolean)

/**
 * Reads the screen only when the system says it changed. Nothing is sent
 * while the same on/locked pair is already the last one reported.
 */
object ScreenState {
    private val watching = AtomicBoolean(false)
    private val gate = Any()
    @Volatile private var reported: String? = null

    fun read(context: Context): ScreenSnapshot {
        val app = context.applicationContext
        val on = try {
            app.getSystemService(PowerManager::class.java)?.isInteractive == true
        } catch (_: Throwable) {
            false
        }
        val locked = try {
            app.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false
        } catch (_: Throwable) {
            true
        }
        return ScreenSnapshot(on = on, locked = locked)
    }

    fun json(snapshot: ScreenSnapshot): JsonObject = buildJsonObject {
        put("on", snapshot.on)
        put("locked", snapshot.locked)
    }

    /** Hello carries this snapshot, so a matching pending frame is dropped by the caller. */
    fun remember(snapshot: ScreenSnapshot) {
        synchronized(gate) {
            reported = key(snapshot)
        }
    }

    fun watch(context: Context) {
        if (!watching.compareAndSet(false, true)) return
        val app = context.applicationContext
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                publish(ctx?.applicationContext ?: app)
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
            watching.set(false)
        }
    }

    fun publish(context: Context) {
        val snapshot = read(context)
        synchronized(gate) {
            val next = key(snapshot)
            if (reported == next) return
            reported = next
        }
        RealtimeApp.engine(context).enqueue(
            Protocol.TYPE_DEVICE_SCREEN,
            json(snapshot),
            dedupeKey = Protocol.TYPE_DEVICE_SCREEN,
        )
    }

    private fun key(snapshot: ScreenSnapshot): String {
        return "${if (snapshot.on) 1 else 0}${if (snapshot.locked) 1 else 0}"
    }
}
