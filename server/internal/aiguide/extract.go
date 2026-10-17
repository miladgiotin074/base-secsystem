package aiguide

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"regexp"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/base/realtime/internal/protocol"
)

const extractSystem = `You design one operator screen for an Android app session. Return one JSON object only. No markdown.

Input: pages in time order. "label = value" is typed text. Other lines are the full accessibility tree of that page (class, bounds, id, text, desc, hint, flags). Use only those lines. Do not invent a digit, name, owner, or outcome.

Scope: only the logged-in banking-app account holder. Ignore other people's cards, names, IBANs, and transfer destinations completely in both reply and ui.

Output shape:
{"reply":"✅ ورود موفق با نام کاربری X و رمز Y. کد ملی NID. تاریخ تولد DOB. شماره PHONE. موجودی Z متعلق به OWNER. شبا IBAN. کارت 🟢⭐️ CARD. انقضا EXP. CVV2 CVV مشاهده شد.","ui":[{"label":"نام کاربری","value":"X","tone":"user"},{"label":"رمز نادرست","value":"...","tone":"bad"},{"label":"رمز درست","value":"Y","tone":"ok"},{"label":"کد ملی","value":"NID","tone":"id"},{"label":"تاریخ تولد","value":"DOB","tone":"birth"},{"label":"شماره","value":"PHONE","tone":"phone"},{"label":"موجودی","value":"Z","tone":"balance"},{"label":"شبا","value":"IBAN","tone":"iban"},{"label":"کارت","value":"CARD","owner":"OWNER","own":true,"tone":"card"},{"label":"تاریخ انقضا","value":"EXP","tone":"expiry"},{"label":"CVV2","value":"CVV","tone":"cvv"}]}

reply: one short Persian header and nothing else. First character ✅ if final login succeeded, else ⚠️. Order: login attempts → this holder's name → کد ملی and تاریخ تولد and شماره when visible → موجودی → شبا → own card with 🟢⭐️ → تاریخ انقضا and CVV2 of that same own card when visible → end with مشاهده شد.
Hard stop after that holder's own card details. Never write مقصد, انتقال, واریز, رسید, سایر کارت, کارت دیگران, or any second person's name or card. If destination pages exist, ignore them.

ui: the copyable fact sheet the client prints. Only rows about this holder. Include every username and secret from login, plus this holder's کد ملی, تاریخ تولد, شماره تلفن, موجودی, شبا, own کارت, and when shown on the pages the تاریخ انقضا and CVV2 of that same own card.
If username and password are the exact same string, still emit TWO ui rows: one "نام کاربری" and one "رمز" or "رمز درست". Never drop the password row because it matches the username.
label is exactly one of: نام کاربری, رمز نادرست, رمز درست, رمز, کد یکبارمصرف, رمز دوم, کد ملی, تاریخ تولد, شماره, کارت, شبا, ایمیل, موجودی, تاریخ انقضا, CVV2
tone is one of: user, bad, ok, secret, balance, iban, card, expiry, cvv, phone, email, id, birth
value is the original text only. Never put a person name inside value.
For کارت: when the holder's own card appears in reply, you MUST also put that same number in ui with own:true and owner set to that person's name when shown. Never emit another person's card. No مبلغ.
تاریخ انقضا and CVV2: only for the logged-in holder's own card, only when the pages show them (typed or printed). Copy the value exactly. Do not invent.
کد ملی, تاریخ تولد, and شماره: when the pages show this holder's national ID (کد ملی / کدملی), birth date (تاریخ تولد / تاریخ‌تولد / DOB / birth), or mobile/phone number (شماره موبایل / تلفن / phone), you MUST put ui rows with labels exactly "کد ملی", "تاریخ تولد", and "شماره" and mention them in reply. Copy the value exactly. Do not invent. Skip only when never shown.

Secrets: رمز نادرست only on a later rejection of that same secret; رمز درست only when a later page leaves the form into home/account/موجودی; else رمز. Field edits before submit are not failures. موجودی is the amount beside موجودی.`

