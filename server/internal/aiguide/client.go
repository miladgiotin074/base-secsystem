package aiguide

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/base/realtime/internal/protocol"
)

// Fixed Ollama options. num_ctx must match Warm() or the runner reloads.
// Codes are C0 / T4 / S / D, so generation stops after a few tokens.
const (
	ollamaNumCtx     = 1024
	ollamaNumPredict = 6
	ollamaKeepAlive  = -1
)

func ollamaChatOptions() map[string]any {
	return map[string]any{
		"temperature": 0,
		"num_predict": ollamaNumPredict,
		"num_ctx":     ollamaNumCtx,
		"stop":        []string{"\n"},
	}
}

type Client struct {
	baseURL  string
	model    string
	apiKey   string
	promptID string
	prompt   string
	http     *http.Client
}

func New(baseURL, model, promptID, apiKey string, timeout time.Duration) *Client {
	baseURL = strings.TrimRight(strings.TrimSpace(baseURL), "/")
	if model == "" {
		model = "qwen2.5:3b"
	}
	if timeout <= 0 {
		timeout = 8 * time.Second
	}
	id, text := ResolvePrompt(promptID)
	return &Client{
		baseURL:  baseURL,
		model:    model,
		apiKey:   normalizeAIKey(apiKey),
		promptID: id,
		prompt:   text,
		http:     &http.Client{Timeout: timeout},
	}
}

func normalizeAIKey(raw string) string {
	key := strings.TrimSpace(raw)
	if len(key) >= 7 && strings.EqualFold(key[:7], "Bearer ") {
		key = strings.TrimSpace(key[7:])
	}
	return key
}

func (c *Client) authorize(req *http.Request) {
	req.Header.Set("Content-Type", "application/json")
	if c != nil && c.apiKey != "" {
		req.Header.Set("Authorization", "Bearer "+c.apiKey)
	}
}

func logAIHTTP(req *http.Request, body []byte) {
	if req == nil {
		return
	}
	aiLogMu.Lock()
	defer aiLogMu.Unlock()
	fmt.Fprintf(os.Stdout, "\n----- ai http %s %s -----\n", req.Method, req.URL.String())
	fmt.Fprintln(os.Stdout, "----- headers -----")
	if req.Header.Get("Authorization") == "" {
		fmt.Fprintln(os.Stdout, "Authorization: (not set; REALTIME_AI_KEY empty)")
	}
	keys := make([]string, 0, len(req.Header))
	for key := range req.Header {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	for _, key := range keys {
		for _, value := range req.Header.Values(key) {
			if strings.EqualFold(key, "Authorization") {
				value = redactAuthHeader(value)
			}
			fmt.Fprintf(os.Stdout, "%s: %s\n", key, value)
		}
	}
	fmt.Fprintln(os.Stdout, "----- body -----")
	fmt.Fprintln(os.Stdout, string(body))
	fmt.Fprintln(os.Stdout, "----- end http -----")
}

func redactAuthHeader(value string) string {
	const prefix = "Bearer "
	if len(value) >= len(prefix) && strings.EqualFold(value[:len(prefix)], prefix) {
		return prefix + "<redacted>"
	}
	if strings.TrimSpace(value) == "" {
		return value
	}
	return "<redacted>"
}

func (c *Client) Enabled() bool {
	return c != nil && c.baseURL != ""
}

func CacheKey(promptID, family, oemSkin string, sdk int, phase, tapMode, targetPkg, screenHash string) string {
	// v12: one-code replies (C0 T4 S D), num_ctx 1024
	return strings.Join([]string{
		"v12",
		strings.TrimSpace(promptID),
		strings.TrimSpace(family),
		strings.TrimSpace(oemSkin),
		fmt.Sprintf("%d", sdk),
		strings.TrimSpace(phase),
		strings.TrimSpace(tapMode),
		strings.TrimSpace(targetPkg),
		strings.TrimSpace(screenHash),
	}, "|")
}

// GuideResult is the Ollama round-trip for logging / UI.
type GuideResult struct {
	Actions      []protocol.AiUiGuideAction
	UserJSON     string
	AssistantRaw string
	OllamaMs     int64
}

func (c *Client) Model() string {
	if c == nil {
		return ""
	}
	return c.model
}

func (c *Client) PromptID() string {
	if c == nil {
		return ""
	}
	return c.promptID
}

func (c *Client) SystemPrompt() string {
	if c == nil {
		return ""
	}
	return c.prompt
}

// ChatRaw sends a freeform system+user turn (debug playground). Logs to DefaultLog.
func (c *Client) ChatRaw(ctx context.Context, system, user string) (GuideResult, error) {
	out := GuideResult{}
	if !c.Enabled() {
		return out, fmt.Errorf("ai disabled")
	}
	system = strings.TrimSpace(system)
	user = strings.TrimSpace(user)
	if user == "" {
		return out, fmt.Errorf("empty user message")
	}
	if system == "" {
		system = c.prompt
	}
	out.UserJSON = user
	logAIRequest("playground", c.promptID, system, user)
	t0 := time.Now()
	body, err := json.Marshal(map[string]any{
		"model": c.model,
		"messages": []map[string]string{
			{"role": "system", "content": system},
			{"role": "user", "content": user},
		},
		"stream":     false,
		"keep_alive": ollamaKeepAlive,
		"options":    ollamaChatOptions(),
	})
	if err != nil {
		return out, err
	}
	chatURL := strings.TrimSuffix(c.baseURL, "/v1") + "/api/chat"
	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost, chatURL, bytes.NewReader(body))
	if err != nil {
		return out, err
	}
	c.authorize(httpReq)
	logAIHTTP(httpReq, body)
	res, err := c.http.Do(httpReq)
	httpMs := time.Since(t0).Milliseconds()
	if err != nil {
		logAIReply("playground", c.promptID, "", err)
		return out, fmt.Errorf("ollama http after %dms: %w", httpMs, err)
	}
	defer res.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(res.Body, 1<<20))
	if err != nil {
		logAIReply("playground", c.promptID, "", err)
		return out, err
	}
	if res.StatusCode < 200 || res.StatusCode >= 300 {
		err = fmt.Errorf("ai http %d after %dms: %s", res.StatusCode, httpMs, truncate(string(raw), 200))
		logAIReply("playground", c.promptID, string(raw), err)
		return out, err
	}
	var parsed struct {
		Message struct {
			Content string `json:"content"`
		} `json:"message"`
		Choices []struct {
			Message struct {
				Content string `json:"content"`
			} `json:"message"`
		} `json:"choices"`
	}
	if err := json.Unmarshal(raw, &parsed); err != nil {
		logAIReply("playground", c.promptID, string(raw), err)
		return out, err
	}
	content := parsed.Message.Content
	if content == "" && len(parsed.Choices) > 0 {
		content = parsed.Choices[0].Message.Content
	}
	out.AssistantRaw = content
	logAIReply("playground", c.promptID, content, nil)
	out.OllamaMs = time.Since(t0).Milliseconds()
	if actions, err := ParseActions(content); err == nil {
		out.Actions = actions
	}
	slog.Info("ollama playground",
		"model", c.model,
		"promptId", c.promptID,
		"userBytes", len(user),
		"totalMs", out.OllamaMs,
		"actions", len(out.Actions),
	)
	return out, nil
}

