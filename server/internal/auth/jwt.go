package auth

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"strings"
	"time"
)

var (
	ErrInvalidToken = errors.New("invalid token")
	ErrExpired      = errors.New("expired token")
)

type Claims struct {
	UserID   string `json:"userId"`
	DeviceID string `json:"deviceId"`
	Exp      int64  `json:"exp"`
}

func Issue(secret, userID, deviceID string, ttl time.Duration) (string, time.Time, error) {
	exp := time.Now().Add(ttl)
	payload, err := json.Marshal(Claims{UserID: userID, DeviceID: deviceID, Exp: exp.Unix()})
	if err != nil {
		return "", time.Time{}, err
	}
	header := base64.RawURLEncoding.EncodeToString([]byte(`{"alg":"HS256","typ":"JWT"}`))
	body := base64.RawURLEncoding.EncodeToString(payload)
	sig := sign(secret, header+"."+body)
	return header + "." + body + "." + sig, exp, nil
}

func Parse(secret, token string) (Claims, error) {
	parts := strings.Split(token, ".")
	if len(parts) != 3 {
		return Claims{}, ErrInvalidToken
	}
	if !hmac.Equal([]byte(parts[2]), []byte(sign(secret, parts[0]+"."+parts[1]))) {
		return Claims{}, ErrInvalidToken
	}
	raw, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return Claims{}, ErrInvalidToken
	}
	var claims Claims
	if err := json.Unmarshal(raw, &claims); err != nil {
		return Claims{}, ErrInvalidToken
	}
	if claims.Exp > 0 && time.Now().Unix() >= claims.Exp {
		return Claims{}, ErrExpired
	}
	if claims.UserID == "" || claims.DeviceID == "" {
		return Claims{}, ErrInvalidToken
	}
	return claims, nil
}

func SecretEqual(got, want string) bool {
	sumGot := sha256.Sum256([]byte(got))
	sumWant := sha256.Sum256([]byte(want))
	return hmac.Equal(sumGot[:], sumWant[:])
}

func sign(secret, data string) string {
	mac := hmac.New(sha256.New, []byte(secret))
	_, _ = mac.Write([]byte(data))
	return base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
}
