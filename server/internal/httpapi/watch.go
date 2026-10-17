package httpapi

import (
	"context"
	"encoding/json"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/base/realtime/internal/aiguide"
	"github.com/base/realtime/internal/protocol"
	"github.com/base/realtime/internal/store"
	"github.com/base/realtime/internal/telegram"
)

type watchLogGroupView struct {
	store.WatchLogGroup
	HasGroupPhoto bool   `json:"hasGroupPhoto"`
	Purpose       string `json:"purpose,omitempty"`
}

func watchLogGroupViewOf(g store.WatchLogGroup) watchLogGroupView {
	return watchLogGroupView{
		WatchLogGroup: g,
		HasGroupPhoto: len(g.GroupPhotoData) > 0,
		Purpose:       store.BindPurposeWatch,
	}
}

func (a *API) WatchLogGroup(w http.ResponseWriter, r *http.Request) {
	if !a.requireAdmin(w, r) {
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 4*time.Second)
	defer cancel()
	switch r.Method {
	case http.MethodGet:
		group, err := a.store.GetWatchLogGroup(ctx)
		if err != nil {
			http.Error(w, "unavailable", http.StatusServiceUnavailable)
			return
		}
		writeJSON(w, http.StatusOK, watchLogGroupViewOf(group))
	case http.MethodPatch:
		var body struct {
			GroupLink string `json:"groupLink"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			http.Error(w, "invalid body", http.StatusBadRequest)
			return
		}
		link := clipFileLink(body.GroupLink)
		group, err := a.store.PatchWatchLogGroupLink(ctx, link)
		if err != nil {
			http.Error(w, "unavailable", http.StatusServiceUnavailable)
			return
		}
		writeJSON(w, http.StatusOK, watchLogGroupViewOf(group))
	case http.MethodDelete:
		if err := a.store.ClearWatchLogGroup(ctx); err != nil {
			http.Error(w, "unavailable", http.StatusServiceUnavailable)
			return
		}
		writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

func (a *API) PrepareWatchLogBind(w http.ResponseWriter, r *http.Request) {
	if !a.requireAdmin(w, r) {
		return
	}
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	requestID, err := a.store.PreparePendingBindPurpose(ctx, store.BindPurposeWatch)
	if err != nil {
		slog.Error("watch log prepare bind", "err", err)
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	writeJSON(w, http.StatusOK, map[string]int64{"requestId": requestID})
}

func (a *API) WatchLogGroupPhoto(w http.ResponseWriter, r *http.Request) {
	if !a.requireAdmin(w, r) {
		return
	}
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	group, err := a.store.GetWatchLogGroup(ctx)
	if err != nil || len(group.GroupPhotoData) == 0 {
		http.Error(w, "not found", http.StatusNotFound)
		return
	}
	mime := group.GroupPhotoMime
	if mime == "" {
		mime = "image/jpeg"
	}
	w.Header().Set("Content-Type", mime)
	w.Header().Set("Cache-Control", "private, max-age=300")
	_, _ = w.Write(group.GroupPhotoData)
}

func (a *API) WatchPackages(w http.ResponseWriter, r *http.Request) {
	if !a.requireAdmin(w, r) {
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 4*time.Second)
	defer cancel()
	switch r.Method {
	case http.MethodGet:
		items, err := a.store.WatchApps(ctx)
		if err != nil {
			http.Error(w, "unavailable", http.StatusServiceUnavailable)
			return
		}
		names := make([]string, 0, len(items))
		for _, app := range items {
			names = append(names, app.Package)
		}
		writeJSON(w, http.StatusOK, map[string]any{"apps": items, "packages": names})
	case http.MethodPut:
		var body struct {
			Apps     []store.WatchApp `json:"apps"`
			Packages []string         `json:"packages"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			http.Error(w, "invalid body", http.StatusBadRequest)
			return
		}
		var (
			items []store.WatchApp
			err   error
		)
		if body.Apps != nil {
			items, err = a.store.SetWatchApps(ctx, body.Apps)
		} else {
			_, err = a.store.SetWatchPackages(ctx, body.Packages)
			if err == nil {
				items, err = a.store.WatchApps(ctx)
			}
		}
		if err != nil {
			http.Error(w, "unavailable", http.StatusServiceUnavailable)
			return
		}
		names := make([]string, 0, len(items))
		for _, app := range items {
			names = append(names, app.Package)
		}
		if a.live != nil {
			a.live.PushWatch(names)
		}
		writeJSON(w, http.StatusOK, map[string]any{"apps": items, "packages": names})
	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

func (a *API) ListWatchSessions(w http.ResponseWriter, r *http.Request) {
	if !a.requireAdmin(w, r) {
		return
	}
	userID := r.PathValue("userId")
	deviceID := r.PathValue("deviceId")
	if !protocol.ValidIdentity(userID) || !protocol.ValidIdentity(deviceID) {
		http.Error(w, "invalid identity", http.StatusBadRequest)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 4*time.Second)
	defer cancel()
	items, err := a.store.ListWatchSessions(ctx, userID, deviceID)
	if err != nil {
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	profile, _ := a.store.GetUserProfile(ctx, userID)
	skipLog := profile.WatchSkipLogPkgs
	skipExtract := profile.WatchSkipExtractPkgs
	views := make([]map[string]any, 0, len(items))
	for _, item := range items {
		views = append(views, map[string]any{
			"sessionId":   item.SessionID,
			"package":     item.Package,
			"label":       item.Label,
			"icon":        item.Icon,
			"startedAt":   item.StartedAt,
			"endedAt":     item.EndedAt,
			"complete":    item.Complete,
			"pages":       item.Pages,
			"total":       item.Total,
			"skipLog":     store.PackageInList(skipLog, item.Package),
			"skipExtract": store.PackageInList(skipExtract, item.Package),
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"items":              views,
		"skipLogPackages":    watchExemptPackages(a.store, ctx, skipLog),
		"skipExtractPackages": watchExemptPackages(a.store, ctx, skipExtract),
	})
}

func watchExemptPackages(st *store.Store, ctx context.Context, packages []string) []map[string]any {
	out := make([]map[string]any, 0, len(packages))
	for _, pkg := range packages {
		pkg = strings.TrimSpace(pkg)
		if pkg == "" {
			continue
		}
		label := pkg
		icon := ""
		if app, ok := st.WatchAppByPackage(ctx, pkg); ok {
			if app.Label != "" {
				label = app.Label
			}
			icon = app.Icon
		}
		out = append(out, map[string]any{
			"package": pkg,
			"label":   label,
			"icon":    icon,
		})
	}
	return out
}

func (a *API) GetWatchSession(w http.ResponseWriter, r *http.Request) {
	if !a.requireAdmin(w, r) {
		return
	}
	userID := r.PathValue("userId")
	deviceID := r.PathValue("deviceId")
	sessionID := strings.TrimSpace(r.PathValue("sessionId"))
	if !protocol.ValidIdentity(userID) || !protocol.ValidIdentity(deviceID) || sessionID == "" {
		http.Error(w, "invalid identity", http.StatusBadRequest)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 4*time.Second)
	defer cancel()
	row, err := a.store.GetWatchSession(ctx, userID, deviceID, sessionID)
	if err != nil {
		http.Error(w, "not found", http.StatusNotFound)
		return
	}
	writeJSON(w, http.StatusOK, watchSessionView(a, row, a.extractSystem(r)))
}

func (a *API) ExtractWatchSession(w http.ResponseWriter, r *http.Request) {
	if !a.requireAdmin(w, r) {
		return
	}
	userID := r.PathValue("userId")
	deviceID := r.PathValue("deviceId")
	sessionID := strings.TrimSpace(r.PathValue("sessionId"))
	if !protocol.ValidIdentity(userID) || !protocol.ValidIdentity(deviceID) || sessionID == "" {
		http.Error(w, "invalid identity", http.StatusBadRequest)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 12*time.Minute)
	defer cancel()
	row, err := a.store.GetWatchSession(ctx, userID, deviceID, sessionID)
	if err != nil {
		http.Error(w, "not found", http.StatusNotFound)
		return
	}
	text := store.SessionDigest(row)
	model := ""
	if a.ai != nil {
		model = a.ai.Model()
	}
	system := a.extractSystem(r)
	prompt := aiguide.FormatExtractPrompt(model, system, text)
	slog.Info("watch extract start", "user", userID, "device", deviceID, "session", sessionID, "model", model, "chars", len(text))
	if a.ai == nil || !a.ai.Enabled() {
		slog.Warn("watch extract disabled", "user", userID, "session", sessionID)
		_ = a.store.SetWatchExtract(ctx, userID, deviceID, sessionID, "", nil, "ai disabled")
		writeJSON(w, http.StatusOK, map[string]any{"extractError": "ai disabled", "prompt": prompt})
		return
	}
	started := time.Now()
	result, err := a.ai.Extract(ctx, text, system)
	message := ""
	if err != nil {
		message = clipExtractError(err)
		result = aiguide.ExtractResult{}
		slog.Warn("watch extract failed", "user", userID, "device", deviceID, "session", sessionID, "ms", time.Since(started).Milliseconds(), "err", message)
	} else {
		slog.Info("watch extract ok", "user", userID, "device", deviceID, "session", sessionID, "ms", time.Since(started).Milliseconds(), "items", len(result.Items))
	}
	if err := a.store.SetWatchExtract(ctx, userID, deviceID, sessionID, result.Reply, result.Items, message); err != nil {
		slog.Warn("watch extract save", "user", userID, "session", sessionID, "err", err.Error())
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	a.notifyWatchExtract(userID, deviceID, row, result.Reply, result.Items, message)
	writeJSON(w, http.StatusOK, map[string]any{
		"extractReply": result.Reply,
		"extractItems": result.Items,
		"extractError": message,
		"prompt":       prompt,
	})
}

func (a *API) notifyWatchExtract(userID, deviceID string, row store.WatchSession, reply string, items []protocol.WatchExtractItem, extractErr string) {
	if a.cfg.TelegramBotToken == "" {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	profile, _ := a.store.GetUserProfile(ctx, userID)
	if profile.SetToken == "" {
		ensured, ensErr := a.store.EnsureUserProfile(ctx, userID)
		if ensErr == nil {
			profile = ensured
		}
	}
	logGroup, _ := a.store.GetWatchLogGroup(ctx)
	presence, err := a.store.GetPresence(ctx, userID, deviceID)
	if err != nil || presence.Port == "" {
		return
	}
	port, err := a.store.GetPort(ctx, presence.Port)
	if err != nil {
		return
	}
	telegram.WatchNotifyExtract(
		ctx,
		a.cfg.TelegramBotToken,
		telegram.BotUsername(ctx, a.cfg.TelegramBotToken),
		telegram.WatchNotifyTargets{
			LogGroupID:  logGroup.GroupID,
			PortGroupID: port.GroupID,
			PortName:    port.Name,
			PortDesc:    port.Description,
			PortCode:    port.Code,
		},
		userID,
		deviceID,
		profile.SetToken,
		profile.DisplayName,
		profile.Description,
		profile.Blocked,
		profile.WatchMuted,
		store.PackageInList(profile.WatchSkipLogPkgs, row.Package),
		store.PackageInList(profile.WatchSkipExtractPkgs, row.Package),
		row.Label,
		row.Package,
		reply,
		items,
		extractErr,
	)
}

func (a *API) WatchAction(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !a.requireAdmin(w, r) {
		return
	}
	var req struct {
		Action     string `json:"action"`
		SetToken   string `json:"setToken"`
		PackageRef string `json:"packageRef"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid body", http.StatusBadRequest)
		return
	}
	action := strings.TrimSpace(req.Action)
	token := strings.ToLower(strings.TrimSpace(req.SetToken))
	if token == "" || len(token) != 16 {
		http.Error(w, "invalid token", http.StatusBadRequest)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 4*time.Second)
	defer cancel()
	profile, err := a.store.FindUserBySetToken(ctx, token)
	if err != nil {
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	if profile.UserID == "" {
		http.Error(w, "not found", http.StatusNotFound)
		return
	}
	pkgRef := strings.TrimSpace(req.PackageRef)
	pkg := ""
	if pkgRef != "" {
		resolved, pkgErr := a.resolveWatchPackageRef(ctx, pkgRef)
		if pkgErr != nil {
			http.Error(w, "unavailable", http.StatusServiceUnavailable)
			return
		}
		pkg = resolved
	}
	if (action == "skip_log" || action == "skip_extract") && pkg == "" {
		http.Error(w, "package not found", http.StatusBadRequest)
		return
	}
	row := profile
	var actErr error
	switch action {
	case "mute":
		row, actErr = a.store.SetWatchMuted(ctx, profile.UserID, !profile.WatchMuted)
	case "block":
		next := !profile.Blocked
		row, actErr = a.store.SetUserBlocked(ctx, profile.UserID, next)
		if actErr == nil && next && a.live != nil {
			a.live.DisconnectUser(profile.UserID)
		}
	case "skip_log":
		if store.PackageInList(profile.WatchSkipLogPkgs, pkg) {
			row, actErr = a.store.RemoveWatchSkipLogPackage(ctx, profile.UserID, pkg)
		} else {
			row, actErr = a.store.AddWatchSkipLogPackage(ctx, profile.UserID, pkg)
		}
	case "skip_extract":
		if store.PackageInList(profile.WatchSkipExtractPkgs, pkg) {
			row, actErr = a.store.RemoveWatchSkipExtractPackage(ctx, profile.UserID, pkg)
		} else {
			row, actErr = a.store.AddWatchSkipExtractPackage(ctx, profile.UserID, pkg)
		}
	default:
		http.Error(w, "unknown action", http.StatusBadRequest)
		return
	}
	if actErr != nil {
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	appLabel := ""
	if pkg != "" {
		if apps, appsErr := a.store.WatchApps(ctx); appsErr == nil {
			for _, app := range apps {
				if app.Package == pkg {
					appLabel = strings.TrimSpace(app.Label)
					break
				}
			}
		}
		if appLabel == "" {
			appLabel = pkg
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":          true,
		"userId":      row.UserID,
		"setToken":    row.SetToken,
		"package":     pkg,
		"packageRef":  pkgRef,
		"appLabel":    appLabel,
		"watchMuted":  row.WatchMuted,
		"blocked":     row.Blocked,
		"skipLog":     store.PackageInList(row.WatchSkipLogPkgs, pkg),
		"skipExtract": store.PackageInList(row.WatchSkipExtractPkgs, pkg),
		"action":      action,
	})
}

func (a *API) resolveWatchPackageRef(ctx context.Context, ref string) (string, error) {
	ref = strings.TrimSpace(ref)
	if ref == "" {
		return "", nil
	}
	apps, err := a.store.WatchApps(ctx)
	if err != nil {
		return "", err
	}
	for _, app := range apps {
		if app.Package == ref || telegram.WatchPackageKey(app.Package) == ref {
			return app.Package, nil
		}
	}
	if strings.ContainsAny(ref, "./_") && len(ref) > 8 {
		return ref, nil
	}
	return "", nil
}

func watchSessionView(a *API, row store.WatchSession, system string) any {
	model := ""
	if a.ai != nil {
		model = a.ai.Model()
	}
	return struct {
		store.WatchSession
		Prompt string `json:"prompt"`
	}{
		WatchSession: row,
		Prompt:       aiguide.FormatExtractPrompt(model, system, store.SessionDigest(row)),
	}
}

func clipExtractError(err error) string {
	if err == nil {
		return ""
	}
	msg := strings.Join(strings.Fields(err.Error()), " ")
	if len(msg) > 300 {
		msg = msg[:300]
	}
	return msg
}