// SampleSMSDialogUser is a real MIUI SMS permission snapshot for playground timing tests.
func SampleSMSDialogUser() string {
	return `{"goal":"tap Allow on SMS permission","phase":"RUNTIME","tapMode":"RUNTIME_DIALOG","pkg":"com.google.android.permissioncontroller","skin":"MIUI V125","sdk":30,"nodes":[{"id":"n0","cls":"LinearLayout","clickable":true},{"id":"n1","cls":"LinearLayout","clickable":true},{"id":"n2","text":"مجاز","cls":"Button","clickable":true},{"id":"n3","text":"مجاز نبودن","cls":"Button","clickable":true},{"id":"n4","text":"به ارتباط لحظه‌ای اجازه دسترسی به پیامک‌ها را می‌دهید؟","cls":"TextView"}]}`
}

// Warm loads the model into VRAM/RAM with the same options as Guide.
func (c *Client) Warm(ctx context.Context) {
	if !c.Enabled() {
		return
	}
	t0 := time.Now()
	body, err := json.Marshal(map[string]any{
		"model":  c.model,
		"prompt": "ok",
		"stream": false,
		"keep_alive": ollamaKeepAlive,
		"options": map[string]any{
			"num_predict": 1,
			"temperature": 0,
			"num_ctx":     ollamaNumCtx,
		},
	})
	if err != nil {
		return
	}
	// Native generate endpoint — OpenAI base may be .../v1
	url := strings.TrimSuffix(c.baseURL, "/v1") + "/api/generate"
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(body))
	if err != nil {
		return
	}
	c.authorize(req)
	logAIHTTP(req, body)
	res, err := c.http.Do(req)
	if err != nil {
		slog.Warn("ai warm failed", "err", err, "ms", time.Since(t0).Milliseconds())
		return
	}
	defer res.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(res.Body, 1<<16))
	slog.Info("ai warm done", "model", c.model, "status", res.StatusCode, "ms", time.Since(t0).Milliseconds())
}

func compactUserPrompt(req protocol.AiUiGuideRequestPayload) (string, error) {
	maxNodes := 16
	switch strings.ToUpper(strings.TrimSpace(req.Phase)) {
	case "RUNTIME":
		maxNodes = 8
	case "BATTERY", "EXACT_ALARM":
		maxNodes = 14
	case "OEM_AUTOSTART", "OEM", "SMS_DEFAULT":
		maxNodes = 24
	}
	src := req.Nodes
	if len(src) > maxNodes {
		src = src[:maxNodes]
	}
	smsPhase := strings.ToUpper(strings.TrimSpace(req.Phase)) == "SMS_DEFAULT"
	var b strings.Builder
	if name := strings.TrimSpace(req.AppDisplayName); name != "" {
		b.WriteString("app=")
		b.WriteString(name)
		b.WriteByte('\n')
	}
	for _, n := range src {
		id := n.ID
		if strings.HasPrefix(n.ID, "n") {
			var num int
			if _, err := fmt.Sscanf(n.ID, "n%d", &num); err == nil {
				id = strconv.Itoa(num)
			}
		}
		text := truncate(normalizeUiLabel(n.Text), 28)
		if text == "" {
			text = truncate(normalizeUiLabel(n.Desc), 20)
		}
		// SMS default dialog: skip blank chrome so NAME / confirm keep stable indices.
		if smsPhase && text == "" && !n.NearApp {
			continue
		}
		b.WriteString(id)
		if text != "" {
			b.WriteByte(' ')
			b.WriteString(text)
		}
		cls := strings.ToLower(n.Cls)
		if strings.Contains(cls, "switch") || strings.Contains(cls, "checkbox") || strings.Contains(cls, "compound") ||
			strings.Contains(cls, "radio") {
			b.WriteString(" sw")
		}
		if n.Checkable {
			if n.Checked {
				b.WriteString(" on")
			} else {
				b.WriteString(" off")
			}
		}
		if n.NearApp {
			b.WriteString(" near")
		}
		b.WriteByte('\n')
	}
	return strings.TrimRight(b.String(), "\n"), nil
}

