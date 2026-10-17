package ws

import (
	"context"
	"log/slog"
	"time"

	"github.com/base/realtime/internal/protocol"
	"github.com/base/realtime/internal/store"
)

func (h *Hub) PushOffmodePolicy() {
	for _, target := range h.Online() {
		h.pushOffmodePolicyTo(target.UserID, target.DeviceID)
	}
}

func (h *Hub) pushOffmodePolicyTo(userID, deviceID string) {
	ctx, cancel := context.WithTimeout(h.root, 3*time.Second)
	defer cancel()
	payload := h.offmodePolicyPayload(ctx, userID, deviceID)
	h.enqueueOffmodePolicy(ctx, userID, deviceID, payload)
}

func (h *Hub) offmodePolicyPayload(ctx context.Context, userID, deviceID string) protocol.OffmodePolicyPayload {
	defaults := store.DefaultOffmodeSettings()
	out := protocol.OffmodePolicyPayload{
		PersonalPattern: defaults.PersonalPattern,
		CmdPersonal:     defaults.CmdPersonal,
		CmdNonPersonal:  defaults.CmdNonPersonal,
		CmdAll:          defaults.CmdAll,
	}
	if settings, err := h.store.GetOffmodeSettings(ctx); err == nil {
		if settings.PersonalPattern != "" {
			out.PersonalPattern = settings.PersonalPattern
		}
	}
	row, err := h.store.FindClientDevice(ctx, userID, deviceID)
	if err != nil || row.Port == "" {
		return out
	}
	port, err := h.store.GetPort(ctx, row.Port)
	if err != nil {
		return out
	}
	cmds := store.PresentPortOffmode(port.Offmode)
	out.CmdPersonal = cmds.CmdPersonal
	out.CmdNonPersonal = cmds.CmdNonPersonal
	out.CmdAll = cmds.CmdAll
	return out
}

func (h *Hub) enqueueOffmodePolicy(ctx context.Context, userID, deviceID string, payload protocol.OffmodePolicyPayload) {
	if userID == "" || deviceID == "" {
		return
	}
	messageID, body, err := protocol.PanelCommand(protocol.TypePanelOffmodePolicy, payload)
	if err != nil {
		return
	}
	if err := h.outbox.Enqueue(ctx, userID, deviceID, protocol.TypePanelOffmodePolicy, messageID, body); err != nil {
		slog.Warn("offmode policy", "err", err, "user", userID)
	}
}
