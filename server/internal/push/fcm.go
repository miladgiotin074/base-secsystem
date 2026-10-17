package push

import (
	"bytes"
	"context"
	"encoding/json"
	"log/slog"
	"net/http"
	"time"
)

type Sender struct {
	serverKey string
	webhook   string
	http      *http.Client
}

func New(serverKey, webhook string) *Sender {
	return &Sender{
		serverKey: serverKey,
		webhook:   webhook,
		http:      &http.Client{Timeout: 8 * time.Second},
	}
}

func (s *Sender) Wake(ctx context.Context, token, userID, deviceID string) {
	if token == "" {
		return
	}
	if s.webhook != "" {
		s.post(ctx, s.webhook, map[string]string{
			"token":    token,
			"userId":   userID,
			"deviceId": deviceID,
			"type":     "wake",
		}, "")
		return
	}
	if s.serverKey == "" {
		return
	}
	body := map[string]any{
		"to":       token,
		"priority": "high",
		"data":     map[string]string{"realtime": "wake"},
	}
	s.post(ctx, "https://fcm.googleapis.com/fcm/send", body, "key="+s.serverKey)
}

func (s *Sender) post(ctx context.Context, endpoint string, body any, auth string) {
	raw, err := json.Marshal(body)
	if err != nil {
		return
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(raw))
	if err != nil {
		return
	}
	req.Header.Set("Content-Type", "application/json")
	if auth != "" {
		req.Header.Set("Authorization", auth)
	}
	res, err := s.http.Do(req)
	if err != nil {
		slog.Warn("push wake", "err", err)
		return
	}
	_ = res.Body.Close()
	if res.StatusCode >= 300 {
		slog.Warn("push wake status", "status", res.StatusCode)
	}
}
