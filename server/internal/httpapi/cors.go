package httpapi

import (
	"net/http"
	"net/url"
	"strings"
)

func CORS(origins []string, next http.Handler) http.Handler {
	allowed := make(map[string]struct{}, len(origins))
	for _, origin := range origins {
		allowed[strings.ToLower(origin)] = struct{}{}
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		origin := r.Header.Get("Origin")
		if host := originHost(origin); host != "" {
			if _, ok := allowed[strings.ToLower(host)]; ok {
				w.Header().Set("Access-Control-Allow-Origin", origin)
				w.Header().Set("Vary", "Origin")
				w.Header().Set("Access-Control-Allow-Headers", "Authorization, Content-Type")
				w.Header().Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
			}
		}
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func originHost(origin string) string {
	if origin == "" {
		return ""
	}
	u, err := url.Parse(origin)
	if err != nil {
		return ""
	}
	return u.Host
}