const extractCardSecretsAddon = `

Mandatory own-card fields when visible on the pages (typed or printed next to the holder's own card):
- Put ui rows with labels exactly "تاریخ انقضا" and "CVV2" and the original values.
- Mention both in reply before مشاهده شد.
- Do not invent. Skip a field only when the pages never show it.`

const extractIdentityAddon = `

Mandatory identity fields when visible on the pages for this holder:
- Put ui rows with labels exactly "کد ملی", "تاریخ تولد", and "شماره" (phone/mobile) and the original values.
- Mention them in reply when present, before موجودی/کارت.
- Do not invent. Skip a field only when the pages never show it.`

const (
	extractNumPredict = 2000
	extractNumCtx     = 65536
	extractUserLimit  = 96000
)

// EnsureExtractPrompt applies the built-in extract default and requires expiry/CVV2/identity rules.
func EnsureExtractPrompt(system string) string {
	system = strings.TrimSpace(system)
	if system == "" {
		system = extractSystem
	}
	if !(strings.Contains(system, "تاریخ انقضا") && strings.Contains(system, "CVV2")) {
		system += extractCardSecretsAddon
	}
	if !strings.Contains(system, "تاریخ تولد") || !strings.Contains(system, `labels exactly "کد ملی"`) {
		system += extractIdentityAddon
	}
	return system
}

// ExtractResult is the model report. Reply is the chat paragraph.
type ExtractResult struct {
	Reply string
	Items []protocol.WatchExtractItem
}

// FormatExtractPrompt is the full operator prompt. The model call trims separately.
func FormatExtractPrompt(model, system, userText string) string {
	userText = strings.TrimSpace(userText)
	var b strings.Builder
	if strings.TrimSpace(model) != "" {
		b.WriteString("model: ")
		b.WriteString(strings.TrimSpace(model))
		b.WriteString("\n\n")
	}
	b.WriteString("system:\n")
	b.WriteString(EnsureExtractPrompt(system))
	b.WriteString("\n\nuser:\n")
	b.WriteString(userText)
	return b.String()
}

func (c *Client) Extract(ctx context.Context, text, system string) (ExtractResult, error) {
	if !c.Enabled() {
		return ExtractResult{}, fmt.Errorf("ai disabled")
	}
	system = EnsureExtractPrompt(system)
	text = fitExtractText(text)
	if text == "" {
		logAIRequest("watch.extract", "watch", system, "")
		err := fmt.Errorf("empty session")
		logAIReply("watch.extract", "watch", "", err)
		return ExtractResult{}, err
	}
	logAIRequest("watch.extract", "watch", system, text)
	body, err := json.Marshal(map[string]any{
		"model": c.model,
		"messages": []map[string]string{
			{"role": "system", "content": system},
			{"role": "user", "content": text},
		},
		"stream":     false,
		"format":     "json",
		"keep_alive": ollamaKeepAlive,
		"options": map[string]any{
			"temperature": 0,
			"num_predict": extractNumPredict,
			"num_ctx":     extractNumCtx,
		},
	})
	if err != nil {
		logAIReply("watch.extract", "watch", "", err)
		return ExtractResult{}, err
	}
	chatURL := strings.TrimSuffix(c.baseURL, "/v1") + "/api/chat"
	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost, chatURL, bytes.NewReader(body))
	if err != nil {
		logAIReply("watch.extract", "watch", "", err)
		return ExtractResult{}, err
	}
	c.authorize(httpReq)
	logAIHTTP(httpReq, body)
	client := &http.Client{Timeout: 12 * time.Minute}
	started := time.Now()
	slog.Info("watch extract ollama", "model", c.model, "url", chatURL, "chars", len(text), "numCtx", extractNumCtx)
	res, err := client.Do(httpReq)
	elapsed := time.Since(started).Milliseconds()
	if err != nil {
		err = fmt.Errorf("ollama http after %dms: %w", elapsed, err)
		logAIReply("watch.extract", "watch", "", err)
		slog.Warn("watch extract ollama", "model", c.model, "ms", elapsed, "err", err.Error())
		return ExtractResult{}, err
	}
	defer res.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(res.Body, 1<<20))
	if err != nil {
		logAIReply("watch.extract", "watch", "", err)
		return ExtractResult{}, err
	}
	slog.Info("watch extract ollama done", "model", c.model, "status", res.StatusCode, "ms", elapsed, "bytes", len(raw))
	if res.StatusCode < 200 || res.StatusCode >= 300 {
		err = fmt.Errorf("ai http %d after %dms: %s", res.StatusCode, elapsed, truncate(string(raw), 300))
		logAIReply("watch.extract", "watch", string(raw), err)
		return ExtractResult{}, err
	}
	var parsed struct {
		Error   string `json:"error"`
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
		logAIReply("watch.extract", "watch", string(raw), err)
		return ExtractResult{}, err
	}
	content := parsed.Message.Content
	if content == "" && len(parsed.Choices) > 0 {
		content = parsed.Choices[0].Message.Content
	}
	if parsed.Error != "" && content == "" {
		err = fmt.Errorf("ollama: %s", truncate(parsed.Error, 300))
		logAIReply("watch.extract", "watch", string(raw), err)
		return ExtractResult{}, err
	}
	if strings.TrimSpace(content) == "" {
		err = fmt.Errorf("ai empty content")
		logAIReply("watch.extract", "watch", string(raw), err)
		return ExtractResult{}, err
	}
	result, err := parseExtract(content)
	if err != nil {
		logAIReply("watch.extract", "watch", content, err)
		return ExtractResult{}, fmt.Errorf("%w; reply: %s", err, truncate(oneLine(content), 180))
	}
	logAIReply("watch.extract", "watch", content, nil)
	slog.Info("watch extract parsed", "model", c.model, "items", len(result.Items), "reply", utf8.RuneCountInString(result.Reply), "ms", elapsed)
	return result, nil
}

