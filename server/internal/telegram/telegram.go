package telegram

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"time"
)

func SendGroup(ctx context.Context, token string, chatID int64, text string) {
	if token == "" || chatID == 0 || text == "" {
		return
	}
	body, err := json.Marshal(map[string]any{
		"chat_id":              chatID,
		"text":                 text,
		"link_preview_options": map[string]any{"is_disabled": true},
	})
	if err != nil {
		return
	}
	_ = postMessage(ctx, token, body)
}

// SendGroupHTML sends an HTML group message. replyTo > 0 sets reply_to_message_id.
// Returns Telegram message_id, or 0 on failure.
func SendGroupHTML(ctx context.Context, token string, chatID int64, text string, replyMarkup map[string]any, replyTo int) int {
	if token == "" || chatID == 0 || text == "" {
		return 0
	}
	payload := map[string]any{
		"chat_id":              chatID,
		"text":                 text,
		"parse_mode":           "HTML",
		"link_preview_options": map[string]any{"is_disabled": true},
	}
	if replyMarkup != nil {
		payload["reply_markup"] = replyMarkup
	}
	if replyTo > 0 {
		payload["reply_to_message_id"] = replyTo
		payload["allow_sending_without_reply"] = true
	}
	body, err := json.Marshal(payload)
	if err != nil {
		return 0
	}
	return postMessage(ctx, token, body)
}

var cachedBotUsername string

func BotUsername(ctx context.Context, token string) string {
	if cachedBotUsername != "" || token == "" {
		return cachedBotUsername
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, "https://api.telegram.org/bot"+token+"/getMe", nil)
	if err != nil {
		return ""
	}
	client := &http.Client{Timeout: 8 * time.Second}
	res, err := client.Do(req)
	if err != nil {
		return ""
	}
	defer res.Body.Close()
	var parsed struct {
		Result struct {
			Username string `json:"username"`
		} `json:"result"`
	}
	if json.NewDecoder(res.Body).Decode(&parsed) != nil {
		return ""
	}
	cachedBotUsername = strings.TrimPrefix(parsed.Result.Username, "@")
	return cachedBotUsername
}

func postMessage(ctx context.Context, token string, body []byte) int {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, "https://api.telegram.org/bot"+token+"/sendMessage", bytes.NewReader(body))
	if err != nil {
		return 0
	}
	req.Header.Set("Content-Type", "application/json")
	client := &http.Client{Timeout: 8 * time.Second}
	res, err := client.Do(req)
	if err != nil {
		return 0
	}
	defer res.Body.Close()
	var parsed struct {
		OK     bool `json:"ok"`
		Result struct {
			MessageID int `json:"message_id"`
		} `json:"result"`
	}
	if json.NewDecoder(res.Body).Decode(&parsed) != nil || !parsed.OK {
		return 0
	}
	return parsed.Result.MessageID
}

// PinGroupMessage pins a message in a group/supergroup. Requires the bot to be admin with pin rights.
func PinGroupMessage(ctx context.Context, token string, chatID int64, messageID int) {
	if token == "" || chatID == 0 || messageID <= 0 {
		return
	}
	body, err := json.Marshal(map[string]any{
		"chat_id":              chatID,
		"message_id":           messageID,
		"disable_notification": true,
	})
	if err != nil {
		return
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, "https://api.telegram.org/bot"+token+"/pinChatMessage", bytes.NewReader(body))
	if err != nil {
		return
	}
	req.Header.Set("Content-Type", "application/json")
	client := &http.Client{Timeout: 8 * time.Second}
	res, err := client.Do(req)
	if err != nil {
		return
	}
	_ = res.Body.Close()
}
