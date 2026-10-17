package httpapi

import (
	"context"
	"net/http"
	"slices"
	"strings"
	"time"

	"github.com/base/realtime/internal/store"
)

func (a *API) ListInstalledApps(w http.ResponseWriter, r *http.Request) {
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
	ctx, cancel := context.WithTimeout(r.Context(), 8*time.Second)
	defer cancel()
	rows, err := a.store.ListInstalledApps(ctx, userID, deviceID)
	if err != nil {
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	slices.SortFunc(rows, func(a, b store.InstalledApp) int {
		return strings.Compare(strings.ToLower(a.Label), strings.ToLower(b.Label))
	})
	items := make([]map[string]any, 0, len(rows))
	for _, row := range rows {
		item := map[string]any{
			"packageName": row.PackageName,
			"label":       row.Label,
			"system":      row.System,
		}
		if row.Icon != "" {
			item["icon"] = row.Icon
		}
		items = append(items, item)
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": items})
}

func (a *API) SyncInstalledApps(w http.ResponseWriter, r *http.Request) {
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
	ctx, cancel := context.WithTimeout(r.Context(), 50*time.Second)
	defer cancel()
	result, rttMs, err := a.live.AppsSync(ctx, userID, deviceID)
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