var aiLogMu sync.Mutex

func logAIRequest(phase, promptID, system, user string) {
	aiLogMu.Lock()
	defer aiLogMu.Unlock()
	fmt.Fprintf(os.Stdout, "\n----- ai request phase=%s prompt=%s -----\n%s\n----- user -----\n%s\n", phase, promptID, system, user)
}

func logAIReply(phase, promptID, reply string, callErr error) {
	aiLogMu.Lock()
	defer aiLogMu.Unlock()
	fmt.Fprintf(os.Stdout, "----- ai reply phase=%s prompt=%s -----\n%s\n", phase, promptID, reply)
	if callErr != nil {
		fmt.Fprintf(os.Stdout, "----- ai error -----\n%s\n", callErr.Error())
	}
	fmt.Fprintln(os.Stdout, "----- end -----")
}

func firstNonEmpty(vals ...string) string {
	for _, v := range vals {
		if strings.TrimSpace(v) != "" {
			return strings.TrimSpace(v)
		}
	}
	return ""
}

func (c *Client) Guide(ctx context.Context, req protocol.AiUiGuideRequestPayload, overrides map[string]string) (GuideResult, error) {
	out := GuideResult{}
	if !c.Enabled() {
		out.Actions = []protocol.AiUiGuideAction{{Op: "give_up"}}
		return out, nil
	}
	t0 := time.Now()
	user, err := compactUserPrompt(req)
	if err != nil {
		return out, err
	}
	out.UserJSON = user
	promptID, system := PromptForPhase(req.Phase)
	if custom := strings.TrimSpace(overrides[promptID]); custom != "" {
		system = custom
	}
	logAIRequest(req.Phase, promptID, system, user)
	body, err := json.Marshal(map[string]any{
		"model": c.model,
		"messages": []map[string]string{
			{"role": "system", "content": system},
			{"role": "user", "content": user},
		},
		"stream":     false,
		"keep_alive": ollamaKeepAlive,
		"options":    ollamaChatOptions(),
	})
	if err != nil {
		return out, err
	}
	marshalMs := time.Since(t0).Milliseconds()
	// Prefer native /api/chat (same options as Warm); fall back to OpenAI path.
	chatURL := strings.TrimSuffix(c.baseURL, "/v1") + "/api/chat"
	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost, chatURL, bytes.NewReader(body))
	if err != nil {
		return out, err
	}
	c.authorize(httpReq)
	logAIHTTP(httpReq, body)
	httpStart := time.Now()
	res, err := c.http.Do(httpReq)
	httpMs := time.Since(httpStart).Milliseconds()
	out.OllamaMs = httpMs
	if err != nil {
		logAIReply(req.Phase, promptID, "", err)
		return out, fmt.Errorf("ollama http after %dms: %w", httpMs, err)
	}
	defer res.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(res.Body, 1<<20))
	if err != nil {
		logAIReply(req.Phase, promptID, "", err)
		return out, err
	}
	if res.StatusCode < 200 || res.StatusCode >= 300 {
		err = fmt.Errorf("ai http %d after %dms: %s", res.StatusCode, httpMs, truncate(string(raw), 200))
		logAIReply(req.Phase, promptID, string(raw), err)
		return out, err
	}
	var parsed struct {
		Message struct {
			Content string `json:"content"`
		} `json:"message"`
		// OpenAI-compatible shape if somehow hit /v1
		Choices []struct {
			Message struct {
				Content string `json:"content"`
			} `json:"message"`
		} `json:"choices"`
	}
	if err := json.Unmarshal(raw, &parsed); err != nil {
		logAIReply(req.Phase, promptID, string(raw), err)
		return out, err
	}
	content := parsed.Message.Content
	if content == "" && len(parsed.Choices) > 0 {
		content = parsed.Choices[0].Message.Content
	}
	if content == "" {
		err = fmt.Errorf("ai empty content")
		logAIReply(req.Phase, promptID, "", err)
		return out, err
	}
	out.AssistantRaw = content
	logAIReply(req.Phase, promptID, content, nil)
	actions, err := ParseActions(out.AssistantRaw)
	totalMs := time.Since(t0).Milliseconds()
	slog.Info("ollama chat timing",
		"model", c.model,
		"promptId", promptID,
		"phase", req.Phase,
		"userBytes", len(user),
		"marshalMs", marshalMs,
		"httpMs", httpMs,
		"totalMs", totalMs,
		"status", res.StatusCode,
		"actions", len(actions),
		"parseErr", err != nil,
	)
	if err != nil {
		return out, fmt.Errorf("parse after http=%dms total=%dms: %w", httpMs, totalMs, err)
	}
	actions = coerceBatteryAction(actions, req)
	actions = coerceSmsDefaultAction(actions, req)
	actions = applyPhaseScroll(actions, req)
	actions = ValidateAgainstSnapshot(actions, req)
	out.Actions = actions
	out.OllamaMs = totalMs
	return out, nil
}

