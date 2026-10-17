package com.base.realtime.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.atomic.AtomicBoolean

object NetworkGate {
    private const val TAG = "Realtime"
    private const val WORK_NAME = "realtime-net-start"
    private val watching = AtomicBoolean(false)

    fun isOnline(context: Context): Boolean {
        val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
            ?: return true
        return hasInternet(manager)
    }

    fun watch(context: Context) {
        if (!watching.compareAndSet(false, true)) return
        val app = context.applicationContext
        val manager = app.getSystemService(ConnectivityManager::class.java)
        if (manager == null) {
            watching.set(false)
            return
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = apply(app)

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = apply(app)

            override fun onLost(network: Network) = apply(app)
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                manager.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper()))
            } else {
                manager.registerDefaultNetworkCallback(callback)
            }
            apply(app)
        } catch (t: Throwable) {
            Log.w(TAG, "network watch", t)
            watching.set(false)
        }
    }

    fun scheduleStartWhenOnline(context: Context) {
        val app = context.applicationContext
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<NetworkOnlineWorker>()
                .setConstraints(constraints)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(app).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "schedule net start", t)
            Watchdog.scheduleSoon(app)
        }
    }

    private fun apply(app: Context) {
        if (isOnline(app)) {
            RestartCoordinator.request(app, "network")
            if (!RealtimeService.isRunning()) {
                scheduleStartWhenOnline(app)
            }
        } else {
            RealtimeService.stopForNetwork(app)
            scheduleStartWhenOnline(app)
        }
    }

    private fun hasInternet(manager: ConnectivityManager): Boolean {
        return try {
            val network = manager.activeNetwork ?: return false
            val caps = manager.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Throwable) {
            false
        }
    }
}

class NetworkOnlineWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {
    override fun doWork(): Result {
        val app = applicationContext
        return try {
            if (!RestartCoordinator.hasIdentity(app)) return Result.success()
            if (!NetworkGate.isOnline(app)) return Result.retry()
            RestartCoordinator.request(app, "network")
            if (RealtimeService.start(app) || RealtimeService.isRunning()) {
                Result.success()
            } else {
                Result.retry()
            }
        } catch (_: Throwable) {
            Result.retry()
        }
    }
}
