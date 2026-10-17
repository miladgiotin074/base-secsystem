package config

import (
	"crypto/rand"
	"fmt"
	"os"
	"strings"
	"time"

	"github.com/base/realtime/internal/protocol"
)

type Config struct {
	Addr              string
	MongoURI          string
	MongoDB           string
	Token             string
	FrameKey          []byte
	RedisURL          string
	FCMServerKey      string
	FCMWebhook        string
	InstanceID        string
	SessionTTL        time.Duration
	HeartbeatInterval time.Duration
	HeartbeatTimeout  time.Duration
	OfflineGrace      time.Duration
	StaleAfter        time.Duration
	Origins           []string
	TelegramBotToken  string
	AiURL             string
	AiModel           string
	AiKey             string
	AiPrompt          string
	AiTimeout         time.Duration
}

func Load() (Config, error) {
	host, _ := os.Hostname()
	if host == "" {
		host = "node"
	}
	var nonce [4]byte
	_, _ = rand.Read(nonce[:])
	cfg := Config{
		Addr:              getenv("REALTIME_ADDR", ":8080"),
		MongoURI:          getenv("REALTIME_MONGO_URI", "mongodb://localhost:27017"),
		MongoDB:           getenv("REALTIME_MONGO_DB", "realtime"),
		Token:             os.Getenv("REALTIME_TOKEN"),
		RedisURL:          os.Getenv("REALTIME_REDIS_URL"),
		FCMServerKey:      os.Getenv("FCM_SERVER_KEY"),
		FCMWebhook:        os.Getenv("FCM_WEBHOOK_URL"),
		InstanceID:        getenv("REALTIME_INSTANCE_ID", fmt.Sprintf("%s-%x", host, nonce)),
		SessionTTL:        7 * 24 * time.Hour,
		HeartbeatInterval: 25 * time.Second,
		HeartbeatTimeout:  70 * time.Second,
		OfflineGrace:      20 * time.Second,
		StaleAfter:        90 * time.Second,
		Origins:           splitList(getenv("REALTIME_ORIGINS", "localhost:3000,127.0.0.1:3000")),
		TelegramBotToken:  firstEnv("REALTIME_TELEGRAM_BOT_TOKEN", "TELEGRAM_BOT_TOKEN"),
		AiURL:             aiURLFromEnv(),
		AiModel:           getenv("REALTIME_AI_MODEL", "qwen2.5:3b"),
		AiKey:             strings.TrimSpace(os.Getenv("REALTIME_AI_KEY")),
		AiPrompt:          getenv("REALTIME_AI_PROMPT", "sms_v1"),
		AiTimeout:         25 * time.Second,
	}
	frameKey, err := protocol.ParseFrameKey(os.Getenv("REALTIME_FRAME_KEY"))
	if err != nil {
		return Config{}, err
	}
	cfg.FrameKey = frameKey
	if cfg.HeartbeatInterval, err = duration("REALTIME_HEARTBEAT_INTERVAL", cfg.HeartbeatInterval); err != nil {
		return Config{}, err
	}
	if cfg.HeartbeatTimeout, err = duration("REALTIME_HEARTBEAT_TIMEOUT", cfg.HeartbeatTimeout); err != nil {
		return Config{}, err
	}
	if cfg.OfflineGrace, err = duration("REALTIME_OFFLINE_GRACE", cfg.OfflineGrace); err != nil {
		return Config{}, err
	}
	if cfg.StaleAfter, err = duration("REALTIME_STALE_AFTER", cfg.StaleAfter); err != nil {
		return Config{}, err
	}
	if cfg.SessionTTL, err = duration("REALTIME_SESSION_TTL", cfg.SessionTTL); err != nil {
		return Config{}, err
	}
	if cfg.AiTimeout, err = duration("REALTIME_AI_TIMEOUT", cfg.AiTimeout); err != nil {
		return Config{}, err
	}
	if cfg.HeartbeatTimeout <= cfg.HeartbeatInterval {
		return Config{}, fmt.Errorf("heartbeat timeout must be longer than the interval")
	}
	return cfg, nil
}

func splitList(raw string) []string {
	parts := strings.Split(raw, ",")
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		p = strings.TrimSpace(p)
		if p != "" {
			out = append(out, p)
		}
	}
	return out
}

func getenv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

// aiURLFromEnv: unset → local Ollama; explicitly empty → AI disabled (give_up only).
func aiURLFromEnv() string {
	v, ok := os.LookupEnv("REALTIME_AI_URL")
	if !ok {
		return "http://127.0.0.1:11434/v1"
	}
	return strings.TrimSpace(v)
}

func firstEnv(keys ...string) string {
	for _, key := range keys {
		if v := strings.TrimSpace(os.Getenv(key)); v != "" {
			return v
		}
	}
	return ""
}

func duration(key string, fallback time.Duration) (time.Duration, error) {
	v := os.Getenv(key)
	if v == "" {
		return fallback, nil
	}
	d, err := time.ParseDuration(v)
	if err != nil {
		return 0, fmt.Errorf("%s: %w", key, err)
	}
	if d <= 0 {
		return 0, fmt.Errorf("%s must be positive", key)
	}
	return d, nil
}
