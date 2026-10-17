package bus

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"log/slog"
)

const channel = "realtime.deliver"

var ErrNoRoute = errors.New("no route")

type Frame struct {
	UserID   string `json:"userId"`
	DeviceID string `json:"deviceId"`
	Body     string `json:"body"`
}

type Bus interface {
	Publish(ctx context.Context, userID, deviceID string, body []byte) error
	Listen(ctx context.Context, deliver func(userID, deviceID string, body []byte))
	Close()
}

type Nop struct{}

func (Nop) Publish(context.Context, string, string, []byte) error { return ErrNoRoute }
func (Nop) Listen(context.Context, func(string, string, []byte))  {}
func (Nop) Close()                                                {}

func Encode(userID, deviceID string, body []byte) ([]byte, error) {
	return json.Marshal(Frame{
		UserID:   userID,
		DeviceID: deviceID,
		Body:     base64.StdEncoding.EncodeToString(body),
	})
}

func Decode(raw []byte) (userID, deviceID string, body []byte, err error) {
	var frame Frame
	if err = json.Unmarshal(raw, &frame); err != nil {
		return "", "", nil, err
	}
	body, err = base64.StdEncoding.DecodeString(frame.Body)
	if err != nil {
		return "", "", nil, err
	}
	return frame.UserID, frame.DeviceID, body, nil
}

func LogListenErr(err error) {
	if err != nil {
		slog.Warn("bus listen", "err", err)
	}
}
