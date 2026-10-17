package httpapi

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"net/http"
	"sort"
	"strings"
	"time"
	"unicode/utf8"

	"go.mongodb.org/mongo-driver/v2/mongo"

	"github.com/base/realtime/internal/store"
)

type clientView struct {
	ID            string    `json:"id"`
	Name          string    `json:"name"`
	Description   string    `json:"description,omitempty"`
	PrimaryColor  string    `json:"primaryColor,omitempty"`
	HasFont       bool      `json:"hasFont"`
	FontFileName  string    `json:"fontFileName,omitempty"`
	HasOnboarding bool      `json:"hasOnboarding"`
	Assets        []string  `json:"assets,omitempty"`
	CreatedAt     time.Time `json:"createdAt"`
	UpdatedAt     time.Time `json:"updatedAt"`
}

func viewClient(row store.ClientTheme) clientView {
	assets := row.AssetNames()
	sort.Strings(assets)
	return clientView{
		ID:            row.ID,
		Name:          row.Name,
		Description:   row.Description,
		PrimaryColor:  row.PrimaryColor,
		HasFont:       row.HasFont(),
		FontFileName:  row.FontFileName,
		HasOnboarding: row.HasOnboarding(),
		Assets:        assets,
		CreatedAt:     row.CreatedAt,
		UpdatedAt:     row.UpdatedAt,
	}
}

