package aiguide

import (
	"sync"
	"time"

	"github.com/base/realtime/internal/id"
	"github.com/base/realtime/internal/protocol"
)

const maxTranscripts = 80

// Transcript is one server↔Ollama (or cache) exchange for the debug UI.
type Transcript struct {
	ID        string                     `json:"id"`
	CreatedAt time.Time                  `json:"createdAt"`
	RequestID string                     `json:"requestId,omitempty"`
	UserID    string                     `json:"userId,omitempty"`
	DeviceID  string                     `json:"deviceId,omitempty"`
	Phase     string                     `json:"phase,omitempty"`
	TapMode   string                     `json:"tapMode,omitempty"`
	TargetPkg string                     `json:"targetPkg,omitempty"`
	Source    string                     `json:"source,omitempty"` // ai | cache | give_up
	Model     string                     `json:"model,omitempty"`
	System    string                     `json:"system,omitempty"`
	User      string                     `json:"user,omitempty"`
	Assistant string                     `json:"assistant,omitempty"`
	Actions   []protocol.AiUiGuideAction `json:"actions,omitempty"`
	OllamaMs  int64                      `json:"ollamaMs,omitempty"`
	CacheMs   int64                      `json:"cacheMs,omitempty"`
	TotalMs   int64                      `json:"totalMs,omitempty"`
	Error     string                     `json:"error,omitempty"`
}

type Log struct {
	mu   sync.RWMutex
	items []Transcript
}

var DefaultLog = &Log{}

func (l *Log) Add(t Transcript) {
	if t.ID == "" {
		t.ID = id.New()
	}
	if t.CreatedAt.IsZero() {
		t.CreatedAt = time.Now()
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	l.items = append(l.items, t)
	if len(l.items) > maxTranscripts {
		l.items = l.items[len(l.items)-maxTranscripts:]
	}
}

func (l *Log) List() []Transcript {
	l.mu.RLock()
	defer l.mu.RUnlock()
	out := make([]Transcript, len(l.items))
	copy(out, l.items)
	// newest first
	for i, j := 0, len(out)-1; i < j; i, j = i+1, j-1 {
		out[i], out[j] = out[j], out[i]
	}
	return out
}

func (l *Log) Clear() {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.items = nil
}

// SystemPrompt returns the default catalog prompt (for UI fallback).
func SystemPrompt() string {
	_, text := ResolvePrompt(defaultPromptID)
	return text
}
