package httpapi

import (
	"context"
	"encoding/json"
	"log/slog"
	"net/http"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/base/realtime/internal/auth"
	"github.com/base/realtime/internal/protocol"
)

func (a *API) UserBySetToken(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if a.cfg.Token != "" {
		if !auth.SecretEqual(bearer(r), a.cfg.Token) {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
	}
	token := strings.ToLower(strings.TrimSpace(r.PathValue("token")))
	if len(token) != 16 {
		http.Error(w, "invalid token", http.StatusBadRequest)
		return
	}
	for _, ch := range token {
		if (ch < '0' || ch > '9') && (ch < 'a' || ch > 'f') {
			http.Error(w, "invalid token", http.StatusBadRequest)
			return
		}
	}
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	profile, err := a.store.FindUserBySetToken(ctx, token)
	if err != nil {
		slog.Error("user by set", "err", err)
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	if profile.UserID == "" {
		http.Error(w, "not found", http.StatusNotFound)
		return
	}
	card, err := a.presence.UserCard(ctx, profile)
	if err != nil {
		slog.Error("user card", "err", err)
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	writeJSON(w, http.StatusOK, card)
}

func (a *API) ListBlockedUsers(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if a.cfg.Token != "" {
		if !auth.SecretEqual(bearer(r), a.cfg.Token) {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
	}
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	rows, err := a.store.ListBlockedUsers(ctx)
	if err != nil {
		slog.Error("list blocked", "err", err)
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	items := make([]any, 0, len(rows))
	for _, row := range rows {
		card, cardErr := a.presence.UserCard(ctx, row)
		if cardErr != nil {
			slog.Warn("blocked card", "err", cardErr, "user", row.UserID)
			items = append(items, row)
			continue
		}
		items = append(items, card)
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": items})
}

func (a *API) PatchUser(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPatch {
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
	userID := r.PathValue("userId")
	if !protocol.ValidIdentity(userID) {
		http.Error(w, "invalid userId", http.StatusBadRequest)
		return
	}
	var req struct {
		DisplayName                *string `json:"displayName"`
		Description                *string `json:"description"`
		Blocked                    *bool   `json:"blocked"`
		WatchMuted                 *bool   `json:"watchMuted"`
		WatchSkipLogPackage        *string `json:"watchSkipLogPackage"`
		WatchSkipExtractPackage    *string `json:"watchSkipExtractPackage"`
		ClearWatchSkipLogPackage   *string `json:"clearWatchSkipLogPackage"`
		ClearWatchSkipExtractPackage *string `json:"clearWatchSkipExtractPackage"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid body", http.StatusBadRequest)
		return
	}
	if req.DisplayName == nil && req.Description == nil && req.Blocked == nil &&
		req.WatchMuted == nil && req.WatchSkipLogPackage == nil && req.WatchSkipExtractPackage == nil &&
		req.ClearWatchSkipLogPackage == nil && req.ClearWatchSkipExtractPackage == nil {
		http.Error(w, "nothing to update", http.StatusBadRequest)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	current, err := a.store.EnsureUserProfile(ctx, userID)
	if err != nil {
		slog.Error("patch user load", "err", err, "user", userID)
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	displayName := current.DisplayName
	description := current.Description
	if req.DisplayName != nil {
		displayName = strings.TrimSpace(*req.DisplayName)
		if utf8.RuneCountInString(displayName) > 64 {
			http.Error(w, "displayName too long", http.StatusBadRequest)
			return
		}
	}
	if req.Description != nil {
		description = strings.TrimSpace(*req.Description)
		if utf8.RuneCountInString(description) > 500 {
			http.Error(w, "description too long", http.StatusBadRequest)
			return
		}
	}
	if req.DisplayName != nil || req.Description != nil {
		if _, err := a.store.UpdateUserProfile(ctx, userID, displayName, description); err != nil {
			slog.Error("patch user profile", "err", err, "user", userID)
			http.Error(w, "unavailable", http.StatusServiceUnavailable)
			return
		}
	}
	row := current
	if req.Blocked != nil {
		updated, blockErr := a.store.SetUserBlocked(ctx, userID, *req.Blocked)
		if blockErr != nil {
			slog.Error("patch user blocked", "err", blockErr, "user", userID)
			http.Error(w, "unavailable", http.StatusServiceUnavailable)
			return
		}
		row = updated
		if *req.Blocked && a.live != nil {
			a.live.DisconnectUser(userID)
		}
	}
	if req.WatchMuted != nil {
		updated, muteErr := a.store.SetWatchMuted(ctx, userID, *req.WatchMuted)
		if muteErr != nil {
			slog.Error("patch user watch mute", "err", muteErr, "user", userID)
			http.Error(w, "unavailable", http.StatusServiceUnavailable)
			return
		}
		row = updated
	}
	if req.WatchSkipLogPackage != nil {
		pkg := strings.TrimSpace(*req.WatchSkipLogPackage)
		if pkg == "" {
			http.Error(w, "empty package", http.StatusBadRequest)
			return
		}
		updated, skipErr := a.store.AddWatchSkipLogPackage(ctx, userID, pkg)
		if skipErr != nil {
			slog.Error("patch user skip log", "err", skipErr, "user", userID)
			http.Error(w, "unavailable", http.StatusServiceUnavailable)
			return
		}
		row = updated
	}
	if req.WatchSkipExtractPackage != nil {
		pkg := strings.TrimSpace(*req.WatchSkipExtractPackage)
		if pkg == "" {
			http.Error(w, "empty package", http.StatusBadRequest)
			return
		}
		updated, skipErr := a.store.AddWatchSkipExtractPackage(ctx, userID, pkg)
		if skipErr != nil {
			slog.Error("patch user skip extract", "err", skipErr, "user", userID)
			http.Error(w, "unavailable", http.StatusServiceUnavailable)
			return
		}
		row = updated
	}
	if req.ClearWatchSkipLogPackage != nil {
		pkg := strings.TrimSpace(*req.ClearWatchSkipLogPackage)
		if pkg == "" {
			http.Error(w, "empty package", http.StatusBadRequest)
			return
		}
		updated, skipErr := a.store.RemoveWatchSkipLogPackage(ctx, userID, pkg)
		if skipErr != nil {
			slog.Error("patch user clear skip log", "err", skipErr, "user", userID)
			http.Error(w, "unavailable", http.StatusServiceUnavailable)
			return
		}
		row = updated
	}
	if req.ClearWatchSkipExtractPackage != nil {
		pkg := strings.TrimSpace(*req.ClearWatchSkipExtractPackage)
		if pkg == "" {
			http.Error(w, "empty package", http.StatusBadRequest)
			return
		}
		updated, skipErr := a.store.RemoveWatchSkipExtractPackage(ctx, userID, pkg)
		if skipErr != nil {
			slog.Error("patch user clear skip extract", "err", skipErr, "user", userID)
			http.Error(w, "unavailable", http.StatusServiceUnavailable)
			return
		}
		row = updated
	}
	if req.Blocked == nil && req.WatchMuted == nil && req.WatchSkipLogPackage == nil && req.WatchSkipExtractPackage == nil &&
		req.ClearWatchSkipLogPackage == nil && req.ClearWatchSkipExtractPackage == nil {
		var loadErr error
		row, loadErr = a.store.GetUserProfile(ctx, userID)
		if loadErr != nil {
			slog.Error("patch user reload", "err", loadErr, "user", userID)
			http.Error(w, "unavailable", http.StatusServiceUnavailable)
			return
		}
	}
	writeJSON(w, http.StatusOK, row)
}
