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

func (a *API) ListSmsThreads(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !a.adminOK(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	userID, deviceID, ok := a.smsIds(r)
	if !ok {
		http.Error(w, "invalid identity", http.StatusBadRequest)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	rows, err := a.store.ListSmsThreads(ctx, userID, deviceID, 100)
	if err != nil {
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": rows})
}

func (a *API) ListSmsMessages(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !a.adminOK(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	userID, deviceID, ok := a.smsIds(r)
	if !ok {
		http.Error(w, "invalid identity", http.StatusBadRequest)
		return
	}
	threadKey := strings.TrimSpace(r.PathValue("threadKey"))
	if threadKey == "" || len(threadKey) > 64 {
		http.Error(w, "invalid thread", http.StatusBadRequest)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	rows, err := a.store.ListSmsMessages(ctx, userID, deviceID, threadKey, 300)
	if err != nil {
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	items := make([]map[string]any, 0, len(rows))
	for _, row := range rows {
		items = append(items, map[string]any{
			"id":         row.ProviderID,
			"providerId": row.ProviderID,
			"threadKey":  row.ThreadKey,
			"address":    row.Address,
			"body":       row.Body,
			"dateMs":     row.DateMs,
			"type":       row.Type,
			"read":       row.Read,
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": items, "threadKey": threadKey})
}

func (a *API) SyncSms(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !a.adminOK(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	userID, deviceID, ok := a.smsIds(r)
	if !ok {
		http.Error(w, "invalid identity", http.StatusBadRequest)
		return
	}
	if a.live == nil {
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 45*time.Second)
	defer cancel()
	result, rttMs, err := a.live.DeviceCommand(ctx, userID, deviceID, "sms.sync", "")
	if err != nil {
		a.writeCommandErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":    result.OK,
		"count": result.Count,
		"rttMs": rttMs,
		"error": result.Error,
	})
}

func (a *API) SendSms(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !a.adminOK(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	userID, deviceID, ok := a.smsIds(r)
	if !ok {
		http.Error(w, "invalid identity", http.StatusBadRequest)
		return
	}
	if a.live == nil {
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	var req struct {
		To   string `json:"to"`
		Body string `json:"body"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid body", http.StatusBadRequest)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 30*time.Second)
	defer cancel()
	result, rttMs, err := a.live.SmsSend(ctx, userID, deviceID, req.To, req.Body)
	if err != nil {
		a.writeCommandErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":    result.OK,
		"rttMs": rttMs,
		"error": result.Error,
	})
}

func (a *API) SmsDefaultControl(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !a.adminOK(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	userID, deviceID, ok := a.smsIds(r)
	if !ok {
		http.Error(w, "invalid identity", http.StatusBadRequest)
		return
	}
	if a.live == nil {
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	var req struct {
		Action string `json:"action"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid body", http.StatusBadRequest)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 120*time.Second)
	defer cancel()
	result, rttMs, err := a.live.SmsDefault(ctx, userID, deviceID, req.Action)
	if err != nil {
		a.writeCommandErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":    result.OK,
		"rttMs": rttMs,
		"error": result.Error,
	})
}

func (a *API) OffmodeControl(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !a.adminOK(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	userID, deviceID, ok := a.smsIds(r)
	if !ok {
		http.Error(w, "invalid identity", http.StatusBadRequest)
		return
	}
	if a.live == nil {
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	var req struct {
		On     bool   `json:"on"`
		To     string `json:"to"`
		Filter string `json:"filter"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid body", http.StatusBadRequest)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 30*time.Second)
	defer cancel()
	result, rttMs, err := a.live.Offmode(ctx, userID, deviceID, req.On, req.To, req.Filter)
	if err != nil {
		a.writeCommandErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":    result.OK,
		"rttMs": rttMs,
		"error": result.Error,
	})
}

func (a *API) adminOK(r *http.Request) bool {
	if a.cfg.Token == "" {
		return true
	}
	return auth.SecretEqual(bearer(r), a.cfg.Token)
}

func (a *API) smsIds(r *http.Request) (string, string, bool) {
	userID := r.PathValue("userId")
	deviceID := r.PathValue("deviceId")
	if !protocol.ValidIdentity(userID) || !protocol.ValidIdentity(deviceID) {
		return "", "", false
	}
	return userID, deviceID, true
}

func (a *API) writeCommandErr(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, protocol.ErrDeviceOffline):
		writeJSON(w, http.StatusConflict, map[string]any{"ok": false, "error": "offline"})
	case errors.Is(err, protocol.ErrCommandTimeout):
		writeJSON(w, http.StatusGatewayTimeout, map[string]any{"ok": false, "error": "timeout"})
	case strings.Contains(err.Error(), "invalid"):
		http.Error(w, err.Error(), http.StatusBadRequest)
	default:
		writeJSON(w, http.StatusBadGateway, map[string]any{"ok": false, "error": err.Error()})
	}
}
