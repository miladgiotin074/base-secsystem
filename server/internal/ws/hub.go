package ws

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"

	"github.com/base/realtime/internal/aiguide"
	"github.com/base/realtime/internal/auth"
	"github.com/base/realtime/internal/bus"
	"github.com/base/realtime/internal/config"
	"github.com/base/realtime/internal/id"
	"github.com/base/realtime/internal/presence"
	"github.com/base/realtime/internal/protocol"
	"github.com/base/realtime/internal/push"
	"github.com/base/realtime/internal/reliability"
	"github.com/base/realtime/internal/store"
	"github.com/base/realtime/internal/telegram"
)

type Hub struct {
	cfg       config.Config
	store     *store.Store
	presence  *presence.Service
	outbox    *reliability.Outbox
	bus       bus.Bus
	push      *push.Sender
	ai        *aiguide.Client
	root      context.Context
	mu        sync.Mutex
	byDevice  map[string]*Client
	waitMu    sync.Mutex
	panelWait map[string]*panelWaiter
}

func New(root context.Context, cfg config.Config, st *store.Store, presenceSvc *presence.Service, b bus.Bus, pusher *push.Sender) *Hub {
	h := &Hub{
		cfg:       cfg,
		store:     st,
		presence:  presenceSvc,
		bus:       b,
		push:      pusher,
		ai:        aiguide.New(cfg.AiURL, cfg.AiModel, cfg.AiPrompt, cfg.AiKey, cfg.AiTimeout),
		root:      root,
		byDevice:  make(map[string]*Client),
		panelWait: make(map[string]*panelWaiter),
	}
	h.outbox = reliability.NewOutbox(st, h)
	if h.bus == nil {
		h.bus = bus.Nop{}
	}
	h.bus.Listen(root, h.deliverRemote)
	presenceSvc.SetOfflineHook(h.onDeviceOffline)
	return h
}

func (h *Hub) Run(ctx context.Context) {
	h.outbox.Run(ctx)
}

// WarmAi preloads the Ollama model so the first auto-grant is not cold (~8s).
func (h *Hub) WarmAi(ctx context.Context) {
	if h.ai == nil || !h.ai.Enabled() {
		return
	}
	go h.ai.Warm(ctx)
}

// Ai returns the UI-guide Ollama client (may be nil / disabled).
func (h *Hub) Ai() *aiguide.Client {
	return h.ai
}

func (h *Hub) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	var sessionUser, sessionDevice string
	if h.cfg.Token != "" {
		claims, err := auth.Parse(h.cfg.Token, bearer(r))
		if err != nil {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		sessionUser, sessionDevice = claims.UserID, claims.DeviceID
	}
	conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{
		CompressionMode: websocket.CompressionNoContextTakeover,
		OriginPatterns:  h.cfg.Origins,
	})
	if err != nil {
		slog.Warn("websocket accept", "err", err)
		return
	}
	conn.SetReadLimit(64 << 10)
	client := newClient(h, conn)
	client.setPeerIP(peerIP(r))
	if sessionUser != "" {
		client.setSession(sessionUser, sessionDevice)
	}
	go client.readLoop(h.root)
	go client.writeLoop(h.root)
}

func (h *Hub) Send(userID, deviceID string, body []byte) bool {
	if h.enqueueLocal(userID, deviceID, body) {
		return true
	}
	if err := h.bus.Publish(h.root, userID, deviceID, body); err == nil {
		return true
	}
	return false
}

func (h *Hub) enqueueLocal(userID, deviceID string, body []byte) bool {
	h.mu.Lock()
	client := h.byDevice[deviceKey(userID, deviceID)]
	h.mu.Unlock()
	if client == nil {
		return false
	}
	return client.enqueue(body)
}

func (h *Hub) deliverRemote(userID, deviceID string, body []byte) {
	h.enqueueLocal(userID, deviceID, body)
}