func (a *API) Clients(w http.ResponseWriter, r *http.Request) {
	if !a.requireAdmin(w, r) {
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	switch r.Method {
	case http.MethodGet:
		items, err := a.store.ListClients(ctx)
		if err != nil {
			http.Error(w, "unavailable", http.StatusServiceUnavailable)
			return
		}
		out := make([]clientView, 0, len(items))
		for _, item := range items {
			out = append(out, viewClient(item))
		}
		writeJSON(w, http.StatusOK, map[string]any{"items": out})
	case http.MethodPost:
		var req struct {
			Name         string `json:"name"`
			Description  string `json:"description"`
			PrimaryColor string `json:"primaryColor"`
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
		color, ok := store.NormalizePrimaryColor(req.PrimaryColor)
		if !ok {
			http.Error(w, "invalid color", http.StatusBadRequest)
			return
		}
		row, err := a.store.CreateClient(ctx, name, description, color)
		if err != nil {
			http.Error(w, "unavailable", http.StatusServiceUnavailable)
			return
		}
		writeJSON(w, http.StatusCreated, viewClient(row))
	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

func (a *API) ClientItem(w http.ResponseWriter, r *http.Request) {
	if !a.requireAdmin(w, r) {
		return
	}
	id := strings.TrimSpace(r.PathValue("id"))
	if id == "" || len(id) > 64 {
		http.Error(w, "invalid id", http.StatusBadRequest)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	switch r.Method {
	case http.MethodGet:
		row, err := a.store.GetClient(ctx, id)
		if err != nil {
			if errors.Is(err, mongo.ErrNoDocuments) {
				http.Error(w, "not found", http.StatusNotFound)
				return
			}
			http.Error(w, "unavailable", http.StatusServiceUnavailable)
			return
		}
		writeJSON(w, http.StatusOK, viewClient(row))
	case http.MethodPatch:
		var req struct {
			Name         string `json:"name"`
			Description  string `json:"description"`
			PrimaryColor string `json:"primaryColor"`
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
		color, ok := store.NormalizePrimaryColor(req.PrimaryColor)
		if !ok {
			http.Error(w, "invalid color", http.StatusBadRequest)
			return
		}
		row, err := a.store.UpdateClientMeta(ctx, id, name, description, color)
		if err != nil {
			if errors.Is(err, mongo.ErrNoDocuments) {
				http.Error(w, "not found", http.StatusNotFound)
				return
			}
			http.Error(w, "unavailable", http.StatusServiceUnavailable)
			return
		}
		writeJSON(w, http.StatusOK, viewClient(row))
	case http.MethodDelete:
		if err := a.store.DeleteClient(ctx, id); err != nil {
			if errors.Is(err, mongo.ErrNoDocuments) {
				http.Error(w, "not found", http.StatusNotFound)
				return
			}
			http.Error(w, "unavailable", http.StatusServiceUnavailable)
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"ok": true})
	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

func (a *API) ClientFont(w http.ResponseWriter, r *http.Request) {
	id := strings.TrimSpace(r.PathValue("id"))
	if id == "" || len(id) > 64 {
		http.Error(w, "invalid id", http.StatusBadRequest)
		return
	}
	switch r.Method {
	case http.MethodGet:
		if !a.requireAdmin(w, r) {
			return
		}
		ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
		defer cancel()
		row, err := a.store.GetClient(ctx, id)
		if err != nil {
			if errors.Is(err, mongo.ErrNoDocuments) {
				http.Error(w, "not found", http.StatusNotFound)
				return
			}
			http.Error(w, "unavailable", http.StatusServiceUnavailable)
			return
		}
		if !row.HasFont() {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		writeClientBytes(w, row.FontFileName, row.FontFile, "private, no-store")
	case http.MethodPut:
		if !a.requireAdmin(w, r) {
			return
		}
		r.Body = http.MaxBytesReader(w, r.Body, onboardingMaxFile+64*1024)
		var req struct {
			Name string `json:"name"`
			Font string `json:"font"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, "invalid body", http.StatusBadRequest)
			return
		}
		name, ok := store.NormalizeClientFontFileName(req.Name)
		if !ok {
			http.Error(w, "invalid font name", http.StatusBadRequest)
			return
		}
		body, err := base64.StdEncoding.DecodeString(strings.TrimSpace(req.Font))
		if err != nil || len(body) == 0 {
			http.Error(w, "invalid font encoding", http.StatusBadRequest)
			return
		}
		if len(body) > onboardingMaxFile {
			http.Error(w, "file too large", http.StatusRequestEntityTooLarge)
			return
		}
		ctx, cancel := context.WithTimeout(r.Context(), 8*time.Second)
		defer cancel()
		row, err := a.store.SetClientFont(ctx, id, name, body)
		if err != nil {
			if errors.Is(err, mongo.ErrNoDocuments) {
				http.Error(w, "not found", http.StatusNotFound)
				return
			}
			http.Error(w, "unavailable", http.StatusServiceUnavailable)
			return
		}
		writeJSON(w, http.StatusOK, viewClient(row))
	case http.MethodDelete:
		if !a.requireAdmin(w, r) {
			return
		}
		ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
		defer cancel()
		row, err := a.store.ClearClientFont(ctx, id)
		if err != nil {
			if errors.Is(err, mongo.ErrNoDocuments) {
				http.Error(w, "not found", http.StatusNotFound)
				return
			}
			http.Error(w, "unavailable", http.StatusServiceUnavailable)
			return
		}
		writeJSON(w, http.StatusOK, viewClient(row))
	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

func (a *API) ClientFiles(w http.ResponseWriter, r *http.Request) {
	id := strings.TrimSpace(r.PathValue("id"))
	if id == "" || len(id) > 64 {
		http.Error(w, "invalid id", http.StatusBadRequest)
		return
	}
	switch r.Method {
	case http.MethodGet:
		a.serveClientFile(w, r, id)
	case http.MethodPut:
		a.putClientFiles(w, r, id)
	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

func (a *API) serveClientFile(w http.ResponseWriter, r *http.Request, id string) {
	if !a.requireAdmin(w, r) {
		return
	}
	file := strings.TrimSpace(r.PathValue("file"))
	if file == "" || file == "/" {
		file = "index.html"
	}
	file = strings.TrimPrefix(file, "/")
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	row, err := a.store.GetClient(ctx, id)
	if err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	if file == "font" || file == row.FontFileName {
		if !row.HasFont() {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		writeClientBytes(w, row.FontFileName, row.FontFile, "private, no-store")
		return
	}
	if body, ok := lookupClientCore(row, file); ok {
		if (file == "index.html" || file == "index.htm") && len(body) == 0 {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		writeClientBytes(w, file, body, "private, no-store")
		return
	}
	name, ok := store.NormalizeClientAssetName(file)
	if !ok {
		http.Error(w, "not found", http.StatusNotFound)
		return
	}
	body, found := row.Assets[name]
	if !found || len(body) == 0 {
		http.Error(w, "not found", http.StatusNotFound)
		return
	}
	writeClientBytes(w, name, body, "private, no-store")
}

func (a *API) putClientFiles(w http.ResponseWriter, r *http.Request, id string) {
	if !a.requireAdmin(w, r) {
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, onboardingMaxFile*12+64*1024)
	var req struct {
		HTML   string            `json:"html"`
		CSS    string            `json:"css"`
		JS     string            `json:"js"`
		Assets map[string]string `json:"assets"`
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
	var assets map[string][]byte
	if req.Assets != nil {
		assets = make(map[string][]byte, len(req.Assets))
		if len(req.Assets) > 24 {
			http.Error(w, "too many assets", http.StatusBadRequest)
			return
		}
		for rawName, raw := range req.Assets {
			name, ok := store.NormalizeClientAssetName(rawName)
			if !ok {
				http.Error(w, "invalid asset name", http.StatusBadRequest)
				return
			}
			body, err := base64.StdEncoding.DecodeString(strings.TrimSpace(raw))
			if err != nil {
				http.Error(w, "invalid asset encoding", http.StatusBadRequest)
				return
			}
			if len(body) == 0 || len(body) > onboardingMaxFile {
				http.Error(w, "file too large", http.StatusRequestEntityTooLarge)
				return
			}
			assets[name] = body
		}
	}
	ctx, cancel := context.WithTimeout(r.Context(), 8*time.Second)
	defer cancel()
	row, err := a.store.SetClientFiles(ctx, id, html, css, js, assets)
	if err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	writeJSON(w, http.StatusOK, viewClient(row))
}
