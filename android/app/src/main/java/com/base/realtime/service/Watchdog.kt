package com.base.realtime.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object Watchdog {
    private const val TAG = "Realtime"
    private const val INTERVAL_MS = 15 * 60 * 1000L
    private const val WORK_NAME = "realtime-watchdog"
    private const val PERIODIC_REQUEST = 1001
    private const val SOON_REQUEST = 1002

    private val destroyCount = AtomicInteger(0)
    private val destroyWindowStart = AtomicLong(0)

    fun schedule(context: Context) {
        val app = context.applicationContext
        scheduleAlarm(app, PERIODIC_REQUEST, INTERVAL_MS)
        scheduleWork(app)
    }

    fun scheduleBackup(context: Context) {
        scheduleWork(context.applicationContext)
    }

    fun scheduleSoon(context: Context) {
        val now = SystemClock.elapsedRealtime()
        val window = destroyWindowStart.get()
        if (now - window > 60_000L) {
            destroyWindowStart.set(now)
            destroyCount.set(0)
        }
        val n = destroyCount.incrementAndGet().coerceAtMost(6)
        val delay = (5_000L shl (n - 1).coerceAtMost(3)).coerceAtMost(60_000L)
        scheduleAlarm(context.applicationContext, SOON_REQUEST, delay)
        schedule(context)
    }

    private fun scheduleAlarm(context: Context, requestCode: Int, delayMs: Long) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = System.currentTimeMillis() + delayMs.coerceAtLeast(3_000L)
        val pending = pending(context, requestCode)
        try {
            val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
            if (exactAllowed) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "alarm schedule", t)
            try {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            } catch (t2: Throwable) {
                Log.w(TAG, "inexact alarm", t2)
            }
        }
    }

    private fun scheduleWork(context: Context) {
        try {
            val request = PeriodicWorkRequest.Builder(WatchdogWorker::class.java, 15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "work schedule", t)
        }
    }

    private fun pending(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }
}

class WatchdogWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {
    override fun doWork(): Result {
        try {
            RestartCoordinator.request(applicationContext, "watchdog")
        } catch (_: Throwable) {
        }
        return Result.success()
    }
}