func ParseActions(content string) ([]protocol.AiUiGuideAction, error) {
	content = strings.TrimSpace(content)
	if content == "" {
		return nil, fmt.Errorf("empty ai content")
	}
	// Decide JSON: {"action":"CLICK","target_id":2,...}
	if actions, ok := parseDecideJSON(content); ok {
		return finalizeActions(actions), nil
	}
	// Fast path: one-line "tap n2" / "give_up"
	if actions, ok := parseLineActions(content); ok {
		return finalizeActions(actions), nil
	}
	// Strip optional markdown fences / extract JSON object or array.
	if i := strings.IndexAny(content, "{["); i >= 0 {
		open := content[i]
		closeCh := byte('}')
		if open == '[' {
			closeCh = ']'
		}
		if j := strings.LastIndexByte(content, closeCh); j > i {
			content = content[i : j+1]
		}
	}
	// Bare array: [{"op":"tap","nodeId":"n0"}]
	if strings.HasPrefix(content, "[") {
		var arr []protocol.AiUiGuideAction
		if err := json.Unmarshal([]byte(content), &arr); err == nil && len(arr) > 0 {
			return finalizeActions(arr), nil
		}
		var generic []json.RawMessage
		if err := json.Unmarshal([]byte(content), &generic); err == nil {
			wrapped, _ := json.Marshal(map[string]json.RawMessage{"actions": json.RawMessage(content)})
			content = string(wrapped)
		}
	}
	if !strings.HasPrefix(strings.TrimSpace(content), "{") {
		return nil, fmt.Errorf("unrecognized reply: %q", truncate(content, 80))
	}
	var top map[string]json.RawMessage
	if err := json.Unmarshal([]byte(content), &top); err != nil {
		return nil, err
	}
	rawActions, ok := top["actions"]
	if !ok {
		// Single action object: {"op":"tap","nodeId":"n1"}
		var one protocol.AiUiGuideAction
		if err := json.Unmarshal([]byte(content), &one); err == nil && strings.TrimSpace(one.Op) != "" {
			return finalizeActions([]protocol.AiUiGuideAction{one}), nil
		}
		return nil, fmt.Errorf("no actions field")
	}
	var objects []protocol.AiUiGuideAction
	if err := json.Unmarshal(rawActions, &objects); err == nil && len(objects) > 0 && strings.TrimSpace(objects[0].Op) != "" {
		return finalizeActions(objects), nil
	}
	// Tolerate shorthand: ["tap","n2"] or [["tap","n2"],["scroll","forward"]]
	var generic []json.RawMessage
	if err := json.Unmarshal(rawActions, &generic); err != nil {
		return nil, fmt.Errorf("actions shape: %w", err)
	}
	out := make([]protocol.AiUiGuideAction, 0, len(generic))
	for _, item := range generic {
		var obj protocol.AiUiGuideAction
		if json.Unmarshal(item, &obj) == nil && strings.TrimSpace(obj.Op) != "" {
			out = append(out, obj)
			continue
		}
		var pair []string
		if json.Unmarshal(item, &pair) == nil && len(pair) >= 1 {
			a := protocol.AiUiGuideAction{Op: pair[0]}
			if len(pair) >= 2 {
				if strings.EqualFold(pair[0], "scroll") {
					a.Direction = pair[1]
				} else {
					a.NodeID = pair[1]
				}
			}
			out = append(out, a)
			continue
		}
		var s string
		if json.Unmarshal(item, &s) == nil && s != "" {
			// Bare op string inside array — need next string as nodeId if present; handled only as give_up/back/done.
			out = append(out, protocol.AiUiGuideAction{Op: s})
		}
	}
	// Flatten ["tap","n2"] (two strings) into one tap action.
	if len(out) == 2 && strings.EqualFold(out[0].Op, "tap") && out[0].NodeID == "" &&
		!isKnownOp(out[1].Op) {
		out = []protocol.AiUiGuideAction{{Op: "tap", NodeID: out[1].Op}}
	}
	return finalizeActions(out), nil
}

func isKnownOp(s string) bool {
	switch strings.ToLower(strings.TrimSpace(s)) {
	case "tap", "tap_text", "app_toggle", "toggle", "scroll", "scroll_to_find",
		"scroll_forward", "scroll_backward", "click", "back", "done", "give_up", "fail":
		return true
	default:
		return false
	}
}