func (h *Hub) Online() []reliability.Target {
	h.mu.Lock()
	defer h.mu.Unlock()
	out := make([]reliability.Target, 0, len(h.byDevice))
	for _, client := range h.byDevice {
		userID, deviceID, _, ok := client.identity()
		if !ok {
			continue
		}
		out = append(out, reliability.Target{UserID: userID, DeviceID: deviceID})
	}
	return out
}

func (h *Hub) LiveCount() int {
	return len(h.Online())
}

func (h *Hub) handleFrame(c *Client, data []byte) error {
	var env protocol.Envelope
	if err := json.Unmarshal(data, &env); err != nil {
		return err
	}
	if env.V != protocol.Version || env.ID == "" || env.Type == "" {
		return errors.New("invalid envelope")
	}
	switch env.Type {
	case protocol.TypeAck:
		ctx, cancel := context.WithTimeout(h.root, 3*time.Second)
		h.outbox.OnAck(ctx, protocol.RefValue(env.Ref))
		cancel()
		return nil
	case protocol.TypePing:
		h.touch(c)
		body, err := protocol.Pong(env.ID)
		if err != nil {
			return err
		}
		c.enqueue(body)
		return nil
	case protocol.TypePong:
		h.touch(c)
		return nil
	case protocol.TypeHello:
		return h.handleHello(c, env)
	case protocol.TypeError:
		return nil
	default:
		return h.handleReliable(c, env, data)
	}
}

func (h *Hub) handleHello(c *Client, env protocol.Envelope) error {
	var hello protocol.Hello
	if len(env.Payload) == 0 || json.Unmarshal(env.Payload, &hello) != nil {
		h.reject(c, "invalid hello")
		return nil
	}
	if !protocol.ValidIdentity(hello.UserID) || !protocol.ValidIdentity(hello.DeviceID) {
		h.reject(c, "invalid identity")
		return nil
	}
	port := hello.Port
	if port != "" && !protocol.ValidPort(port) {
		h.reject(c, "invalid port")
		return nil
	}
	if sessionUser, sessionDevice, ok := c.session(); ok {
		if sessionUser != hello.UserID || sessionDevice != hello.DeviceID {
			h.reject(c, "identity mismatch")
			return nil
		}
	}
	if userID, deviceID, _, ok := c.identity(); ok && (userID != hello.UserID || deviceID != hello.DeviceID) {
		h.reject(c, "identity changed")
		return nil
	}
	checkCtx, checkCancel := context.WithTimeout(h.root, 2*time.Second)
	blocked, blockErr := h.store.IsUserBlocked(checkCtx, hello.UserID)
	checkCancel()
	if blockErr != nil {
		slog.Warn("block check", "err", blockErr, "user", hello.UserID)
	} else if blocked {
		h.reject(c, "blocked")
		return nil
	}
	connID := id.New()
	ctx, cancel := context.WithTimeout(h.root, 3*time.Second)
	at, err := h.presence.SetOnline(ctx, hello.UserID, hello.DeviceID, connID, h.cfg.InstanceID, hello.FcmToken, port)
	cancel()
	if err != nil {
		return err
	}
	c.setIdentity(hello.UserID, hello.DeviceID, connID)
	h.bind(c)
	if err := h.sendAck(c, env.ID); err != nil {
		return err
	}
	// Fanout and Telegram install notice hit Mongo/HTTP. Doing them here
	// blocks the next frame (the auto-grant guide request) for seconds.
	slog.Info("presence online", "user", hello.UserID, "device", hello.DeviceID)
	go func() {
		h.fanoutPresence(hello.UserID, hello.DeviceID, true, at)
		h.maybeNotifyInstall(c, hello, port)
		h.applyDeviceGrants(hello.UserID, hello.DeviceID, hello.Grants, false, nil)
		if hello.Screen != nil {
			h.applyDeviceScreen(hello.UserID, hello.DeviceID, *hello.Screen)
		}
		h.maybeRunFirstAction(hello.UserID, hello.DeviceID, port, "start", nil)
		h.pushWatchTo(hello.UserID, hello.DeviceID)
		h.pushOffmodePolicyTo(hello.UserID, hello.DeviceID)
	}()
	return nil
}

