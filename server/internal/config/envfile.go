package config

import (
	"os"
	"path/filepath"
	"strings"
)

// LoadDotEnv reads .env from the working directory (or server/.env).
func LoadDotEnv() {
	candidates := []string{".env", filepath.Join("server", ".env")}
	if exe, err := os.Executable(); err == nil {
		dir := filepath.Dir(exe)
		candidates = append(candidates,
			filepath.Join(dir, ".env"),
			filepath.Join(dir, "server", ".env"),
		)
	}
	var raw []byte
	for _, path := range candidates {
		b, err := os.ReadFile(path)
		if err == nil {
			raw = b
			break
		}
	}
	if raw == nil {
		return
	}
	for _, line := range strings.Split(string(raw), "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		key, value, ok := strings.Cut(line, "=")
		if !ok {
			continue
		}
		key = strings.TrimSpace(key)
		value = strings.TrimSpace(value)
		if len(value) >= 2 {
			if (value[0] == '"' && value[len(value)-1] == '"') || (value[0] == '\'' && value[len(value)-1] == '\'') {
				value = value[1 : len(value)-1]
			}
		}
		if key != "" && os.Getenv(key) == "" {
			_ = os.Setenv(key, value)
		}
	}
}