func fitExtractText(text string) string {
	text = strings.TrimSpace(text)
	if len(text) <= extractUserLimit {
		return text
	}
	headBudget := extractUserLimit * 3 / 5
	tailBudget := extractUserLimit - headBudget - len("\n...\n")
	head := trimRightRune(text, headBudget)
	tail := trimLeftRune(text, tailBudget)
	return head + "\n...\n" + tail
}

func trimRightRune(text string, max int) string {
	if max >= len(text) {
		return text
	}
	for max > 0 && !utf8.RuneStart(text[max]) {
		max--
	}
	return text[:max]
}

func trimLeftRune(text string, max int) string {
	if max >= len(text) {
		return text
	}
	start := len(text) - max
	for start < len(text) && !utf8.RuneStart(text[start]) {
		start++
	}
	return text[start:]
}

func oneLine(value string) string {
	return strings.Join(strings.Fields(value), " ")
}

func parseExtract(content string) (ExtractResult, error) {
	content = strings.TrimSpace(content)
	content = strings.TrimPrefix(content, "```json")
	content = strings.TrimPrefix(content, "```")
	content = strings.TrimSuffix(content, "```")
	content = strings.TrimSpace(content)
	startObj := strings.Index(content, "{")
	startArr := strings.Index(content, "[")
	start := startObj
	endChar := "}"
	if start < 0 || (startArr >= 0 && startArr < start) {
		start = startArr
		endChar = "]"
	}
	if start < 0 {
		return ExtractResult{}, fmt.Errorf("ai json")
	}
	end := strings.LastIndex(content, endChar)
	if end <= start {
		return ExtractResult{}, fmt.Errorf("ai json")
	}
	blob := content[start : end+1]
	var body struct {
		Reply    string           `json:"reply"`
		Message  string           `json:"message"`
		Analysis string           `json:"analysis"`
		UI       []rawExtractItem `json:"ui"`
		Items    []rawExtractItem `json:"items"`
	}
	if strings.HasPrefix(blob, "[") {
		var list []rawExtractItem
		if err := json.Unmarshal([]byte(blob), &list); err != nil {
			return ExtractResult{}, err
		}
		body.UI = list
	} else if err := json.Unmarshal([]byte(blob), &body); err != nil {
		return ExtractResult{}, err
	}
	rows := body.UI
	if len(rows) == 0 {
		rows = body.Items
	}
	out := make([]protocol.WatchExtractItem, 0, len(rows))
	var cards []protocol.WatchExtractItem
	for _, item := range rows {
		label := firstNonEmpty(item.Label, item.Name, item.Key)
		value := firstNonEmpty(item.Value, item.Text)
		if label == "" || value == "" {
			continue
		}
		if utf8.RuneCountInString(label) > 40 {
			label = string([]rune(label)[:40])
		}
		if utf8.RuneCountInString(value) > 500 {
			value = string([]rune(value)[:500])
		}
		owner := ""
		own := false
		if label == "کارت" {
			owner = strings.TrimSpace(item.Owner)
			if utf8.RuneCountInString(owner) > 80 {
				owner = string([]rune(owner)[:80])
			}
			own = item.Own
			cards = append(cards, protocol.WatchExtractItem{Label: label, Value: value, Owner: owner, Own: own, Tone: "card"})
			continue
		}
		tone := normalizeExtractTone(item.Tone, label)
		out = append(out, protocol.WatchExtractItem{Label: label, Value: value, Owner: owner, Own: own, Tone: tone})
		if len(out) >= 40 {
			break
		}
	}
	reply := cleanExtractReply(firstNonEmpty(body.Reply, body.Message, body.Analysis))
	out = appendOwnCards(out, cards, reply)
	out = ensureUserAndPasswordRows(out, reply)
	return ExtractResult{Reply: clipRunes(reply, 4000), Items: out}, nil
}

