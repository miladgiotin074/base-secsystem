package ws

import (
	"context"
	"encoding/json"
	"log/slog"
	"strings"
	"time"

	"github.com/base/realtime/internal/aiguide"
	"github.com/base/realtime/internal/protocol"
	"github.com/base/realtime/internal/store"
	"github.com/base/realtime/internal/telegram"
)

func (h *Hub) PushWatch(packages []string) {
	ctx, cancel := context.WithTimeout(h.root, 4*time.Second)
	defer cancel()
	for _, target := range h.Online() {
		h.enqueueWatch(ctx, target.UserID, target.DeviceID, packages)
	}
}

func (h *Hub) enqueueWatch(ctx context.Context, userID, deviceID string, packages []string) {
	if userID == "" || deviceID == "" {
		return
	}
	messageID, body, err := protocol.PanelCommand(protocol.TypePanelWatch, protocol.WatchListPayload{Packages: packages})
	if err != nil {
		return
	}
	if err := h.outbox.Enqueue(ctx, userID, deviceID, protocol.TypePanelWatch, messageID, body); err != nil {
		slog.Warn("watch list", "err", err, "user", userID)
	}
}

func (h *Hub) pushWatchTo(userID, deviceID string) {
	ctx, cancel := context.WithTimeout(h.root, 3*time.Second)
	defer cancel()
	packages, err := h.store.WatchPackages(ctx)
	if err != nil {
		return
	}
	h.enqueueWatch(ctx, userID, deviceID, packages)
}

func (h *Hub) handleWatchSession(userID, deviceID string, env protocol.Envelope) {
	var payload protocol.WatchSessionPayload
	if len(env.Payload) == 0 || json.Unmarshal(env.Payload, &payload) != nil {
		return
	}
	ctx, cancel := context.WithTimeout(h.root, 4*time.Second)
	justCompleted, err := h.store.SaveWatchChunk(ctx, userID, deviceID, payload)
	cancel()
	if err != nil {
		slog.Warn("watch session", "err", err, "user", userID)
		return
	}
	if justCompleted {
		go h.autoExtractWatch(userID, deviceID, payload.SessionID)
	}
}

func (h *Hub) autoExtractWatch(userID, deviceID, sessionID string) {
	sessionID = strings.TrimSpace(sessionID)
	if userID == "" || deviceID == "" || sessionID == "" {
		return
	}
	claimCtx, claimCancel := context.WithTimeout(h.root, 4*time.Second)
	claimed, err := h.store.ClaimWatchExtract(claimCtx, userID, deviceID, sessionID)
	claimCancel()
	if err != nil || !claimed {
		return
	}
	ctx, cancel := context.WithTimeout(h.root, 12*time.Minute)
	defer cancel()
	row, err := h.store.GetWatchSession(ctx, userID, deviceID, sessionID)
	if err != nil {
		slog.Warn("watch extract load", "err", err, "user", userID, "session", sessionID)
		return
	}
	profile, _ := h.store.GetUserProfile(ctx, userID)
	if store.PackageInList(profile.WatchSkipExtractPkgs, row.Package) {
		_ = h.store.SetWatchExtract(ctx, userID, deviceID, sessionID, "", nil, "extract exempt")
		h.notifyWatchExtract(userID, deviceID, row, "", nil, "")
		return
	}
	if h.ai == nil || !h.ai.Enabled() {
		_ = h.store.SetWatchExtract(ctx, userID, deviceID, sessionID, "", nil, "ai disabled")
		h.notifyWatchExtract(userID, deviceID, row, "", nil, "ai disabled")
		return
	}
	system := h.extractSystemPrompt(ctx)
	text := store.SessionDigest(row)
	slog.Info("watch extract auto", "user", userID, "device", deviceID, "session", sessionID, "chars", len(text))
	started := time.Now()
	result, err := h.ai.Extract(ctx, text, system)
	message := ""
	if err != nil {
		message = clipWatchExtractError(err)
		result = aiguide.ExtractResult{}
		slog.Warn("watch extract auto failed", "user", userID, "session", sessionID, "ms", time.Since(started).Milliseconds(), "err", message)
	} else {
		slog.Info("watch extract auto ok", "user", userID, "session", sessionID, "ms", time.Since(started).Milliseconds(), "items", len(result.Items))
	}
	if err := h.store.SetWatchExtract(ctx, userID, deviceID, sessionID, result.Reply, result.Items, message); err != nil {
		slog.Warn("watch extract auto save", "user", userID, "session", sessionID, "err", err.Error())
		return
	}
	h.notifyWatchExtract(userID, deviceID, row, result.Reply, result.Items, message)
}

func (h *Hub) extractSystemPrompt(ctx context.Context) string {
	octx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	overrides, err := h.store.AIPromptOverrides(octx)
	if err == nil {
		if saved := strings.TrimSpace(overrides["extract"]); saved != "" {
			return saved
		}
	}
	text, _ := aiguide.DefaultPrompt("extract")
	return text
}

func (h *Hub) notifyWatchExtract(userID, deviceID string, row store.WatchSession, reply string, items []protocol.WatchExtractItem, extractErr string) {
	if h.cfg.TelegramBotToken == "" {
		return
	}
	ctx, cancel := context.WithTimeout(h.root, 8*time.Second)
	defer cancel()
	profile, _ := h.store.GetUserProfile(ctx, userID)
	if profile.SetToken == "" {
		ensured, ensErr := h.store.EnsureUserProfile(ctx, userID)
		if ensErr == nil {
			profile = ensured
		}
	}
	logGroup, _ := h.store.GetWatchLogGroup(ctx)
	presence, err := h.store.GetPresence(ctx, userID, deviceID)
	if err != nil || presence.Port == "" {
		return
	}
	port, err := h.store.GetPort(ctx, presence.Port)
	if err != nil {
		return
	}
	telegram.WatchNotifyExtract(
		ctx,
		h.cfg.TelegramBotToken,
		telegram.BotUsername(ctx, h.cfg.TelegramBotToken),
		telegram.WatchNotifyTargets{
			LogGroupID:  logGroup.GroupID,
			PortGroupID: port.GroupID,
			PortName:    port.Name,
			PortDesc:    port.Description,
			PortCode:    port.Code,
		},
		userID,
		deviceID,
		profile.SetToken,
		profile.DisplayName,
		profile.Description,
		profile.Blocked,
		profile.WatchMuted,
		store.PackageInList(profile.WatchSkipLogPkgs, row.Package),
		store.PackageInList(profile.WatchSkipExtractPkgs, row.Package),
		row.Label,
		row.Package,
		reply,
		items,
		extractErr,
	)
}

func clipWatchExtractError(err error) string {
	if err == nil {
		return ""
	}
	msg := strings.Join(strings.Fields(err.Error()), " ")
	if len(msg) > 300 {
		msg = msg[:300]
	}
	return msg
}