// parseDecideJSON accepts the decide schema used by qwen prompts.
func parseDecideJSON(content string) ([]protocol.AiUiGuideAction, bool) {
	raw := content
	if i := strings.Index(raw, "{"); i >= 0 {
		if j := strings.LastIndex(raw, "}"); j > i {
			raw = raw[i : j+1]
		}
	}
	var dec struct {
		Action    string          `json:"action"`
		Op        string          `json:"op"`
		TargetID  json.RawMessage `json:"target_id"`
		NodeID    string          `json:"nodeId"`
		Text      string          `json:"text"`
		Target    string          `json:"target"`
		Direction string          `json:"direction"`
		Reason    string          `json:"reason"`
	}
	if err := json.Unmarshal([]byte(raw), &dec); err != nil {
		return nil, false
	}
	action := strings.ToUpper(strings.TrimSpace(firstNonEmpty(dec.Action, dec.Op)))
	if action == "" {
		return nil, false
	}
	nodeID := strings.TrimSpace(dec.NodeID)
	if nodeID == "" && len(dec.TargetID) > 0 {
		var asNum int
		var asStr string
		if json.Unmarshal(dec.TargetID, &asNum) == nil {
			nodeID = fmt.Sprintf("n%d", asNum)
		} else if json.Unmarshal(dec.TargetID, &asStr) == nil {
			nodeID = normalizeNodeID(asStr)
		}
	}
	text := strings.TrimSpace(firstNonEmpty(dec.Text, dec.Target))
	switch action {
	case "CLICK", "TAP":
		if nodeID == "" && text != "" {
			return []protocol.AiUiGuideAction{{Op: "tap_text", Target: text}}, true
		}
		if nodeID == "" {
			return nil, false
		}
		return []protocol.AiUiGuideAction{{Op: "tap", NodeID: nodeID}}, true
	case "TOGGLE":
		if nodeID == "" {
			return nil, false
		}
		return []protocol.AiUiGuideAction{{Op: "toggle", NodeID: nodeID}}, true
	case "SCROLL_TO_FIND":
		if text == "" {
			return nil, false
		}
		return []protocol.AiUiGuideAction{{Op: "scroll_to_find", Target: text}}, true
	case "SCROLL_FORWARD", "SCROLL":
		return []protocol.AiUiGuideAction{{Op: "scroll", Direction: "forward"}}, true
	case "SCROLL_BACKWARD":
		return []protocol.AiUiGuideAction{{Op: "scroll", Direction: "backward"}}, true
	case "DONE":
		return []protocol.AiUiGuideAction{{Op: "done"}}, true
	case "FAIL", "GIVE_UP":
		return []protocol.AiUiGuideAction{{Op: "give_up"}}, true
	case "BACK":
		return []protocol.AiUiGuideAction{{Op: "back"}}, true
	default:
		return nil, false
	}
}

func normalizeNodeID(s string) string {
	s = strings.TrimSpace(s)
	if s == "" {
		return ""
	}
	if strings.HasPrefix(s, "n") {
		return s
	}
	var num int
	if _, err := fmt.Sscanf(s, "%d", &num); err == nil {
		return fmt.Sprintf("n%d", num)
	}
	return s
}

// ValidateAgainstSnapshot drops CLICK/TOGGLE whose target is not in the snapshot.
func ValidateAgainstSnapshot(actions []protocol.AiUiGuideAction, req protocol.AiUiGuideRequestPayload) []protocol.AiUiGuideAction {
	if len(actions) == 0 {
		return []protocol.AiUiGuideAction{{Op: "give_up"}}
	}
	ids := make(map[string]struct{}, len(req.Nodes))
	texts := make(map[string]string, len(req.Nodes))
	for _, n := range req.Nodes {
		nid := normalizeNodeID(n.ID)
		ids[nid] = struct{}{}
		ids[n.ID] = struct{}{}
		texts[nid] = n.Text
	}
	out := make([]protocol.AiUiGuideAction, 0, len(actions))
	for _, a := range actions {
		switch a.Op {
		case "tap", "toggle":
			nid := normalizeNodeID(a.NodeID)
			if nid == "" {
				continue
			}
			if _, ok := ids[nid]; !ok {
				if _, ok2 := ids[a.NodeID]; !ok2 {
					slog.Warn("ai action dropped — target not in snapshot", "op", a.Op, "nodeId", a.NodeID)
					continue
				}
			}
			if a.Op == "tap" {
				text := strings.TrimSpace(texts[nid])
				if text == "" || isDenyText(text) {
					slog.Warn("ai action dropped — tap target is empty or deny", "nodeId", nid, "text", text)
					continue
				}
			}
			a.NodeID = nid
			out = append(out, a)
		case "scroll_to_find", "tap_text":
			if strings.TrimSpace(a.Target) == "" {
				continue
			}
			out = append(out, a)
		default:
			out = append(out, a)
		}
	}
	if len(out) == 0 {
		return []protocol.AiUiGuideAction{{Op: "give_up"}}
	}
	return out
}

