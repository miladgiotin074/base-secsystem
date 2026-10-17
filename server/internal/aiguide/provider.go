package aiguide

import (
	"context"

	"github.com/base/realtime/internal/protocol"
)

// DecisionProvider chooses the next UI action for auto-grant.
// RemoteLlmProvider is the only implementation for now (Ollama).
type DecisionProvider interface {
	Enabled() bool
	Decide(ctx context.Context, req protocol.AiUiGuideRequestPayload) (GuideResult, error)
	Model() string
}

// RemoteLlmProvider calls Ollama via Client.
type RemoteLlmProvider struct {
	client *Client
}

func NewRemoteLlmProvider(c *Client) *RemoteLlmProvider {
	if c == nil {
		return nil
	}
	return &RemoteLlmProvider{client: c}
}

func (p *RemoteLlmProvider) Enabled() bool {
	return p != nil && p.client != nil && p.client.Enabled()
}

func (p *RemoteLlmProvider) Model() string {
	if p == nil || p.client == nil {
		return ""
	}
	return p.client.Model()
}

func (p *RemoteLlmProvider) Decide(ctx context.Context, req protocol.AiUiGuideRequestPayload) (GuideResult, error) {
	if !p.Enabled() {
		return GuideResult{Actions: []protocol.AiUiGuideAction{{Op: "give_up"}}}, nil
	}
	return p.client.Guide(ctx, req, nil)
}

// Ensure Client satisfies DecisionProvider when wrapped.
var _ DecisionProvider = (*RemoteLlmProvider)(nil)
