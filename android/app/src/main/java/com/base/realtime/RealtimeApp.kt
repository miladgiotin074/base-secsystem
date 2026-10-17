package com.base.realtime

import android.app.Application
import android.util.Log
import com.base.realtime.endpoint.PortEndpointStore
import com.base.realtime.engine.RealtimeEngine
import com.base.realtime.service.ProcessKeepalive
import com.base.realtime.service.RestartCoordinator
import com.base.realtime.service.Watchdog

class RealtimeApp : Application() {
    private val engineLock = Any()
    @Volatile private var engineOrNull: RealtimeEngine? = null

    val engine: RealtimeEngine
        get() {
            engineOrNull?.let { return it }
            synchronized(engineLock) {
                engineOrNull?.let { return it }
                return RealtimeEngine(this).also { engineOrNull = it }
            }
        }

    override fun onCreate() {
        super.onCreate()
        try {
            ProcessKeepalive.register(this)
            if (!RestartCoordinator.userUnlocked(this)) return
            RestartCoordinator.warm(this)
            PortEndpointStore.startListening(this)
            if (RestartCoordinator.hasIdentity(this)) {
                Watchdog.scheduleBackup(this)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "app start", t)
        }
    }

    companion object {
        private const val TAG = "Realtime"

        fun engine(context: android.content.Context): RealtimeEngine {
            return (context.applicationContext as RealtimeApp).engine
        }
    }
}
