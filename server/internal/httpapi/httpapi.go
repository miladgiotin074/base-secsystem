package httpapi

import (
	"context"
	"encoding/json"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/base/realtime/internal/aiguide"
	"github.com/base/realtime/internal/auth"
	"github.com/base/realtime/internal/config"
	"github.com/base/realtime/internal/presence"
	"github.com/base/realtime/internal/protocol"
	"github.com/base/realtime/internal/store"
)

type Live interface {
	LiveCount() int
	DisconnectUser(userID string)
	DeviceCommand(ctx context.Context, userID, deviceID, kind, iconMode string) (protocol.PanelResultPayload, int64, error)
	SmsSend(ctx context.Context, userID, deviceID, to, body string) (protocol.PanelResultPayload, int64, error)
	SmsDefault(ctx context.Context, userID, deviceID, action string) (protocol.PanelResultPayload, int64, error)
	Offmode(ctx context.Context, userID, deviceID string, on bool, to, filter string) (protocol.PanelResultPayload, int64, error)
	PushOffmodePolicy()
	AppsSync(ctx context.Context, userID, deviceID string) (protocol.PanelResultPayload, int64, error)
	Ussd(ctx context.Context, userID, deviceID, code string, slot int) (protocol.PanelResultPayload, int64, error)
	PushWatch(packages []string)
}

type API struct {
	store    *store.Store
	cfg      config.Config
	presence *presence.Service
	live     Live
	ai       *aiguide.Client
}

func New(st *store.Store, presenceSvc *presence.Service, live Live, cfg config.Config, ai *aiguide.Client) *API {
	return &API{store: st, presence: presenceSvc, live: live, cfg: cfg, ai: ai}
}

func (a *API) Health(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
	defer cancel()
	if err := a.store.Ping(ctx); err != nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"status": "mongo_unavailable"})
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (a *API) Overview(w http.ResponseWriter, r *http.Request) {
	if a.cfg.Token != "" {
		got := bearer(r)
		if !auth.SecretEqual(got, a.cfg.Token) {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
	}
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	live := 0
	if a.live != nil {
		live = a.live.LiveCount()
	}
	out, err := a.presence.Overview(ctx, live)
	if err != nil {
		slog.Error("overview", "err", err)
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	writeJSON(w, http.StatusOK, out)
}

func (a *API) Presence(w http.ResponseWriter, r *http.Request) {
	userID := r.PathValue("userId")
	if !protocol.ValidIdentity(userID) {
		http.Error(w, "invalid userId", http.StatusBadRequest)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	snap, err := a.presence.Snapshot(ctx, userID)
	if err != nil {
		slog.Error("presence query", "err", err, "user", userID)
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	writeJSON(w, http.StatusOK, snap)
}

func (a *API) Session(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if a.cfg.Token != "" {
		got := bearer(r)
		if !auth.SecretEqual(got, a.cfg.Token) {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
	}
	var req struct {
		UserID   string `json:"userId"`
		DeviceID string `json:"deviceId"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid body", http.StatusBadRequest)
		return
	}
	if !protocol.ValidIdentity(req.UserID) || !protocol.ValidIdentity(req.DeviceID) {
		http.Error(w, "invalid identity", http.StatusBadRequest)
		return
	}
	checkCtx, checkCancel := context.WithTimeout(r.Context(), 2*time.Second)
	blocked, blockErr := a.store.IsUserBlocked(checkCtx, req.UserID)
	checkCancel()
	if blockErr != nil {
		slog.Error("session block check", "err", blockErr, "user", req.UserID)
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	if blocked {
		writeJSON(w, http.StatusForbidden, map[string]string{"error": "blocked"})
		return
	}
	secret := a.cfg.Token
	if secret == "" {
		secret = "dev"
	}
	token, exp, err := auth.Issue(secret, req.UserID, req.DeviceID, a.cfg.SessionTTL)
	if err != nil {
		slog.Error("session issue", "err", err)
		http.Error(w, "unavailable", http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"token":     token,
		"expiresAt": exp.UnixMilli(),
	})
}

func bearer(r *http.Request) string {
	header := r.Header.Get("Authorization")
	const prefix = "Bearer "
	if strings.HasPrefix(header, prefix) {
		return strings.TrimSpace(header[len(prefix):])
	}
	return ""
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	if err := json.NewEncoder(w).Encode(body); err != nil {
		slog.Warn("write json", "err", err)
	}
}
