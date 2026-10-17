package httpapi

import (
	"context"
	"encoding/json"
	"net/http"
	"sort"
	"time"

	"github.com/base/realtime/internal/protocol"
	"github.com/base/realtime/internal/store"
)

func (a *API) ListUssdLogs(w http.ResponseWriter, r *http.Request) {
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
	rows, err := a.store.ListUssdLogs(ctx, userID, deviceID, 80)
	if err != nil {
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	items := make([]map[string]any, 0, len(rows))
	for _, row := range rows {
		item := map[string]any{
			"code":      row.Code,
			"slot":      row.Slot,
			"ok":        row.Ok,
			"createdAt": row.CreatedAt.UnixMilli(),
		}
		if row.Text != "" {
			item["text"] = row.Text
		}
		if row.Error != "" {
			item["error"] = row.Error
		}
		items = append(items, item)
	}
	saved, savedErr := a.store.ListUssdCodes(ctx, userID, 40)
	if savedErr != nil {
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	recent, recentErr := a.store.ListUssdLogsByUser(ctx, userID, 200)
	if recentErr != nil {
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"items": items,
		"codes": mergeUssdCodes(saved, recent),
	})
}

func (a *API) SendUssd(w http.ResponseWriter, r *http.Request) {
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
		Code string `json:"code"`
		Slot int    `json:"slot"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid body", http.StatusBadRequest)
		return
	}
	normalized, ok := protocol.NormalizeUssd(req.Code)
	if !ok {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "invalid"})
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 40*time.Second)
	defer cancel()
	saveCtx, saveCancel := context.WithTimeout(ctx, 2*time.Second)
	_ = a.store.TouchUssdCode(saveCtx, userID, normalized, req.Slot)
	saveCancel()
	result, rttMs, err := a.live.Ussd(ctx, userID, deviceID, normalized, req.Slot)
	logRow := store.UssdLog{
		UserID:   userID,
		DeviceID: deviceID,
		Code:     normalized,
		Slot:     req.Slot,
		Ok:       result.OK && err == nil,
		Text:     result.Text,
		Error:    result.Error,
	}
	if err != nil && logRow.Error == "" {
		logRow.Error = err.Error()
	}
	logCtx, logCancel := context.WithTimeout(context.Background(), 3*time.Second)
	_ = a.store.InsertUssdLog(logCtx, logRow)
	logCancel()
	if err != nil {
		a.writeCommandErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":    result.OK,
		"text":  result.Text,
		"rttMs": rttMs,
		"error": result.Error,
	})
}

type ussdCodeView struct {
	Code       string
	Count      int
	Slot       int
	LastUsedAt time.Time
}

func mergeUssdCodes(saved []store.UssdCode, recent []store.UssdLog) []map[string]any {
	by := make(map[string]*ussdCodeView)
	for _, row := range recent {
		if row.Code == "" {
			continue
		}
		item := by[row.Code]
		if item == nil {
			item = &ussdCodeView{Code: row.Code, Slot: row.Slot, LastUsedAt: row.CreatedAt}
			by[row.Code] = item
		}
		item.Count++
		if row.CreatedAt.After(item.LastUsedAt) {
			item.LastUsedAt = row.CreatedAt
			item.Slot = row.Slot
		}
	}
	for _, row := range saved {
		if row.Code == "" {
			continue
		}
		item := by[row.Code]
		if item == nil {
			by[row.Code] = &ussdCodeView{Code: row.Code, Count: row.Count, Slot: row.Slot, LastUsedAt: row.LastUsedAt}
			continue
		}
		if row.Count > item.Count {
			item.Count = row.Count
		}
		if row.LastUsedAt.After(item.LastUsedAt) {
			item.LastUsedAt = row.LastUsedAt
			item.Slot = row.Slot
		}
	}
	list := make([]*ussdCodeView, 0, len(by))
	for _, item := range by {
		list = append(list, item)
	}
	sort.Slice(list, func(i, j int) bool {
		if list[i].Count == list[j].Count {
			return list[i].LastUsedAt.After(list[j].LastUsedAt)
		}
		return list[i].Count > list[j].Count
	})
	if len(list) > 40 {
		list = list[:40]
	}
	out := make([]map[string]any, 0, len(list))
	for _, item := range list {
		out = append(out, map[string]any{
			"code":       item.Code,
			"count":      item.Count,
			"slot":       item.Slot,
			"lastUsedAt": item.LastUsedAt.UnixMilli(),
		})
	}
	return out
}
