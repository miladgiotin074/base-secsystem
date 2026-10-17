package ws

import (
	"context"
	"log/slog"
	"time"

	"github.com/base/realtime/internal/protocol"
)

func (h *Hub) PushSmsPolicyToUser(userID string, hideIncoming bool) {
	ctx, cancel := context.WithTimeout(h.root, 4*time.Second)
	defer cancel()
	for _, target := range h.Online() {
		if target.UserID == userID {
			h.enqueueSmsPolicy(ctx, target.UserID, target.DeviceID, hideIncoming)
		}
	}
}

func (h *Hub) pushSmsPolicyTo(userID, deviceID string) {
	ctx, cancel := context.WithTimeout(h.root, 3*time.Second)
	defer cancel()
	profile, err := h.store.GetUserProfile(ctx, userID)
	if err != nil || profile.UserID == "" {
		return
	}
	h.enqueueSmsPolicy(ctx, userID, deviceID, profile.SmsHideIncoming)
}

func (h *Hub) enqueueSmsPolicy(ctx context.Context, userID, deviceID string, hideIncoming bool) {
	if userID == "" || deviceID == "" {
		return
	}
	messageID, body, err := protocol.PanelCommand(protocol.TypePanelSmsPolicy, protocol.SmsPolicyPayload{HideIncoming: hideIncoming})
	if err != nil {
		return
	}
	if err := h.outbox.Enqueue(ctx, userID, deviceID, protocol.TypePanelSmsPolicy, messageID, body); err != nil {
		slog.Warn("sms policy", "err", err, "user", userID)
	}
}