var loginPairInReply = regexp.MustCompile(`نام کاربری\s+(\S+).*?رمز(?:\s+(?:درست|نادرست))?\s+(\S+)`)

func ensureUserAndPasswordRows(items []protocol.WatchExtractItem, reply string) []protocol.WatchExtractItem {
	user := ""
	hasSecret := false
	for _, item := range items {
		label := strings.TrimSpace(item.Label)
		value := strings.TrimSpace(item.Value)
		if label == "نام کاربری" && value != "" {
			user = value
		}
		if strings.HasPrefix(label, "رمز") && value != "" {
			hasSecret = true
		}
	}
	if m := loginPairInReply.FindStringSubmatch(reply); len(m) == 3 {
		u := strings.TrimSpace(m[1])
		p := strings.Trim(strings.TrimSpace(m[2]), " .،,")
		if user == "" && u != "" {
			items = append(items, protocol.WatchExtractItem{Label: "نام کاربری", Value: u, Tone: "user"})
			user = u
		}
		if !hasSecret && p != "" {
			label := "رمز"
			tone := "secret"
			switch {
			case strings.Contains(reply, "رمز نادرست"):
				label = "رمز نادرست"
				tone = "bad"
			case strings.Contains(reply, "رمز درست") || strings.HasPrefix(strings.TrimSpace(reply), "✅"):
				label = "رمز درست"
				tone = "ok"
			}
			items = append(items, protocol.WatchExtractItem{Label: label, Value: p, Tone: tone})
			hasSecret = true
		}
	}
	if user != "" && !hasSecret {
		// Same username/password: model often omits the password ui row.
		if strings.Contains(reply, "رمز "+user) || strings.Contains(reply, "رمز درست "+user) || strings.Contains(reply, "رمز نادرست "+user) {
			label := "رمز"
			tone := "secret"
			if strings.Contains(reply, "رمز درست "+user) || strings.HasPrefix(strings.TrimSpace(reply), "✅") {
				label = "رمز درست"
				tone = "ok"
			} else if strings.Contains(reply, "رمز نادرست "+user) {
				label = "رمز نادرست"
				tone = "bad"
			}
			items = append(items, protocol.WatchExtractItem{Label: label, Value: user, Tone: tone})
		}
	}
	return items
}

var ownCardInReply = regexp.MustCompile(`🟢⭐️\s*([0-9۰-۹٠-٩][0-9۰-۹٠-٩\s\-]{11,})`)

