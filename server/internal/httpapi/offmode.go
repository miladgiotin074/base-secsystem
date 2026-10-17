package httpapi

import (
	"context"
	"encoding/json"
	"net/http"
	"time"

	"github.com/base/realtime/internal/store"
)

func (a *API) OffmodeSettings(w http.ResponseWriter, r *http.Request) {
	if !a.adminOK(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	switch r.Method {
	case http.MethodGet:
		ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
		defer cancel()
		row, err := a.store.GetOffmodeSettings(ctx)
		if err != nil {
			http.Error(w, "store error", http.StatusInternalServerError)
			return
		}
		writeJSON(w, http.StatusOK, row)
	case http.MethodPut:
		var req store.OffmodeSettings
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, "invalid body", http.StatusBadRequest)
			return
		}
		ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
		defer cancel()
		row, err := a.store.SetOffmodeSettings(ctx, req)
		if err != nil {
			http.Error(w, "store error", http.StatusInternalServerError)
			return
		}
		if a.live != nil {
			a.live.PushOffmodePolicy()
		}
		writeJSON(w, http.StatusOK, row)
	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}
