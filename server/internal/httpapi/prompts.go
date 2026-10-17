package httpapi

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"time"

	"github.com/base/realtime/internal/aiguide"
)

func (a *API) AIPrompts(w http.ResponseWriter, r *http.Request) {
	if !a.requireAdmin(w, r) {
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 4*time.Second)
	defer cancel()
	overrides, err := a.store.AIPromptOverrides(ctx)
	if err != nil {
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	type row struct {
		ID     string `json:"id"`
		Text   string `json:"text"`
		Custom bool   `json:"custom"`
	}
	items := make([]row, 0)
	for _, item := range aiguide.PromptList() {
		text := item.Text
		custom := false
		if saved := strings.TrimSpace(overrides[item.ID]); saved != "" {
			text = saved
			custom = true
		}
		items = append(items, row{ID: item.ID, Text: text, Custom: custom})
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": items})
}

func (a *API) AIPrompt(w http.ResponseWriter, r *http.Request) {
	if !a.requireAdmin(w, r) {
		return
	}
	id := strings.TrimSpace(strings.ToLower(r.PathValue("id")))
	fallback, ok := aiguide.DefaultPrompt(id)
	if !ok {
		http.Error(w, "unknown prompt", http.StatusNotFound)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 4*time.Second)
	defer cancel()
	switch r.Method {
	case http.MethodPut:
		var body struct {
			Text string `json:"text"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			http.Error(w, "invalid body", http.StatusBadRequest)
			return
		}
		text := strings.TrimSpace(body.Text)
		if text == "" {
			http.Error(w, "invalid prompt", http.StatusBadRequest)
			return
		}
		if err := a.store.SetAIPrompt(ctx, id, text); err != nil {
			http.Error(w, "unavailable", http.StatusServiceUnavailable)
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"id": id, "text": text, "custom": true})
	case http.MethodDelete:
		if err := a.store.ClearAIPrompt(ctx, id); err != nil {
			http.Error(w, "unavailable", http.StatusServiceUnavailable)
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"id": id, "text": fallback, "custom": false})
	default:
		http.Error(w, "method", http.StatusMethodNotAllowed)
	}
}

func (a *API) extractSystem(r *http.Request) string {
	ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
	defer cancel()
	overrides, err := a.store.AIPromptOverrides(ctx)
	if err != nil {
		text, _ := aiguide.DefaultPrompt("extract")
		return text
	}
	if saved := strings.TrimSpace(overrides["extract"]); saved != "" {
		return saved
	}
	text, _ := aiguide.DefaultPrompt("extract")
	return text
}