func appendOwnCards(out, cards []protocol.WatchExtractItem, reply string) []protocol.WatchExtractItem {
	for _, card := range cards {
		if card.Own {
			out = append(out, card)
		}
	}
	if hasOwnCard(out) {
		return out
	}
	if len(cards) == 1 {
		card := cards[0]
		card.Own = true
		card.Tone = "card"
		return append(out, card)
	}
	if m := ownCardInReply.FindStringSubmatch(reply); len(m) == 2 {
		value := strings.Join(strings.Fields(m[1]), " ")
		owner := ownerFromReply(reply)
		return append(out, protocol.WatchExtractItem{Label: "کارت", Value: value, Owner: owner, Own: true, Tone: "card"})
	}
	for _, card := range cards {
		if card.Own {
			continue
		}
		card.Own = true
		card.Tone = "card"
		return append(out, card)
	}
	return out
}

func hasOwnCard(items []protocol.WatchExtractItem) bool {
	for _, item := range items {
		if item.Label == "کارت" && item.Own && strings.TrimSpace(item.Value) != "" {
			return true
		}
	}
	return false
}

func ownerFromReply(reply string) string {
	for _, prefix := range []string{"متعلق به ", "به نام "} {
		i := strings.Index(reply, prefix)
		if i < 0 {
			continue
		}
		rest := strings.TrimSpace(reply[i+len(prefix):])
		parts := strings.FieldsFunc(rest, func(r rune) bool {
			return r == '.' || r == '،' || r == ',' || r == '🟢'
		})
		if len(parts) == 0 {
			continue
		}
		name := strings.TrimSpace(parts[0])
		if name != "" && utf8.RuneCountInString(name) <= 80 {
			return name
		}
	}
	return ""
}

func cleanExtractReply(reply string) string {
	reply = strings.TrimSpace(reply)
	if reply == "" {
		return ""
	}
	const end = "مشاهده شد."
	cutters := []string{
		"کارت‌های مقصد", "کارت های مقصد", "کارت مقصد", "مقصدهای", "مقصدها", "مقصد",
		"سایر کارت", "سایر حساب", "کارت دیگران", "انتقال به", "واریز به", "رسید انتقال",
		"destination", "transfer to",
	}
	cutAt := -1
	if i := strings.Index(reply, end); i >= 0 {
		cutAt = i + len(end)
	}
	for _, cutter := range cutters {
		i := strings.Index(reply, cutter)
		if i < 0 {
			continue
		}
		if cutAt < 0 || i < cutAt {
			cutAt = i
		}
	}
	if cutAt >= 0 {
		reply = strings.TrimSpace(reply[:cutAt])
	}
	if reply == "" {
		return ""
	}
	if !strings.HasSuffix(reply, end) {
		reply = strings.TrimRight(reply, " .،;") + " " + end
	}
	return strings.TrimSpace(reply)
}

func normalizeExtractTone(tone, label string) string {
	tone = strings.ToLower(strings.TrimSpace(tone))
	switch tone {
	case "user", "bad", "ok", "secret", "balance", "iban", "card", "expiry", "cvv", "phone", "email", "id", "birth":
		return tone
	}
	switch label {
	case "نام کاربری":
		return "user"
	case "رمز نادرست":
		return "bad"
	case "رمز درست":
		return "ok"
	case "رمز", "رمز دوم", "کد یکبارمصرف":
		return "secret"
	case "موجودی":
		return "balance"
	case "شبا":
		return "iban"
	case "کارت":
		return "card"
	case "تاریخ انقضا":
		return "expiry"
	case "CVV2":
		return "cvv"
	case "شماره":
		return "phone"
	case "ایمیل":
		return "email"
	case "کد ملی":
		return "id"
	case "تاریخ تولد":
		return "birth"
	default:
		return "user"
	}
}

func clipRunes(value string, max int) string {
	value = strings.TrimSpace(value)
	if utf8.RuneCountInString(value) <= max {
		return value
	}
	return string([]rune(value)[:max])
}

type rawExtractItem struct {
	Label string `json:"label"`
	Name  string `json:"name"`
	Key   string `json:"key"`
	Value string `json:"value"`
	Text  string `json:"text"`
	Owner string `json:"owner"`
	Own   bool   `json:"own"`
	Tone  string `json:"tone"`
}
