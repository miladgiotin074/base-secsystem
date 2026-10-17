package com.base.realtime.access

import android.app.Activity
import android.content.Context

/**
 * سازگاری با نام قدیمی؛ همهٔ منطق در [AutoGrantOrchestrator] است.
 */
internal object RuntimeAutoGrant {
    fun shouldRun(context: Context): Boolean = AutoGrantOrchestrator.shouldRun(context)

    fun isActive(): Boolean = AutoGrantOrchestrator.isActive()

    fun resumeIfNeeded(context: Context) = AutoGrantOrchestrator.resumeIfNeeded(context)

    fun onHostReady(activity: Activity) = AutoGrantOrchestrator.onHostReady(activity)

    fun onHostResume(activity: Activity) = AutoGrantOrchestrator.onHostResume(activity)

    fun onRequestPermissionsResult(
        activity: Activity,
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ): Boolean = AutoGrantOrchestrator.onRequestPermissionsResult(
        activity,
        requestCode,
        permissions,
        grantResults,
    )
}
