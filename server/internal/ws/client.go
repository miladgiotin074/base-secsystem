package ws

import (
	"context"
	"log/slog"
	"sync"
	"time"

	"github.com/coder/websocket"

	"github.com/base/realtime/internal/protocol"
)

const statusReplaced websocket.StatusCode = 4002

type Client struct {
	hub       *Hub
	conn      *websocket.Conn
	send      chan []byte
	stop      chan struct{}
	once      sync.Once
	mu        sync.Mutex
	userID    string
	deviceID  string
	connID    string
	sessionUser   string
	sessionDevice string
	ip        string
	heartbeat time.Duration
}

func (c *Client) setPeerIP(ip string) {
	c.mu.Lock()
	c.ip = ip
	c.mu.Unlock()
}

func (c *Client) clientIP() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.ip
}

func newClient(h *Hub, conn *websocket.Conn) *Client {
	return &Client{
		hub:       h,
		conn:      conn,
		send:      make(chan []byte, 64),
		stop:      make(chan struct{}),
		heartbeat: h.cfg.HeartbeatInterval,
	}
}

func (c *Client) setSession(userID, deviceID string) {
	c.mu.Lock()
	c.sessionUser = userID
	c.sessionDevice = deviceID
	c.mu.Unlock()
}

func (c *Client) session() (userID, deviceID string, ok bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.sessionUser == "" {
		return "", "", false
	}
	return c.sessionUser, c.sessionDevice, true
}

func (c *Client) setIdentity(userID, deviceID, connID string) {
	c.mu.Lock()
	c.userID = userID
	c.deviceID = deviceID
	c.connID = connID
	c.mu.Unlock()
}

func (c *Client) identity() (userID, deviceID, connID string, ok bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.userID == "" {
		return "", "", "", false
	}
	return c.userID, c.deviceID, c.connID, true
}

func (c *Client) enqueue(payload []byte) bool {
	select {
	case <-c.stop:
		return false
	default:
	}
	select {
	case <-c.stop:
		return false
	case c.send <- payload:
		return true
	default:
		go c.finish()
		return false
	}
}

func (c *Client) finish() {
	c.finishWith(websocket.StatusGoingAway, "bye")
}

func (c *Client) finishWith(code websocket.StatusCode, reason string) {
	c.once.Do(func() {
		close(c.stop)
		_ = c.conn.Close(code, reason)
		c.hub.onDisconnect(c)
	})
}

func (c *Client) readLoop(ctx context.Context) {
	defer c.finish()
	helloDeadline := time.Now().Add(10 * time.Second)
	for {
		if ctx.Err() != nil {
			return
		}
		select {
		case <-c.stop:
			return
		default:
		}
		timeout := c.hub.cfg.HeartbeatTimeout
		if _, _, _, ok := c.identity(); !ok {
			remain := time.Until(helloDeadline)
			if remain <= 0 {
				c.finishWith(websocket.StatusPolicyViolation, "hello timeout")
				return
			}
			timeout = remain
		}
		readCtx, cancel := context.WithTimeout(ctx, timeout)
		_, data, err := c.conn.Read(readCtx)
		cancel()
		if err != nil {
			return
		}
		plain, err := protocol.OpenFrame(c.hub.cfg.FrameKey, data)
		if err != nil {
			slog.Warn("frame rejected", "err", err)
			c.finishWith(websocket.StatusPolicyViolation, "bad message")
			return
		}
		if err := c.hub.handleFrame(c, plain); err != nil {
			slog.Warn("frame rejected", "err", err)
			c.finishWith(websocket.StatusPolicyViolation, "bad message")
			return
		}
	}
}

func (c *Client) writeLoop(ctx context.Context) {
	defer c.finish()
	ticker := time.NewTicker(c.heartbeat)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-c.stop:
			return
		case msg := <-c.send:
			if !c.write(ctx, msg) {
				return
			}
		case <-ticker.C:
			if _, _, _, ok := c.identity(); !ok {
				continue
			}
			body, err := protocol.Ping()
			if err != nil {
				slog.Warn("ping", "err", err)
				return
			}
			if !c.write(ctx, body) {
				return
			}
		}
	}
}

func (c *Client) write(ctx context.Context, payload []byte) bool {
	body, err := protocol.SealFrame(c.hub.cfg.FrameKey, payload)
	if err != nil {
		slog.Warn("seal frame", "err", err)
		return false
	}
	writeCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	if err := c.conn.Write(writeCtx, websocket.MessageText, body); err != nil {
		return false
	}
	return true
}
