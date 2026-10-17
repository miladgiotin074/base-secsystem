package protocol

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
)

const (
	FrameAlgAEAD = "aead"
	frameNonceN  = 12
	frameKeyN    = 32
)

// WireFrame is the on-wire UTF-8 JSON wrapper. The envelope JSON sits inside C (AES-256-GCM).
type WireFrame struct {
	V   int    `json:"v"`
	Alg string `json:"alg"`
	N   string `json:"n"`
	C   string `json:"c"`
}

func ParseFrameKey(raw string) ([]byte, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return nil, nil
	}
	if len(raw) == 64 {
		if key, err := hex.DecodeString(raw); err == nil && len(key) == frameKeyN {
			return key, nil
		}
	}
	for _, enc := range []*base64.Encoding{
		base64.StdEncoding,
		base64.RawStdEncoding,
		base64.URLEncoding,
		base64.RawURLEncoding,
	} {
		key, err := enc.DecodeString(raw)
		if err == nil && len(key) == frameKeyN {
			return key, nil
		}
	}
	return nil, fmt.Errorf("REALTIME_FRAME_KEY must be 32 bytes (base64 or hex)")
}

func SealFrame(key, plaintext []byte) ([]byte, error) {
	if len(key) == 0 {
		return plaintext, nil
	}
	if len(key) != frameKeyN {
		return nil, errors.New("bad frame key length")
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	nonce := make([]byte, frameNonceN)
	if _, err := rand.Read(nonce); err != nil {
		return nil, err
	}
	sealed := gcm.Seal(nil, nonce, plaintext, aeadExtra())
	wire := WireFrame{
		V:   Version,
		Alg: FrameAlgAEAD,
		N:   base64.RawURLEncoding.EncodeToString(nonce),
		C:   base64.RawURLEncoding.EncodeToString(sealed),
	}
	return json.Marshal(wire)
}

func OpenFrame(key, data []byte) ([]byte, error) {
	var wire WireFrame
	if err := json.Unmarshal(data, &wire); err != nil {
		if len(key) == 0 {
			return data, nil
		}
		return nil, errors.New("encrypted frame required")
	}
	if wire.Alg == FrameAlgAEAD {
		if len(key) == 0 {
			return nil, errors.New("frame key missing")
		}
		if wire.V != Version {
			return nil, errors.New("bad wire version")
		}
		nonce, err := decodeB64(wire.N)
		if err != nil || len(nonce) != frameNonceN {
			return nil, errors.New("bad nonce")
		}
		ciphertext, err := decodeB64(wire.C)
		if err != nil || len(ciphertext) == 0 {
			return nil, errors.New("bad ciphertext")
		}
		block, err := aes.NewCipher(key)
		if err != nil {
			return nil, err
		}
		gcm, err := cipher.NewGCM(block)
		if err != nil {
			return nil, err
		}
		plain, err := gcm.Open(nil, nonce, ciphertext, aeadExtra())
		if err != nil {
			return nil, errors.New("decrypt failed")
		}
		return plain, nil
	}
	if len(key) > 0 {
		return nil, errors.New("encrypted frame required")
	}
	return data, nil
}

func aeadExtra() []byte {
	return []byte("realtime-v1")
}

func decodeB64(raw string) ([]byte, error) {
	for _, enc := range []*base64.Encoding{
		base64.RawURLEncoding,
		base64.URLEncoding,
		base64.RawStdEncoding,
		base64.StdEncoding,
	} {
		out, err := enc.DecodeString(raw)
		if err == nil {
			return out, nil
		}
	}
	return nil, errors.New("bad base64")
}