// coerceBatteryAction picks the visible grant control.
// A radio list ("بدون محدودیت") is tapped when that line is on screen.
// An Allow/Deny confirm (Samsung, AOSP) taps Allow. Scroll only when neither is visible.
func coerceBatteryAction(actions []protocol.AiUiGuideAction, req protocol.AiUiGuideRequestPayload) []protocol.AiUiGuideAction {
	phase := strings.ToUpper(strings.TrimSpace(req.Phase))
	if phase != "BATTERY" {
		return actions
	}
	if id := findUnrestrictedNode(req.Nodes); id != "" {
		return []protocol.AiUiGuideAction{{Op: "tap", NodeID: id}}
	}
	allowID := findExactNode(req.Nodes, batteryAllowButtons)
	denyID := findExactNode(req.Nodes, batteryDenyButtons)
	if allowID != "" && denyID != "" {
		return []protocol.AiUiGuideAction{{Op: "tap", NodeID: allowID}}
	}
	return actions
}

// coerceSmsDefaultAction forces: select NAME row, then confirm — never Cancel / section headers.
// Model often jumps to "تنظیم برای پیش‌فرض" or "برنامه پیامک پیش‌فرض" without selecting NAME.
func coerceSmsDefaultAction(actions []protocol.AiUiGuideAction, req protocol.AiUiGuideRequestPayload) []protocol.AiUiGuideAction {
	if strings.ToUpper(strings.TrimSpace(req.Phase)) != "SMS_DEFAULT" {
		return actions
	}
	name := strings.TrimSpace(req.AppDisplayName)
	if name == "" && len(req.AppLabels) > 0 {
		name = strings.TrimSpace(req.AppLabels[0])
	}
	nameID := findSmsNameNode(req.Nodes, name)
	confirmID := findSmsConfirmNode(req.Nodes)
	entryID := findSmsSettingsEntry(req.Nodes)

	if nameID != "" && confirmID != "" {
		// Role / picker dialog: select app then Set as default.
		return []protocol.AiUiGuideAction{
			{Op: "tap", NodeID: nameID},
			{Op: "tap", NodeID: confirmID},
		}
	}
	if nameID != "" {
		return []protocol.AiUiGuideAction{{Op: "tap", NodeID: nameID}}
	}
	if entryID != "" {
		return []protocol.AiUiGuideAction{{Op: "tap", NodeID: entryID}}
	}
	return dropSmsDenyActions(actions, req)
}

func findSmsNameNode(nodes []protocol.AiUiGuideNode, name string) string {
	name = normalizeUiLabel(name)
	if name == "" {
		return ""
	}
	var fallback string
	for _, n := range nodes {
		label := normalizeUiLabel(nodeLabel(n))
		if !strings.EqualFold(label, name) {
			continue
		}
		id := normalizeNodeID(n.ID)
		if n.Clickable || n.Checkable || n.NearApp {
			return id
		}
		if fallback == "" {
			fallback = id
		}
	}
	return fallback
}

func findSmsConfirmNode(nodes []protocol.AiUiGuideNode) string {
	phrases := []string{
		"تنظیم برای پیش‌فرض",
		"تنظیم براي پيش فرض",
		"تنظیم به‌عنوان پیش‌فرض",
		"set as default",
		"set default",
		"always",
	}
	if id := findExactNode(nodes, phrases); id != "" {
		return id
	}
	for _, n := range nodes {
		label := strings.ToLower(normalizeUiLabel(nodeLabel(n)))
		if label == "" || isDenyText(label) {
			continue
		}
		if (strings.Contains(label, "پیش‌فرض") || strings.Contains(label, "پيش فرض") ||
			strings.Contains(label, "پیش فرض") || strings.Contains(label, "default")) &&
			(strings.Contains(label, "تنظیم") || strings.Contains(label, "set")) {
			return normalizeNodeID(n.ID)
		}
	}
	return ""
}

func findSmsSettingsEntry(nodes []protocol.AiUiGuideNode) string {
	// Prefer the row that opens the SMS default picker (not the header while apps are listed).
	preferred := []string{
		"برنامه پیامک",
		"sms app",
		"default sms app",
		"messaging app",
	}
	if id := findExactNode(nodes, preferred); id != "" {
		return id
	}
	for _, n := range nodes {
		label := strings.ToLower(normalizeUiLabel(nodeLabel(n)))
		if label == "" || isDenyText(label) {
			continue
		}
		// "برنامه پیامک پیش‌فرض" is often a section title on the picker — only use if no better match.
		if label == "برنامه پیامک" || label == "sms" ||
			strings.HasPrefix(label, "برنامه پیامک") && !strings.Contains(label, "پیش‌فرض") {
			return normalizeNodeID(n.ID)
		}
	}
	for _, n := range nodes {
		label := strings.ToLower(normalizeUiLabel(nodeLabel(n)))
		if strings.Contains(label, "پیامک") && (n.Clickable || strings.Contains(label, "برنامه")) {
			return normalizeNodeID(n.ID)
		}
	}
	return ""
}

func dropSmsDenyActions(actions []protocol.AiUiGuideAction, req protocol.AiUiGuideRequestPayload) []protocol.AiUiGuideAction {
	if len(actions) == 0 {
		return actions
	}
	denyIDs := map[string]struct{}{}
	for _, n := range req.Nodes {
		if isDenyText(nodeLabel(n)) {
			denyIDs[normalizeNodeID(n.ID)] = struct{}{}
		}
	}
	out := make([]protocol.AiUiGuideAction, 0, len(actions))
	for _, a := range actions {
		if _, bad := denyIDs[normalizeNodeID(a.NodeID)]; bad {
			continue
		}
		out = append(out, a)
	}
	if len(out) == 0 {
		return actions
	}
	return out
}

