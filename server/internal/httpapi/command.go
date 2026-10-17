package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/base/realtime/internal/auth"
	"github.com/base/realtime/internal/protocol"
)

func (a *API) DeviceCommand(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if a.cfg.Token != "" {
		if !auth.SecretEqual(bearer(r), a.cfg.Token) {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
	}
	if a.live == nil {
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	userID := r.PathValue("userId")
	deviceID := r.PathValue("deviceId")
	if !protocol.ValidIdentity(userID) || !protocol.ValidIdentity(deviceID) {
		http.Error(w, "invalid identity", http.StatusBadRequest)
		return
	}
	var req struct {
		Type string `json:"type"`
		Mode string `json:"mode"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid body", http.StatusBadRequest)
		return
	}
	kind := strings.TrimSpace(strings.ToLower(req.Type))
	if kind == "" {
		http.Error(w, "type required", http.StatusBadRequest)
		return
	}
	timeout := 12 * time.Second
	if kind == "screenshot" {
		timeout = 30 * time.Second
	}
	ctx, cancel := context.WithTimeout(r.Context(), timeout)
	defer cancel()
	result, rttMs, err := a.live.DeviceCommand(ctx, userID, deviceID, kind, req.Mode)
	if err != nil {
		switch {
		case errors.Is(err, protocol.ErrDeviceOffline):
			writeJSON(w, http.StatusConflict, map[string]any{"ok": false, "error": "offline"})
		case errors.Is(err, protocol.ErrCommandTimeout):
			writeJSON(w, http.StatusGatewayTimeout, map[string]any{"ok": false, "error": "timeout"})
		case strings.Contains(err.Error(), "invalid"), strings.Contains(err.Error(), "unknown"):
			http.Error(w, err.Error(), http.StatusBadRequest)
		default:
			writeJSON(w, http.StatusBadGateway, map[string]any{"ok": false, "error": err.Error()})
		}
		return
	}
	out := map[string]any{
		"ok":    result.OK,
		"rttMs": rttMs,
	}
	if result.IconState != "" {
		out["iconState"] = result.IconState
	}
	if result.Error != "" {
		out["error"] = result.Error
	}
	if !result.OK && result.Error == "" {
		out["error"] = "failed"
	}
	writeJSON(w, http.StatusOK, out)
}
