package reliability

import (
	"context"
	"errors"
	"log/slog"
	"time"

	"github.com/base/realtime/internal/store"
)

type Target struct {
	UserID   string
	DeviceID string
}

type Deliverer interface {
	Send(userID, deviceID string, body []byte) bool
	Online() []Target
}

type Outbox struct {
	store *store.Store
	hub   Deliverer
	wake  chan struct{}
}

func NewOutbox(st *store.Store, hub Deliverer) *Outbox {
	return &Outbox{store: st, hub: hub, wake: make(chan struct{}, 1)}
}

func (o *Outbox) Kick() {
	select {
	case o.wake <- struct{}{}:
	default:
	}
}

func (o *Outbox) Run(ctx context.Context) {
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			o.flush(ctx)
		case <-o.wake:
			o.flush(ctx)
		}
	}
}

func (o *Outbox) Enqueue(ctx context.Context, userID, deviceID, dedupeKey, messageID string, body []byte) error {
	err := o.store.Enqueue(ctx, store.OutboxItem{
		ID:            messageID,
		UserID:        userID,
		DeviceID:      deviceID,
		DedupeKey:     dedupeKey,
		Body:          body,
		Attempts:      0,
		NextAttemptAt: time.Now(),
		CreatedAt:     time.Now(),
	})
	if errors.Is(err, store.ErrOutboxFull) {
		slog.Warn("outbox full", "user", userID, "device", deviceID)
		return err
	}
	if err != nil {
		return err
	}
	o.Kick()
	return nil
}

func (o *Outbox) OnAck(ctx context.Context, messageID string) {
	if messageID == "" {
		return
	}
	if err := o.store.DeleteOutbox(ctx, messageID); err != nil {
		slog.Warn("outbox ack", "err", err, "id", messageID)
	}
}

func (o *Outbox) flush(ctx context.Context) {
	if ctx.Err() != nil {
		return
	}
	for _, target := range o.hub.Online() {
		o.flushDevice(ctx, target.UserID, target.DeviceID)
	}
}

func (o *Outbox) flushDevice(ctx context.Context, userID, deviceID string) {
	op, cancel := context.WithTimeout(ctx, 3*time.Second)
	items, err := o.store.DueOutbox(op, userID, deviceID, time.Now(), 20)
	cancel()
	if err != nil {
		slog.Warn("outbox list", "err", err, "user", userID, "device", deviceID)
		return
	}
	for _, item := range items {
		if ctx.Err() != nil {
			return
		}
		if !o.hub.Send(userID, deviceID, item.Body) {
			op, cancel = context.WithTimeout(ctx, 3*time.Second)
			_ = o.store.ScheduleOutbox(op, item.ID, item.Attempts, time.Now().Add(time.Second))
			cancel()
			return
		}
		next := time.Now().Add(AckDelay(item.Attempts + 1))
		op, cancel = context.WithTimeout(ctx, 3*time.Second)
		err = o.store.ScheduleOutbox(op, item.ID, item.Attempts+1, next)
		cancel()
		if err != nil {
			slog.Warn("outbox schedule", "err", err, "id", item.ID)
		}
	}
}
