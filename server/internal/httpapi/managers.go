package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"time"

	"go.mongodb.org/mongo-driver/v2/mongo"

	"github.com/base/realtime/internal/store"
)

// PortManagers handles GET/POST /v1/ports/managers.
func (a *API) PortManagers(w http.ResponseWriter, r *http.Request) {
	if !a.requireAdmin(w, r) {
		return
	}
	switch r.Method {
	case http.MethodGet:
		a.getPortManager(w, r)
	case http.MethodPost:
		a.setPortManager(w, r)
	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

func (a *API) getPortManager(w http.ResponseWriter, r *http.Request) {
	groupID, _ := strconv.ParseInt(r.URL.Query().Get("groupId"), 10, 64)
	telegramID, _ := strconv.ParseInt(r.URL.Query().Get("telegramId"), 10, 64)
	if groupID == 0 {
		http.Error(w, "groupId required", http.StatusBadRequest)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	port, err := a.store.FindPortByGroup(ctx, groupID)
	if err != nil {
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	if port.Code == "" {
		http.Error(w, "port not found", http.StatusNotFound)
		return
	}
	writeJSON(w, http.StatusOK, managerPayload(port, telegramID, ""))
}

func (a *API) setPortManager(w http.ResponseWriter, r *http.Request) {
	var req struct {
		GroupID    int64  `json:"groupId"`
		TelegramID int64  `json:"telegramId"`
		Action     string `json:"action"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.GroupID == 0 || req.TelegramID <= 0 {
		http.Error(w, "invalid body", http.StatusBadRequest)
		return
	}
	action := strings.ToLower(strings.TrimSpace(req.Action))
	if action == "" {
		action = "add"
	}
	if action != "add" && action != "remove" {
		http.Error(w, "invalid action", http.StatusBadRequest)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	var (
		port store.Port
		err  error
	)
	if action == "remove" {
		port, err = a.store.RemovePortManagerByGroup(ctx, req.GroupID, req.TelegramID)
	} else {
		port, err = a.store.AddPortManagerByGroup(ctx, req.GroupID, req.TelegramID)
	}
	if errors.Is(err, mongo.ErrNoDocuments) {
		http.Error(w, "port not found", http.StatusNotFound)
		return
	}
	if err != nil {
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	writeJSON(w, http.StatusOK, managerPayload(port, req.TelegramID, action))
}

func managerPayload(port store.Port, telegramID int64, action string) map[string]any {
	isManager := false
	for _, id := range port.Managers {
		if id == telegramID {
			isManager = true
			break
		}
	}
	out := map[string]any{
		"ok":         true,
		"code":       port.Code,
		"name":       port.Name,
		"telegramId": telegramID,
		"isManager":  isManager,
		"managers":   port.Managers,
	}
	if action != "" {
		out["action"] = action
	}
	return out
}

// PanelAccess GET /v1/panel/access?telegramId=&userId=
func (a *API) PanelAccess(w http.ResponseWriter, r *http.Request) {
	if !a.requireAdmin(w, r) {
		return
	}
	telegramID, _ := strconv.ParseInt(r.URL.Query().Get("telegramId"), 10, 64)
	if telegramID <= 0 {
		http.Error(w, "telegramId required", http.StatusBadRequest)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	ports, err := a.store.ListPortsManagedBy(ctx, telegramID)
	if err != nil {
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	codes := make([]string, 0, len(ports))
	for _, p := range ports {
		if p.Code != "" {
			codes = append(codes, p.Code)
		}
	}
	out := map[string]any{
		"telegramId": telegramID,
		"manager":    len(codes) > 0,
		"ports":      codes,
	}
	userID := r.URL.Query().Get("userId")
	if userID != "" {
		ok, err := a.store.UserTouchesAnyPort(ctx, userID, codes)
		if err != nil {
			http.Error(w, "unavailable", http.StatusServiceUnavailable)
			return
		}
		out["userOk"] = ok
	}
	writeJSON(w, http.StatusOK, out)
}