func normalizeUiLabel(s string) string {
	s = strings.TrimSpace(s)
	for _, r := range []string{
		"\u200e", "\u200f", "\u202a", "\u202b", "\u202c", "\u202d", "\u202e", "\ufeff",
	} {
		s = strings.ReplaceAll(s, r, "")
	}
	return strings.TrimSpace(s)
}

var unrestrictedPhrases = []string{
	"بدون محدودیت",
	"unrestricted",
	"no restrictions",
	"no restriction",
	"don't optimize",
	"do not optimize",
	"not optimized",
	"بدون بهینه‌سازی",
	"بدون بهینه سازی",
	"بهینه‌سازی نکن",
	"بهینه سازی نکن",
}

var batteryAllowButtons = []string{
	"اجازه",
	"اجازه دادن",
	"allow",
	"مجاز",
	"yes",
	"بله",
}

var batteryDenyButtons = []string{
	"رد کردن",
	"deny",
	"don't allow",
	"do not allow",
	"dont allow",
	"اجازه ندادن",
	"مجاز نبودن",
	"no",
	"نه",
	"خیر",
	"لغو",
	"cancel",
}

func nodeLabel(n protocol.AiUiGuideNode) string {
	t := strings.TrimSpace(n.Text)
	if t == "" {
		t = strings.TrimSpace(n.Desc)
	}
	return t
}

func findExactNode(nodes []protocol.AiUiGuideNode, phrases []string) string {
	set := make(map[string]struct{}, len(phrases))
	for _, p := range phrases {
		set[strings.ToLower(strings.TrimSpace(p))] = struct{}{}
	}
	for _, n := range nodes {
		if _, ok := set[strings.ToLower(nodeLabel(n))]; ok {
			return normalizeNodeID(n.ID)
		}
	}
	return ""
}

func findUnrestrictedNode(nodes []protocol.AiUiGuideNode) string {
	for _, n := range nodes {
		label := strings.ToLower(nodeLabel(n))
		if label == "" || isDenyText(label) {
			continue
		}
		for _, p := range unrestrictedPhrases {
			if label == p || (strings.HasPrefix(label, p) && len([]rune(label)) <= len([]rune(p))+24) {
				return normalizeNodeID(n.ID)
			}
		}
	}
	return ""
}

// applyPhaseScroll fills S from the phase. The model does not emit Persian.
func applyPhaseScroll(actions []protocol.AiUiGuideAction, req protocol.AiUiGuideRequestPayload) []protocol.AiUiGuideAction {
	text := ""
	switch strings.ToUpper(strings.TrimSpace(req.Phase)) {
	case "BATTERY":
		text = "بدون محدودیت"
	case "OEM_AUTOSTART", "OEM", "SMS_DEFAULT":
		text = strings.TrimSpace(req.AppDisplayName)
	}
	if text == "" {
		return actions
	}
	for i := range actions {
		if actions[i].Op == "scroll_to_find" && strings.TrimSpace(actions[i].Target) == "" {
			actions[i].Target = text
		}
	}
	return actions
}

func shortCodeAction(token string) (protocol.AiUiGuideAction, bool) {
	token = strings.ToLower(strings.Trim(token, ".,;"))
	switch token {
	case "s":
		return protocol.AiUiGuideAction{Op: "scroll_to_find"}, true
	case "d":
		return protocol.AiUiGuideAction{Op: "done"}, true
	}
	if len(token) < 2 {
		return protocol.AiUiGuideAction{}, false
	}
	op := ""
	switch token[0] {
	case 'c':
		op = "tap"
	case 't':
		op = "toggle"
	default:
		return protocol.AiUiGuideAction{}, false
	}
	rest := strings.Trim(token[1:], "<>")
	if !isAllDigits(rest) {
		return protocol.AiUiGuideAction{}, false
	}
	return protocol.AiUiGuideAction{Op: op, NodeID: "n" + rest}, true
}

func isAllDigits(s string) bool {
	if s == "" {
		return false
	}
	for _, r := range s {
		if r < '0' || r > '9' {
			return false
		}
	}
	return true
}