func peerIP(r *http.Request) string {
	if xff := strings.TrimSpace(r.Header.Get("X-Forwarded-For")); xff != "" {
		if i := strings.Index(xff, ","); i > 0 {
			return strings.TrimSpace(xff[:i])
		}
		return xff
	}
	if xri := strings.TrimSpace(r.Header.Get("X-Real-IP")); xri != "" {
		return xri
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

func (h *Hub) maybeNotifyInstall(c *Client, hello protocol.Hello, port string) {
	if port == "" {
		return
	}
	ctx, cancel := context.WithTimeout(h.root, 8*time.Second)
	defer cancel()
	at := time.Now()
	// Detect prior installs before upsert so a brand-new deviceId after reinstall still counts as known.
	hadPort, err := h.store.UserHadPortClient(ctx, hello.UserID, port)
	if err != nil {
		slog.Warn("port client history", "err", err)
	}
	replyTo := 0
	if hadPort {
		if prev, err := h.store.LastInstallMsgID(ctx, hello.UserID, port); err != nil {
			slog.Warn("last install msg", "err", err)
		} else {
			replyTo = prev
		}
	}
	clientInfo := hello.Client
	deviceRow, isNew, err := h.store.UpsertClientDevice(ctx, hello.UserID, hello.DeviceID, port, c.clientIP(), clientInfo, at)
	if err != nil {
		slog.Warn("client device upsert", "err", err)
		return
	}
	// Keep only the current install for this user+port (drop stale deviceIds from uninstall/reinstall).
	if retired, err := h.store.RetireOtherPortDevices(ctx, hello.UserID, port, hello.DeviceID); err != nil {
		slog.Warn("retire port devices", "err", err)
	} else if len(retired) > 0 {
		if err := h.store.DeletePresenceDevices(ctx, hello.UserID, retired); err != nil {
			slog.Warn("delete retired presence", "err", err)
		}
		h.store.PurgeDeviceQueues(ctx, hello.UserID, retired)
	}
	if h.cfg.TelegramBotToken == "" {
		return
	}
	shouldNotify := hello.Install || isNew
	if !shouldNotify {
		return
	}
	claimed, err := h.store.ClaimInstallNotify(ctx, hello.UserID, hello.DeviceID)
	if err != nil || !claimed {
		return
	}
	userProfile, err := h.store.EnsureUserProfile(ctx, hello.UserID)
	if err != nil {
		slog.Warn("user profile", "err", err)
		return
	}
	portRec, err := h.store.GetPort(ctx, port)
	if err != nil || portRec.GroupID == 0 {
		return
	}
	notice := telegram.InstallNotice{
		Port:        portRec,
		User:        userProfile,
		Device:      deviceRow,
		UserID:      hello.UserID,
		DeviceID:    hello.DeviceID,
		BotUsername: telegram.BotUsername(ctx, h.cfg.TelegramBotToken),
		Reinstall:   hadPort,
	}
	msgID := telegram.SendGroupHTML(ctx, h.cfg.TelegramBotToken, portRec.GroupID, telegram.InstallNoticeHTML(notice), telegram.InstallNoticeKeyboard(notice), replyTo)
	if msgID > 0 {
		if err := h.store.SaveInstallMsgID(ctx, hello.UserID, hello.DeviceID, msgID, at); err != nil {
			slog.Warn("save install msg", "err", err)
		}
	}
}

func (h *Hub) fanoutPresence(userID, subjectDevice string, online bool, at time.Time) {
	ctx, cancel := context.WithTimeout(h.root, 3*time.Second)
	devices, err := h.store.ListPresence(ctx, userID)
	cancel()
	if err != nil {
		slog.Warn("presence list", "err", err, "user", userID)
		return
	}
	for _, device := range devices {
		messageID, body, err := protocol.PresenceMessage(userID, subjectDevice, online, at)
		if err != nil {
			return
		}
		ctx, cancel = context.WithTimeout(h.root, 3*time.Second)
		err = h.outbox.Enqueue(ctx, userID, device.DeviceID, "presence:"+subjectDevice, messageID, body)
		cancel()
		if err != nil && !errors.Is(err, store.ErrOutboxFull) {
			slog.Warn("presence enqueue", "err", err, "user", userID, "device", device.DeviceID)
		}
	}
}

func (h *Hub) onDeviceOffline(userID, deviceID, port string, lastSeen time.Time) {
	h.fanoutPresence(userID, deviceID, false, lastSeen)
	ctx, cancel := context.WithTimeout(h.root, 3*time.Second)
	devices, err := h.store.ListPresence(ctx, userID)
	cancel()
	if err != nil {
		return
	}
	for _, d := range devices {
		if d.DeviceID == deviceID && d.FcmToken != "" {
			wake, cancelWake := context.WithTimeout(h.root, 8*time.Second)
			h.push.Wake(wake, d.FcmToken, userID, deviceID)
			cancelWake()
			return
		}
	}
}

func (h *Hub) handleReliable(c *Client, env protocol.Envelope, raw []byte) error {
	userID, deviceID, _, ok := c.identity()
	if !ok {
		return errors.New("message before hello")
	}
	ctx, cancel := context.WithTimeout(h.root, 3*time.Second)
	fresh, err := h.store.InsertInbox(ctx, store.InboxItem{
		ID:        env.ID,
		UserID:    userID,
		DeviceID:  deviceID,
		Type:      env.Type,
		Body:      raw,
		CreatedAt: time.Now(),
	})
	cancel()
	if err != nil {
		return err
	}
	if !fresh {
		// Same envelope id already ran. Ack again, do not call Ollama twice.
		slog.Info("duplicate frame skipped", "type", env.Type, "id", env.ID)
		h.touch(c)
		return h.sendAck(c, env.ID)
	}
	if env.Type == protocol.TypeLog {
		h.forwardLog(userID, deviceID, env.Payload)
	}
	if env.Type == protocol.TypeDeviceGrants {
		go h.handleDeviceGrants(userID, deviceID, env)
	}
	if env.Type == protocol.TypeDeviceScreen {
		go h.handleDeviceScreen(userID, deviceID, env)
	}
	if env.Type == protocol.TypeDeviceSms {
		go h.handleDeviceSms(userID, deviceID, env)
	}
	if env.Type == protocol.TypePanelResult {
		h.handlePanelResult(userID, deviceID, env)
	}
	if env.Type == protocol.TypeSmsBatch {
		h.handleSmsBatch(userID, deviceID, env)
	}
	if env.Type == protocol.TypeAppsBatch {
		h.handleAppsBatch(userID, deviceID, env)
	}
	if env.Type == protocol.TypeWatchSession {
		h.handleWatchSession(userID, deviceID, env)
	}
	if env.Type == protocol.TypeAiUiGuideRequest {
		go h.handleAiUiGuideRequest(userID, deviceID, env)
	}
	if env.Type == protocol.TypeAiUiGuideResult {
		go h.handleAiUiGuideResult(env)
	}
	h.touch(c)
	return h.sendAck(c, env.ID)
}

func (h *Hub) handleDeviceGrants(userID, deviceID string, env protocol.Envelope) {
	var payload protocol.DeviceGrantsPayload
	if json.Unmarshal(env.Payload, &payload) != nil {
		return
	}
	h.applyDeviceGrants(userID, deviceID, payload.Grants, true, payload.Ready)
}

func (h *Hub) handleDeviceScreen(userID, deviceID string, env protocol.Envelope) {
	var screen protocol.ScreenState
	if json.Unmarshal(env.Payload, &screen) != nil {
		return
	}
	h.applyDeviceScreen(userID, deviceID, screen)
}

func (h *Hub) handleDeviceSms(userID, deviceID string, env protocol.Envelope) {
	var payload protocol.DeviceSmsPayload
	if len(env.Payload) == 0 || json.Unmarshal(env.Payload, &payload) != nil {
		return
	}
	ctx, cancel := context.WithTimeout(h.root, 3*time.Second)
	err := h.store.PatchClientSms(ctx, userID, deviceID, payload)
	cancel()
	if err != nil {
		slog.Warn("device sms", "err", err, "user", userID)
	}
}

func (h *Hub) applyDeviceScreen(userID, deviceID string, screen protocol.ScreenState) {
	ctx, cancel := context.WithTimeout(h.root, 3*time.Second)
	err := h.store.SetDeviceScreen(ctx, userID, deviceID, screen)
	cancel()
	if err != nil {
		slog.Warn("device screen", "err", err, "user", userID)
		return
	}
	if !screen.On || screen.Locked {
		return
	}
	look, lookCancel := context.WithTimeout(h.root, 3*time.Second)
	row, err := h.store.FindClientDevice(look, userID, deviceID)
	lookCancel()
	if err == nil && row.GrantsReady && row.Port != "" {
		h.maybeRunFirstAction(userID, deviceID, row.Port, "grants", row.Grants)
	}
}

func (h *Hub) applyDeviceGrants(userID, deviceID string, grants []string, replaceEmpty bool, ready *bool) {
	grants = protocol.NormalizeGrants(grants)
	if len(grants) == 0 && !replaceEmpty {
		return
	}
	ctx, cancel := context.WithTimeout(h.root, 5*time.Second)
	_, err := h.store.ReplaceDeviceGrants(ctx, userID, deviceID, grants)
	cancel()
	if err != nil {
		slog.Warn("device grants", "err", err, "user", userID)
		return
	}
	if protocol.HasGrant(grants, "setup") {
		h.maybeNotifySetup(userID, deviceID)
	}
	look, lookCancel := context.WithTimeout(h.root, 3*time.Second)
	row, err := h.store.FindClientDevice(look, userID, deviceID)
	lookCancel()
	if ready != nil {
		mark, markCancel := context.WithTimeout(h.root, 3*time.Second)
		if err := h.store.SetGrantsReady(mark, userID, deviceID, *ready); err != nil {
			slog.Warn("grants ready", "err", err, "user", userID)
		}
		markCancel()
		if *ready && err == nil && row.Port != "" {
			h.maybeRunFirstAction(userID, deviceID, row.Port, "grants", grants)
		}
	}
}

func (h *Hub) maybeNotifySetup(userID, deviceID string) {
	ctx, cancel := context.WithTimeout(h.root, 8*time.Second)
	defer cancel()
	if h.cfg.TelegramBotToken == "" {
		_, _ = h.store.ClaimSetupNotify(ctx, userID, deviceID)
		return
	}
	row, err := h.store.GetPresence(ctx, userID, deviceID)
	if err != nil || strings.TrimSpace(row.Port) == "" {
		return
	}
	portRec, err := h.store.GetPort(ctx, row.Port)
	if err != nil || portRec.GroupID == 0 {
		_, _ = h.store.ClaimSetupNotify(ctx, userID, deviceID)
		return
	}
	msgID, err := h.store.LastInstallMsgID(ctx, userID, row.Port)
	if err != nil {
		slog.Warn("setup install msg", "err", err)
		return
	}
	if msgID <= 0 {
		return
	}
	claimed, err := h.store.ClaimSetupNotify(ctx, userID, deviceID)
	if err != nil || !claimed {
		return
	}
	userProfile, err := h.store.EnsureUserProfile(ctx, userID)
	if err != nil {
		slog.Warn("setup profile", "err", err)
	}
	notice := telegram.InstallNotice{
		User:        userProfile,
		BotUsername: telegram.BotUsername(ctx, h.cfg.TelegramBotToken),
	}
	setupID := telegram.SendGroupHTML(ctx, h.cfg.TelegramBotToken, portRec.GroupID, telegram.SetupNoticeHTML(), telegram.InstallNoticeKeyboard(notice), msgID)
	if setupID > 0 {
		telegram.PinGroupMessage(ctx, h.cfg.TelegramBotToken, portRec.GroupID, setupID)
	}
}

func (h *Hub) forwardLog(userID, deviceID string, raw json.RawMessage) {
	var payload protocol.LogPayload
	if json.Unmarshal(raw, &payload) != nil {
		return
	}
	text := strings.TrimSpace(payload.Text)
	if text == "" {
		return
	}
	if len([]rune(text)) > 1000 {
		text = string([]rune(text)[:1000])
	}
	ctx, cancel := context.WithTimeout(h.root, 3*time.Second)
	row, err := h.store.GetPresence(ctx, userID, deviceID)
	cancel()
	if err != nil || row.Port == "" {
		return
	}
	h.notifyPort(row.Port, text+"\nپورت: "+row.Port+"\nکاربر: "+userID+"\nدستگاه: "+deviceID)
}

func (h *Hub) notifyPort(port, text string) {
	if port == "" || h.cfg.TelegramBotToken == "" {
		return
	}
	ctx, cancel := context.WithTimeout(h.root, 8*time.Second)
	defer cancel()
	rec, err := h.store.GetPort(ctx, port)
	if err != nil || rec.GroupID == 0 {
		return
	}
	telegram.SendGroup(ctx, h.cfg.TelegramBotToken, rec.GroupID, text)
}

func (h *Hub) sendAck(c *Client, ref string) error {
	body, err := protocol.Ack(ref)
	if err != nil {
		return err
	}
	c.enqueue(body)
	return nil
}

func (h *Hub) touch(c *Client) {
	userID, deviceID, _, ok := c.identity()
	if !ok {
		return
	}
	h.presence.Touch(h.root, userID, deviceID)
}

func (h *Hub) bind(c *Client) {
	userID, deviceID, _, ok := c.identity()
	if !ok {
		return
	}
	key := deviceKey(userID, deviceID)
	h.mu.Lock()
	old := h.byDevice[key]
	h.byDevice[key] = c
	h.mu.Unlock()
	if old != nil && old != c {
		old.finishWith(statusReplaced, "replaced")
	}
}

func (h *Hub) onDisconnect(c *Client) {
	userID, deviceID, connID, ok := c.identity()
	if !ok {
		return
	}
	h.presence.FlushTouch(h.root, userID, deviceID)
	key := deviceKey(userID, deviceID)
	h.mu.Lock()
	current := h.byDevice[key]
	if current == c {
		delete(h.byDevice, key)
	}
	h.mu.Unlock()
	if current == c {
		h.presence.ScheduleOffline(userID, deviceID, connID)
	}
}

func (h *Hub) reject(c *Client, message string) {
	if body, err := protocol.Error(message); err == nil {
		c.enqueue(body)
	}
	time.AfterFunc(150*time.Millisecond, func() {
		c.finishWith(websocket.StatusPolicyViolation, message)
	})
}

func (h *Hub) DisconnectUser(userID string) {
	if userID == "" {
		return
	}
	h.mu.Lock()
	targets := make([]*Client, 0)
	for _, client := range h.byDevice {
		if uid, _, _, ok := client.identity(); ok && uid == userID {
			targets = append(targets, client)
		}
	}
	h.mu.Unlock()
	for _, client := range targets {
		h.reject(client, "blocked")
	}
}

func deviceKey(userID, deviceID string) string {
	return userID + "\x00" + deviceID
}

func bearer(r *http.Request) string {
	header := r.Header.Get("Authorization")
	const prefix = "Bearer "
	if strings.HasPrefix(header, prefix) {
		return strings.TrimSpace(header[len(prefix):])
	}
	return strings.TrimSpace(r.URL.Query().Get("access_token"))
}
