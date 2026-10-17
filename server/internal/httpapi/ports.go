package httpapi

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"go.mongodb.org/mongo-driver/v2/mongo"

	"github.com/base/realtime/internal/auth"
	"github.com/base/realtime/internal/protocol"
	"github.com/base/realtime/internal/store"
)

type portListItem struct {
	store.Port
	HasGroupPhoto  bool   `json:"hasGroupPhoto"`
	HasClient      bool   `json:"hasClient"`
	HasOnboarding  bool   `json:"hasOnboarding"`
	ClientName     string `json:"clientName,omitempty"`
	ClientColor    string `json:"clientColor,omitempty"`
	ClientFont     string `json:"clientFont,omitempty"`
}

func portListItems(ports []store.Port) []portListItem {
	out := make([]portListItem, len(ports))
	for i, p := range ports {
		out[i] = portView(p)
	}
	return out
}

func portView(p store.Port) portListItem {
	linked := p.LinkedClientID()
	p.FirstAction = store.PresentFirstAction(p.FirstAction)
	p.Offmode = store.PresentPortOffmode(p.Offmode)
	item := portListItem{
		Port:          p,
		HasGroupPhoto: len(p.GroupPhotoData) > 0,
		HasClient:     linked != "" || len(p.OnboardingHTML) > 0,
		HasOnboarding: len(p.OnboardingHTML) > 0,
	}
	if linked != "" {
		item.ClientID = linked
	}
	return item
}

func (a *API) enrichPortViews(ctx context.Context, items []portListItem) []portListItem {
	for i := range items {
		id := items[i].LinkedClientID()
		if id == "" {
			continue
		}
		row, err := a.store.GetClient(ctx, id)
		if err != nil {
			continue
		}
		items[i].ClientID = id
		items[i].ClientName = row.Name
		items[i].ClientColor = row.PrimaryColor
		if row.HasFont() {
			items[i].ClientFont = row.FontFileName
		}
		items[i].HasClient = true
		items[i].HasOnboarding = row.HasOnboarding() || items[i].HasOnboarding
	}
	return items
}

func (a *API) requireAdmin(w http.ResponseWriter, r *http.Request) bool {
	if a.cfg.Token != "" {
		if !auth.SecretEqual(bearer(r), a.cfg.Token) {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return false
		}
	}
	return true
}

