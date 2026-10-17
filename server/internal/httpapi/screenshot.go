package httpapi

import (
	"context"
	"errors"
	"io"
	"net/http"
	"strings"
	"time"

	"go.mongodb.org/mongo-driver/v2/mongo"

	"github.com/base/realtime/internal/auth"
)

const maxScreenshotBytes = 3_500_000

func (a *API) UploadScreenshot(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	userID, deviceID, ok := a.smsIds(r)
	if !ok {
		http.Error(w, "invalid identity", http.StatusBadRequest)
		return
	}
	if !a.allowDeviceUpload(r, userID, deviceID) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, maxScreenshotBytes)
	data, err := io.ReadAll(r.Body)
	if err != nil || len(data) == 0 {
		http.Error(w, "invalid body", http.StatusBadRequest)
		return
	}
	mime := strings.TrimSpace(r.Header.Get("Content-Type"))
	if mime == "" || !strings.HasPrefix(mime, "image/") {
		mime = "image/jpeg"
	}
	if i := strings.Index(mime, ";"); i >= 0 {
		mime = strings.TrimSpace(mime[:i])
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	if err := a.store.SaveScreenshot(ctx, userID, deviceID, mime, data); err != nil {
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "size": len(data)})
}

func (a *API) GetScreenshot(w http.ResponseWriter, r *http.Request) {
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
	row, err := a.store.GetScreenshot(ctx, userID, deviceID)
	if errors.Is(err, mongo.ErrNoDocuments) {
		http.Error(w, "not found", http.StatusNotFound)
		return
	}
	if err != nil {
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	if row.Mime == "" {
		row.Mime = "image/jpeg"
	}
	w.Header().Set("Content-Type", row.Mime)
	w.Header().Set("Cache-Control", "no-store")
	if !row.UpdatedAt.IsZero() {
		w.Header().Set("Last-Modified", row.UpdatedAt.UTC().Format(http.TimeFormat))
	}
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(row.Data)
}

func (a *API) allowDeviceUpload(r *http.Request, userID, deviceID string) bool {
	token := bearer(r)
	if a.cfg.Token == "" {
		return true
	}
	if token == "" {
		return false
	}
	// Admin secret used as device static token (same as WS hello path).
	if auth.SecretEqual(token, a.cfg.Token) {
		return true
	}
	claims, err := auth.Parse(a.cfg.Token, token)
	if err != nil {
		return false
	}
	return claims.UserID == userID && claims.DeviceID == deviceID
}