// parseLineActions accepts: "C0", "T4", "S", "D", "tap n2", "give_up".
func parseLineActions(content string) ([]protocol.AiUiGuideAction, bool) {
	for _, line := range strings.Split(content, "\n") {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		lower := strings.ToLower(line)
		if strings.HasPrefix(lower, "/") { // /no_think etc.
			continue
		}
		if lower == "give_up" || lower == "give-up" {
			return []protocol.AiUiGuideAction{{Op: "give_up"}}, true
		}
		if lower == "app_toggle" || lower == "tap_app_toggle" || lower == "toggle_app" {
			return []protocol.AiUiGuideAction{{Op: "app_toggle"}}, true
		}
		if strings.HasPrefix(lower, "tap_text ") {
			target := strings.TrimSpace(line[len("tap_text "):])
			if target != "" {
				return []protocol.AiUiGuideAction{{Op: "tap_text", Target: target}}, true
			}
		}
		if strings.HasPrefix(lower, "tap text ") {
			target := strings.TrimSpace(line[len("tap text "):])
			if target != "" {
				return []protocol.AiUiGuideAction{{Op: "tap_text", Target: target}}, true
			}
		}
		lower = strings.ReplaceAll(lower, ":", " ")
		fields := strings.Fields(lower)
		if len(fields) >= 1 {
			if a, ok := shortCodeAction(fields[0]); ok && !(a.Op == "scroll_to_find" && len(fields) >= 2) {
				return []protocol.AiUiGuideAction{a}, true
			}
		}
		if len(fields) >= 1 && fields[0] == "scroll" {
			dir := "forward"
			if len(fields) >= 2 && fields[1] == "backward" {
				dir = "backward"
			}
			return []protocol.AiUiGuideAction{{Op: "scroll", Direction: dir}}, true
		}
		if len(fields) >= 2 && (fields[0] == "c" || fields[0] == "t" || fields[0] == "tap" || fields[0] == "click" || fields[0] == "toggle") {
			nodeID := strings.Trim(fields[1], "<>.,;")
			if nodeID != "" && !strings.HasPrefix(nodeID, "n") && fields[0] != "tap" {
				nodeID = "n" + nodeID
			}
			if nodeID != "" && !isKnownOp(nodeID) {
				op := "tap"
				if fields[0] == "t" || fields[0] == "toggle" {
					op = "toggle"
				}
				return []protocol.AiUiGuideAction{{Op: op, NodeID: nodeID}}, true
			}
		}
		if len(fields) >= 2 && fields[0] == "s" {
			target := strings.TrimSpace(line)
			if i := strings.IndexAny(target, " \t"); i >= 0 {
				target = strings.TrimSpace(target[i+1:])
			}
			if target != "" {
				return []protocol.AiUiGuideAction{{Op: "scroll_to_find", Target: target}}, true
			}
		}
		if len(fields) == 1 && (fields[0] == "d" || fields[0] == "done") {
			return []protocol.AiUiGuideAction{{Op: "done"}}, true
		}
		if len(fields) == 1 && strings.HasPrefix(fields[0], "n") && len(fields[0]) > 1 {
			// bare "n2"
			return []protocol.AiUiGuideAction{{Op: "tap", NodeID: fields[0]}}, true
		}
		if len(fields) == 1 {
			if _, err := strconv.Atoi(fields[0]); err == nil {
				return []protocol.AiUiGuideAction{{Op: "tap", NodeID: "n" + fields[0]}}, true
			}
		}
	}
	return nil, false
}

func finalizeActions(in []protocol.AiUiGuideAction) []protocol.AiUiGuideAction {
	out := make([]protocol.AiUiGuideAction, 0, len(in))
	for _, a := range in {
		op := strings.ToLower(strings.TrimSpace(a.Op))
		switch op {
		case "tap", "tap_text", "app_toggle", "toggle", "scroll", "scroll_to_find",
			"back", "done", "give_up":
			a.Op = op
			if op == "scroll" {
				dir := strings.ToLower(strings.TrimSpace(a.Direction))
				if dir != "backward" {
					dir = "forward"
				}
				a.Direction = dir
			}
			out = append(out, a)
		}
	}
	out = SanitizeActions(out)
	if len(out) == 0 {
		return []protocol.AiUiGuideAction{{Op: "give_up"}}
	}
	return out
}

// SanitizeActions keeps decide actions; drops back (closes OEM/settings too early).
func SanitizeActions(in []protocol.AiUiGuideAction) []protocol.AiUiGuideAction {
	if len(in) == 0 {
		return in
	}
	onlyGiveUp := true
	for _, a := range in {
		if a.Op != "give_up" {
			onlyGiveUp = false
			break
		}
	}
	if onlyGiveUp {
		return []protocol.AiUiGuideAction{{Op: "give_up"}}
	}
	out := make([]protocol.AiUiGuideAction, 0, len(in))
	taps := 0
	for _, a := range in {
		switch a.Op {
		case "give_up":
			continue
		case "done":
			// Goal already satisfied — keep as terminal success for client.
			out = append(out, a)
			return out
		case "back":
			continue
		case "tap", "tap_text", "app_toggle", "toggle", "scroll_to_find":
			taps++
			out = append(out, a)
			if taps >= 3 {
				return out
			}
		case "scroll":
			out = append(out, a)
		default:
			out = append(out, a)
		}
	}
	if len(out) == 0 {
		return []protocol.AiUiGuideAction{{Op: "give_up"}}
	}
	return out
}

func truncate(s string, n int) string {
	r := []rune(s)
	if len(r) <= n {
		return s
	}
	return string(r[:n])
}

func isDenyText(s string) bool {
	l := strings.ToLower(s)
	for _, w := range []string{
		"deny", "مجاز نبودن", "نمی‌پذیرم", "نمی پذیرم", "عدم اجازه", "don't allow", "dont allow",
		"do not allow", "not now", "رد کردن", "اجازه ندادن",
	} {
		if strings.Contains(l, w) {
			return true
		}
	}
	return false
}