func (a *API) Ports(w http.ResponseWriter, r *http.Request) {
	if !a.requireAdmin(w, r) {
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	switch r.Method {
	case http.MethodGet:
		items, err := a.store.ListPorts(ctx)
		if err != nil {
			slog.Error("ports list", "err", err)
			http.Error(w, "unavailable", http.StatusServiceUnavailable)
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"items": a.enrichPortViews(ctx, portListItems(items))})
	case http.MethodPost:
		var req struct {
			Name        string `json:"name"`
			Description string `json:"description"`
			GroupID     int64  `json:"groupId"`
			GroupTitle  string `json:"groupTitle"`
			GroupLink   string `json:"groupLink"`
			RequestID   int64  `json:"requestId"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, "invalid body", http.StatusBadRequest)
			return
		}
		name, ok := clipText(req.Name, 80)
		if !ok || name == "" {
			http.Error(w, "invalid name", http.StatusBadRequest)
			return
		}
		description, ok := clipText(req.Description, 400)
		if !ok {
			http.Error(w, "invalid description", http.StatusBadRequest)
			return
		}
		groupLink, ok := clipLink(req.GroupLink)
		if !ok {
			http.Error(w, "invalid link", http.StatusBadRequest)
			return
		}
		groupID, groupTitle := req.GroupID, strings.TrimSpace(req.GroupTitle)
		groupPhotoMime := ""
		var groupPhotoData []byte
		if req.RequestID != 0 {
			pending, err := a.store.GetPendingBind(ctx, req.RequestID)
			if err == nil && pending.GroupID != 0 {
				if !pending.BotAdminOk {
					http.Error(w, "bot admin required", http.StatusBadRequest)
					return
				}
				groupID = pending.GroupID
				groupTitle = pending.GroupTitle
				groupPhotoMime = pending.GroupPhotoMime
				groupPhotoData = pending.GroupPhotoData
				if groupLink == "" {
					groupLink = pending.GroupLink
				}
			}
			a.store.DeletePendingBind(ctx, req.RequestID)
		}
		if groupID != 0 && req.RequestID == 0 {
			http.Error(w, "invalid group bind", http.StatusBadRequest)
			return
		}
		port, err := a.store.CreatePort(ctx, name, description, groupID, groupTitle, groupLink, groupPhotoMime, groupPhotoData)
		if err != nil {
			slog.Error("port create", "err", err)
			http.Error(w, "unavailable", http.StatusServiceUnavailable)
			return
		}
		writeJSON(w, http.StatusOK, portView(port))
	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

func (a *API) BindPort(w http.ResponseWriter, r *http.Request) {
	if !a.requireAdmin(w, r) {
		return
	}
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	code := r.PathValue("code")
	if !protocol.ValidPort(code) {
		http.Error(w, "invalid port", http.StatusBadRequest)
		return
	}
	var req struct {
		GroupID    int64  `json:"groupId"`
		GroupTitle string `json:"groupTitle"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.GroupID == 0 {
		http.Error(w, "invalid body", http.StatusBadRequest)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	if _, err := a.store.GetPort(ctx, code); err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	port, err := a.store.BindPort(ctx, code, req.GroupID, strings.TrimSpace(req.GroupTitle), "", "", nil)
	if err != nil {
		slog.Error("port bind", "err", err)
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	writeJSON(w, http.StatusOK, portView(port))
}

func (a *API) UnbindPort(w http.ResponseWriter, r *http.Request) {
	if !a.requireAdmin(w, r) {
		return
	}
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	code := r.PathValue("code")
	if !protocol.ValidPort(code) {
		http.Error(w, "invalid port", http.StatusBadRequest)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	port, err := a.store.UnbindPort(ctx, code)
	if err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		slog.Error("port unbind", "err", err)
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	writeJSON(w, http.StatusOK, portView(port))
}

func (a *API) UnbindPortGroup(w http.ResponseWriter, r *http.Request) {
	if !a.requireAdmin(w, r) {
		return
	}
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	var req struct {
		GroupID int64 `json:"groupId"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.GroupID == 0 {
		http.Error(w, "invalid body", http.StatusBadRequest)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	if err := a.store.UnbindPortByGroup(ctx, req.GroupID); err != nil {
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

func (a *API) PreparePortBind(w http.ResponseWriter, r *http.Request) {
	if !a.requireAdmin(w, r) {
		return
	}
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	code := r.PathValue("code")
	if !protocol.ValidPort(code) {
		http.Error(w, "invalid port", http.StatusBadRequest)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	requestID, err := a.store.PreparePortBind(ctx, code)
	if err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		slog.Error("port prepare bind", "err", err)
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	writeJSON(w, http.StatusOK, map[string]int64{"requestId": requestID})
}

func (a *API) BindPortShared(w http.ResponseWriter, r *http.Request) {
	if !a.requireAdmin(w, r) {
		return
	}
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	var req struct {
		RequestID      int64  `json:"requestId"`
		GroupID        int64  `json:"groupId"`
		GroupTitle     string `json:"groupTitle"`
		GroupLink      string `json:"groupLink"`
		GroupPhotoData string `json:"groupPhotoData"`
		GroupPhotoMime string `json:"groupPhotoMime"`
		BotAdminOk     bool   `json:"botAdminOk"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.RequestID == 0 || req.GroupID == 0 {
		http.Error(w, "invalid body", http.StatusBadRequest)
		return
	}
	photoData, photoMime := []byte(nil), ""
	if req.BotAdminOk && strings.TrimSpace(req.GroupPhotoData) != "" {
		var ok bool
		photoData, photoMime, ok = clipPhotoPayload(req.GroupPhotoData, req.GroupPhotoMime)
		if !ok {
			slog.Warn("port group photo skipped")
			photoData, photoMime = nil, ""
		}
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	if saveErr := a.store.SavePendingGroup(ctx, req.RequestID, req.GroupID, strings.TrimSpace(req.GroupTitle), clipFileLink(req.GroupLink), photoMime, photoData, req.BotAdminOk); saveErr != nil {
		slog.Error("pending group", "err", saveErr)
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	pending, pendErr := a.store.GetPendingBind(ctx, req.RequestID)
	if pendErr != nil && !errors.Is(pendErr, mongo.ErrNoDocuments) {
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	if !req.BotAdminOk {
		writeJSON(w, http.StatusOK, map[string]any{
			"pending":    true,
			"botAdminOk": false,
			"requestId":  req.RequestID,
			"groupId":    req.GroupID,
			"groupTitle": req.GroupTitle,
			"purpose":    pending.Purpose,
		})
		return
	}
	// Port bind wins when this request was prepared for a port (pendingRequestId on port).
	found, portErr := a.store.FindPortByRequestID(ctx, req.RequestID)
	if portErr == nil {
		port, err := a.store.BindPort(ctx, found.Code, req.GroupID, strings.TrimSpace(req.GroupTitle), clipFileLink(req.GroupLink), photoMime, photoData)
		if err != nil {
			slog.Error("port bind shared", "err", err)
			http.Error(w, "unavailable", http.StatusServiceUnavailable)
			return
		}
		// Keep pending so the Mini App poll can read groupId after bind completes.
		writeJSON(w, http.StatusOK, portView(port))
		return
	}
	if portErr != nil && !errors.Is(portErr, mongo.ErrNoDocuments) {
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	if pending.Purpose == store.BindPurposeWatch {
		group, bindErr := a.store.SetWatchLogGroup(ctx, req.GroupID, strings.TrimSpace(req.GroupTitle), clipFileLink(req.GroupLink), photoMime, photoData)
		if bindErr != nil {
			slog.Error("watch log group bind", "err", bindErr)
			http.Error(w, "unavailable", http.StatusServiceUnavailable)
			return
		}
		writeJSON(w, http.StatusOK, watchLogGroupViewOf(group))
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"pending":    true,
		"botAdminOk": true,
		"requestId":  req.RequestID,
		"groupId":    req.GroupID,
		"groupTitle": req.GroupTitle,
	})
}

func (a *API) PortPhoto(w http.ResponseWriter, r *http.Request) {
	if !a.requireAdmin(w, r) {
		return
	}
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	code := r.PathValue("code")
	if !protocol.ValidPort(code) {
		http.Error(w, "invalid port", http.StatusBadRequest)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	port, err := a.store.GetPort(ctx, code)
	if err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	if len(port.GroupPhotoData) == 0 {
		http.Error(w, "not found", http.StatusNotFound)
		return
	}
	mime := port.GroupPhotoMime
	if mime == "" {
		mime = "image/jpeg"
	}
	w.Header().Set("Content-Type", mime)
	w.Header().Set("Cache-Control", "private, max-age=86400")
	_, _ = w.Write(port.GroupPhotoData)
}

const onboardingMaxFile = 2 * 1024 * 1024

func (a *API) PortOnboarding(w http.ResponseWriter, r *http.Request) {
	code := r.PathValue("code")
	if !protocol.ValidPort(code) {
		http.Error(w, "invalid port", http.StatusBadRequest)
		return
	}
	switch r.Method {
	case http.MethodGet:
		a.servePortOnboarding(w, r, code)
	case http.MethodPut:
		a.putPortOnboarding(w, r, code)
	case http.MethodDelete:
		a.deletePortOnboarding(w, r, code)
	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

func (a *API) PortClient(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	code := r.PathValue("code")
	if !protocol.ValidPort(code) {
		http.Error(w, "invalid port", http.StatusBadRequest)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	port, err := a.store.GetPort(ctx, code)
	if err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	id := port.LinkedClientID()
	if id == "" {
		http.Error(w, "not found", http.StatusNotFound)
		return
	}
	row, err := a.store.GetClient(ctx, id)
	if err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"id":           row.ID,
		"name":         row.Name,
		"primaryColor": row.PrimaryColor,
		"hasFont":      row.HasFont(),
		"fontFileName": row.FontFileName,
	})
}

func (a *API) PortClientFont(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	code := r.PathValue("code")
	if !protocol.ValidPort(code) {
		http.Error(w, "invalid port", http.StatusBadRequest)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	port, err := a.store.GetPort(ctx, code)
	if err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	name, body, err := a.store.ResolvePortClientFont(ctx, port)
	if err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	writeClientBytes(w, name, body, "private, max-age=300")
}

func (a *API) servePortOnboarding(w http.ResponseWriter, r *http.Request, code string) {
	file := strings.TrimSpace(r.PathValue("file"))
	if file == "" || file == "/" {
		file = "index.html"
	}
	file = strings.TrimPrefix(file, "/")
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	port, err := a.store.GetPort(ctx, code)
	if err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	switch file {
	case "index.html", "index.htm", "styles.css", "style.css", "app.js", "main.js":
		html, css, js, updatedAt, err := a.store.ResolvePortOnboarding(ctx, port)
		if err != nil {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		var body []byte
		switch file {
		case "index.html", "index.htm":
			body = html
		case "styles.css", "style.css":
			body = css
		default:
			body = js
		}
		if (file == "index.html" || file == "index.htm") && len(body) == 0 {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		if !updatedAt.IsZero() {
			w.Header().Set("Last-Modified", updatedAt.UTC().Format(http.TimeFormat))
		}
		writeClientBytes(w, file, body, "private, max-age=300")
		return
	default:
		body, err := a.store.ResolvePortOnboardingAsset(ctx, port, file)
		if err != nil {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		writeClientBytes(w, file, body, "private, max-age=300")
	}
}

func (a *API) putPortOnboarding(w http.ResponseWriter, r *http.Request, code string) {
	if !a.requireAdmin(w, r) {
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, onboardingMaxFile*3+4096)
	var req struct {
		HTML string `json:"html"`
		CSS  string `json:"css"`
		JS   string `json:"js"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid body", http.StatusBadRequest)
		return
	}
	html := []byte(strings.TrimSpace(req.HTML))
	css := []byte(req.CSS)
	js := []byte(req.JS)
	if len(html) == 0 {
		http.Error(w, "html required", http.StatusBadRequest)
		return
	}
	if len(html) > onboardingMaxFile || len(css) > onboardingMaxFile || len(js) > onboardingMaxFile {
		http.Error(w, "file too large", http.StatusRequestEntityTooLarge)
		return
	}
	if !utf8.Valid(html) || !utf8.Valid(css) || !utf8.Valid(js) {
		http.Error(w, "invalid utf-8", http.StatusBadRequest)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	port, err := a.store.SetPortOnboarding(ctx, code, html, css, js)
	if err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	writeJSON(w, http.StatusOK, portView(port))
}

func (a *API) deletePortOnboarding(w http.ResponseWriter, r *http.Request, code string) {
	if !a.requireAdmin(w, r) {
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	port, err := a.store.ClearPortOnboarding(ctx, code)
	if err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	writeJSON(w, http.StatusOK, portView(port))
}

func (a *API) UpdatePort(w http.ResponseWriter, r *http.Request) {
	if !a.requireAdmin(w, r) {
		return
	}
	if r.Method != http.MethodPatch {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	code := r.PathValue("code")
	if !protocol.ValidPort(code) {
		http.Error(w, "invalid port", http.StatusBadRequest)
		return
	}
	var req struct {
		Name        string             `json:"name"`
		Description string             `json:"description"`
		GroupLink   string             `json:"groupLink"`
		ClientID    *string            `json:"clientId"`
		FirstAction *store.FirstAction `json:"firstAction"`
		Offmode     *store.PortOffmode `json:"offmode"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid body", http.StatusBadRequest)
		return
	}
	name, ok := clipText(req.Name, 80)
	if !ok || name == "" {
		http.Error(w, "invalid name", http.StatusBadRequest)
		return
	}
	description, ok := clipText(req.Description, 400)
	if !ok {
		http.Error(w, "invalid description", http.StatusBadRequest)
		return
	}
	groupLink, ok := clipLink(req.GroupLink)
	if !ok {
		http.Error(w, "invalid link", http.StatusBadRequest)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	port, err := a.store.UpdatePortMeta(ctx, code, name, description, groupLink)
	if err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	if req.FirstAction != nil {
		port, err = a.store.SetPortFirstAction(ctx, code, *req.FirstAction)
		if err != nil {
			if errors.Is(err, mongo.ErrNoDocuments) {
				http.Error(w, "not found", http.StatusNotFound)
				return
			}
			http.Error(w, "invalid first action", http.StatusBadRequest)
			return
		}
	}
	if req.Offmode != nil {
		port, err = a.store.SetPortOffmode(ctx, code, *req.Offmode)
		if err != nil {
			if errors.Is(err, mongo.ErrNoDocuments) {
				http.Error(w, "not found", http.StatusNotFound)
				return
			}
			http.Error(w, "unavailable", http.StatusServiceUnavailable)
			return
		}
		if a.live != nil {
			a.live.PushOffmodePolicy()
		}
	}
	if req.ClientID != nil {
		port, err = a.store.SetPortClientID(ctx, code, strings.TrimSpace(*req.ClientID))
		if err != nil {
			if errors.Is(err, mongo.ErrNoDocuments) {
				http.Error(w, "client not found", http.StatusBadRequest)
				return
			}
			http.Error(w, "unavailable", http.StatusServiceUnavailable)
			return
		}
	}
	views := a.enrichPortViews(ctx, []portListItem{portView(port)})
	writeJSON(w, http.StatusOK, views[0])
}

func (a *API) DeletePort(w http.ResponseWriter, r *http.Request) {
	if !a.requireAdmin(w, r) {
		return
	}
	if r.Method != http.MethodDelete {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	code := r.PathValue("code")
	if !protocol.ValidPort(code) {
		http.Error(w, "invalid port", http.StatusBadRequest)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	if err := a.store.DeletePort(ctx, code); err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

func (a *API) PrepareBind(w http.ResponseWriter, r *http.Request) {
	if !a.requireAdmin(w, r) {
		return
	}
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	requestID, err := a.store.PreparePendingBind(ctx)
	if err != nil {
		slog.Error("pending bind", "err", err)
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	writeJSON(w, http.StatusOK, map[string]int64{"requestId": requestID})
}

func (a *API) GetBind(w http.ResponseWriter, r *http.Request) {
	if !a.requireAdmin(w, r) {
		return
	}
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	requestID, err := strconv.ParseInt(r.PathValue("requestId"), 10, 64)
	if err != nil || requestID == 0 {
		http.Error(w, "invalid request", http.StatusBadRequest)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	row, err := a.store.GetPendingBind(ctx, requestID)
	if err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	type bindView struct {
		store.PendingBind
		HasGroupPhoto bool `json:"hasGroupPhoto"`
	}
	writeJSON(w, http.StatusOK, bindView{PendingBind: row, HasGroupPhoto: len(row.GroupPhotoData) > 0})
}

func (a *API) BindPhoto(w http.ResponseWriter, r *http.Request) {
	if !a.requireAdmin(w, r) {
		return
	}
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	requestID, err := strconv.ParseInt(r.PathValue("requestId"), 10, 64)
	if err != nil || requestID == 0 {
		http.Error(w, "invalid request", http.StatusBadRequest)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	row, err := a.store.GetPendingBind(ctx, requestID)
	if err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	if len(row.GroupPhotoData) == 0 {
		http.Error(w, "not found", http.StatusNotFound)
		return
	}
	mime := row.GroupPhotoMime
	if mime == "" {
		mime = "image/jpeg"
	}
	w.Header().Set("Content-Type", mime)
	w.Header().Set("Cache-Control", "private, max-age=300")
	_, _ = w.Write(row.GroupPhotoData)
}

func clipPhotoPayload(dataB64, mime string) ([]byte, string, bool) {
	dataB64 = strings.TrimSpace(dataB64)
	if dataB64 == "" {
		return nil, "", true
	}
	raw, err := base64.StdEncoding.DecodeString(dataB64)
	if err != nil || len(raw) == 0 || len(raw) > 512*1024 {
		return nil, "", false
	}
	if sniffed := sniffImageMime(raw); sniffed != "" {
		return raw, sniffed, true
	}
	switch strings.TrimSpace(strings.ToLower(mime)) {
	case "image/jpg", "image/jpeg":
		return raw, "image/jpeg", true
	case "image/png":
		return raw, "image/png", true
	case "image/webp":
		return raw, "image/webp", true
	default:
		return nil, "", false
	}
}

func sniffImageMime(raw []byte) string {
	if len(raw) >= 3 && raw[0] == 0xff && raw[1] == 0xd8 && raw[2] == 0xff {
		return "image/jpeg"
	}
	if len(raw) >= 8 && raw[0] == 0x89 && raw[1] == 'P' && raw[2] == 'N' && raw[3] == 'G' {
		return "image/png"
	}
	if len(raw) >= 12 && string(raw[0:4]) == "RIFF" && string(raw[8:12]) == "WEBP" {
		return "image/webp"
	}
	return ""
}

func clipFileLink(value string) string {
	link, ok := clipLink(value)
	if !ok {
		return ""
	}
	return link
}

func clipLink(value string) (string, bool) {
	value = strings.TrimSpace(value)
	if value == "" {
		return "", true
	}
	if utf8.RuneCountInString(value) > 300 {
		return "", false
	}
	lower := strings.ToLower(value)
	switch {
	case strings.HasPrefix(lower, "https://t.me/"),
		strings.HasPrefix(lower, "https://telegram.me/"),
		strings.HasPrefix(lower, "http://t.me/"),
		strings.HasPrefix(lower, "http://telegram.me/"):
		return value, true
	default:
		return "", false
	}
}

func clipText(value string, max int) (string, bool) {
	value = strings.TrimSpace(value)
	if utf8.RuneCountInString(value) > max {
		return "", false
	}
	return value, true
}
