package ws

import (
	"context"
	"encoding/json"
	"log/slog"
	"strings"
	"time"

	"github.com/base/realtime/internal/aiguide"
	"github.com/base/realtime/internal/protocol"
)

func (h *Hub) handleAiUiGuideRequest(userID, deviceID string, env protocol.Envelope) {
	started := time.Now()
	slog.Info("ai.ui.guide.request received",
		"userId", userID,
		"deviceId", deviceID,
		"id", env.ID,
		"t0", started.Format(time.RFC3339Nano),
	)
	var req protocol.AiUiGuideRequestPayload
	if json.Unmarshal(env.Payload, &req) != nil {
		slog.Warn("ai.ui.guide.request bad payload", "userId", userID, "deviceId", deviceID)
		return
	}
	req.RequestID = strings.TrimSpace(req.RequestID)
	if req.RequestID == "" {
		req.RequestID = env.ID
	}
	promptID, systemPrompt := aiguide.PromptForPhase(req.Phase)
	// Cache disabled for now — always live Ollama for real latency measurement.
	cacheKey := aiguide.CacheKey(
		promptID, req.Family, req.OemSkin, req.Sdk, req.Phase, req.TapMode, req.TargetPkg, req.ScreenHash,
	)
	slog.Info("ai.ui.guide.request meta",
		"requestId", req.RequestID,
		"phase", req.Phase,
		"tapMode", req.TapMode,
		"targetPkg", req.TargetPkg,
		"family", req.Family,
		"oemSkin", req.OemSkin,
		"nodes", len(req.Nodes),
		"screenHash", req.ScreenHash,
		"cacheKey", cacheKey,
		"cache", "disabled",
	)

	ctx, cancel := context.WithTimeout(h.root, h.cfg.AiTimeout+2*time.Second)
	defer cancel()

	source := "ai"
	failErr := ""
	actions := []protocol.AiUiGuideAction{{Op: "give_up"}}
	var ollamaMs int64
	assistantRaw := ""
	userJSON := ""
	if b, err := json.Marshal(req); err == nil {
		userJSON = string(b)
	}

	if h.ai != nil && h.ai.Enabled() {
		var overrides map[string]string
		octx, ocancel := context.WithTimeout(h.root, 2*time.Second)
		overrides, _ = h.store.AIPromptOverrides(octx)
		ocancel()
		slog.Info("ai.ui.guide calling ollama",
			"url", h.cfg.AiURL,
			"model", h.cfg.AiModel,
			"promptId", promptID,
			"timeout", h.cfg.AiTimeout.String(),
			"userId", userID,
			"deviceId", deviceID,
		)
		ollamaStart := time.Now()
		guide, err := h.ai.Guide(ctx, req, overrides)
		ollamaMs = time.Since(ollamaStart).Milliseconds()
		if guide.UserJSON != "" {
			userJSON = guide.UserJSON
		}
		assistantRaw = guide.AssistantRaw
		if err != nil {
			failErr = err.Error()
			slog.Warn("ai.ui.guide call failed",
				"err", err,
				"ollamaMs", ollamaMs,
				"userId", userID,
				"deviceId", deviceID,
			)
			source = "give_up"
			actions = []protocol.AiUiGuideAction{{Op: "give_up"}}
		} else {
			actions = guide.Actions
			source = "ai"
			if guide.OllamaMs > 0 {
				ollamaMs = guide.OllamaMs
			}
			slog.Info("ai.ui.guide ollama ok",
				"actions", len(actions),
				"ollamaMs", ollamaMs,
				"userId", userID,
				"deviceId", deviceID,
			)
		}
	} else {
		source = "give_up"
		failErr = "ai disabled (REALTIME_AI_URL empty or unset after load)"
		slog.Warn("ai.ui.guide skipped", "reason", failErr)
	}

	totalMs := time.Since(started).Milliseconds()
	model := h.cfg.AiModel
	if h.ai != nil {
		model = h.ai.Model()
	}
	aiguide.DefaultLog.Add(aiguide.Transcript{
		RequestID: req.RequestID,
		UserID:    userID,
		DeviceID:  deviceID,
		Phase:     req.Phase,
		TapMode:   req.TapMode,
		TargetPkg: req.TargetPkg,
		Source:    source,
		Model:     model,
		System:    systemPrompt,
		User:      userJSON,
		Assistant: assistantRaw,
		Actions:   actions,
		OllamaMs:  ollamaMs,
		TotalMs:   totalMs,
		Error:     failErr,
	})

	payload := protocol.AiUiGuidePayload{
		RequestID: req.RequestID,
		CacheKey:  cacheKey,
		Source:    source,
		Error:     failErr,
		Actions:   actions,
		OllamaMs:  ollamaMs,
		TotalMs:   totalMs,
	}
	messageID, body, err := protocol.AiUiGuideReply(req.RequestID, payload)
	if err != nil {
		slog.Warn("ai.ui.guide marshal", "err", err)
		return
	}
	enqueueStart := time.Now()
	enqueueCtx, enqueueCancel := context.WithTimeout(h.root, 5*time.Second)
	defer enqueueCancel()
	if err := h.outbox.Enqueue(enqueueCtx, userID, deviceID, "ai.ui.guide:"+req.RequestID, messageID, body); err != nil {
		slog.Warn("ai.ui.guide enqueue", "err", err, "userId", userID, "deviceId", deviceID)
	} else {
		slog.Info("ai.ui.guide reply enqueued",
			"requestId", req.RequestID,
			"source", source,
			"ollamaMs", ollamaMs,
			"totalMs", totalMs,
			"enqueueMs", time.Since(enqueueStart).Milliseconds(),
		)
	}
}

func (h *Hub) handleAiUiGuideResult(env protocol.Envelope) {
	var result protocol.AiUiGuideResultPayload
	if json.Unmarshal(env.Payload, &result) != nil {
		return
	}
	slog.Info("ai.ui.guide.result",
		"requestId", result.RequestID,
		"ok", result.OK,
		"error", result.Error,
		"cacheKey", result.CacheKey,
		"actions", len(result.Actions),
		"cache", "disabled",
	)
	// Cache upsert intentionally skipped until a full cache design lands.
}
