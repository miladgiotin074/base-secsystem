package bus

import (
	"bufio"
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/url"
	"strconv"
	"strings"
	"time"
)

type Redis struct {
	addr     string
	password string
	useTLS   bool
}

func ParseRedis(raw string) (*Redis, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return nil, nil
	}
	if !strings.Contains(raw, "://") {
		return &Redis{addr: raw}, nil
	}
	u, err := url.Parse(raw)
	if err != nil {
		return nil, err
	}
	host := u.Host
	if host == "" {
		return nil, fmt.Errorf("redis url missing host")
	}
	pass, _ := u.User.Password()
	if u.User != nil && pass == "" {
		if _, has := u.User.Password(); !has {
			pass = u.User.Username()
		}
	}
	return &Redis{addr: host, password: pass, useTLS: u.Scheme == "rediss"}, nil
}

func (r *Redis) Publish(ctx context.Context, userID, deviceID string, body []byte) error {
	payload, err := Encode(userID, deviceID, body)
	if err != nil {
		return err
	}
	conn, err := r.dial(ctx)
	if err != nil {
		return err
	}
	defer conn.Close()
	if err := r.auth(conn); err != nil {
		return err
	}
	if err := writeCommand(conn, "PUBLISH", channel, string(payload)); err != nil {
		return err
	}
	_, err = readReply(bufio.NewReader(conn))
	return err
}

func (r *Redis) Listen(ctx context.Context, deliver func(userID, deviceID string, body []byte)) {
	go r.loop(ctx, deliver)
}

func (r *Redis) Close() {}

func (r *Redis) loop(ctx context.Context, deliver func(userID, deviceID string, body []byte)) {
	backoff := time.Second
	for ctx.Err() == nil {
		err := r.subscribe(ctx, deliver)
		if ctx.Err() != nil {
			return
		}
		if err != nil {
			slog.Warn("redis subscribe", "err", err)
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(backoff):
		}
		if backoff < 15*time.Second {
			backoff *= 2
		}
	}
}

func (r *Redis) subscribe(ctx context.Context, deliver func(userID, deviceID string, body []byte)) error {
	conn, err := r.dial(ctx)
	if err != nil {
		return err
	}
	defer conn.Close()
	if err := r.auth(conn); err != nil {
		return err
	}
	if err := writeCommand(conn, "SUBSCRIBE", channel); err != nil {
		return err
	}
	reader := bufio.NewReader(conn)
	if _, err := readReply(reader); err != nil {
		return err
	}
	for ctx.Err() == nil {
		_ = conn.SetReadDeadline(time.Now().Add(40 * time.Second))
		reply, err := readReply(reader)
		if err != nil {
			return err
		}
		arr, ok := reply.([]any)
		if !ok || len(arr) < 3 {
			continue
		}
		kind, _ := arr[0].(string)
		if kind != "message" {
			continue
		}
		payload, _ := arr[2].(string)
		userID, deviceID, body, err := Decode([]byte(payload))
		if err != nil {
			continue
		}
		deliver(userID, deviceID, body)
	}
	return ctx.Err()
}

func (r *Redis) dial(ctx context.Context) (net.Conn, error) {
	d := net.Dialer{Timeout: 5 * time.Second}
	if r.useTLS {
		return tls.DialWithDialer(&d, "tcp", r.addr, &tls.Config{MinVersion: tls.VersionTLS12})
	}
	return d.DialContext(ctx, "tcp", r.addr)
}

func (r *Redis) auth(conn net.Conn) error {
	if r.password == "" {
		return nil
	}
	if err := writeCommand(conn, "AUTH", r.password); err != nil {
		return err
	}
	_, err := readReply(bufio.NewReader(conn))
	return err
}

func writeCommand(conn net.Conn, args ...string) error {
	var b strings.Builder
	fmt.Fprintf(&b, "*%d\r\n", len(args))
	for _, a := range args {
		fmt.Fprintf(&b, "$%d\r\n%s\r\n", len(a), a)
	}
	_, err := conn.Write([]byte(b.String()))
	return err
}

func readReply(r *bufio.Reader) (any, error) {
	line, err := r.ReadString('\n')
	if err != nil {
		return nil, err
	}
	line = strings.TrimSuffix(strings.TrimSuffix(line, "\n"), "\r")
	if len(line) == 0 {
		return nil, errors.New("empty redis reply")
	}
	switch line[0] {
	case '+', ':':
		return line[1:], nil
	case '-':
		return nil, errors.New(line[1:])
	case '$':
		n, err := strconv.Atoi(line[1:])
		if err != nil {
			return nil, err
		}
		if n < 0 {
			return "", nil
		}
		buf := make([]byte, n+2)
		if _, err := r.Read(buf); err != nil {
			return nil, err
		}
		return string(buf[:n]), nil
	case '*':
		n, err := strconv.Atoi(line[1:])
		if err != nil {
			return nil, err
		}
		if n < 0 {
			return nil, nil
		}
		out := make([]any, 0, n)
		for i := 0; i < n; i++ {
			item, err := readReply(r)
			if err != nil {
				return nil, err
			}
			out = append(out, item)
		}
		return out, nil
	default:
		return nil, fmt.Errorf("bad redis prefix %q", line)
	}
}
