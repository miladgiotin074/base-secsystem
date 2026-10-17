package httpapi

import (
	"net/http"
	"path"
	"strings"

	"github.com/base/realtime/internal/store"
)

func clientContentType(name string) string {
	switch strings.ToLower(path.Ext(name)) {
	case ".html", ".htm":
		return "text/html; charset=utf-8"
	case ".css":
		return "text/css; charset=utf-8"
	case ".js":
		return "text/javascript; charset=utf-8"
	case ".png":
		return "image/png"
	case ".jpg", ".jpeg":
		return "image/jpeg"
	case ".webp":
		return "image/webp"
	case ".gif":
		return "image/gif"
	case ".svg":
		return "image/svg+xml"
	case ".woff2":
		return "font/woff2"
	case ".woff":
		return "font/woff"
	case ".ttf":
		return "font/ttf"
	case ".otf":
		return "font/otf"
	default:
		return "application/octet-stream"
	}
}

func writeClientBytes(w http.ResponseWriter, name string, body []byte, cache string) {
	w.Header().Set("Content-Type", clientContentType(name))
	w.Header().Set("Cache-Control", cache)
	_, _ = w.Write(body)
}

func lookupClientCore(row store.ClientTheme, file string) ([]byte, bool) {
	switch file {
	case "index.html", "index.htm":
		return row.HTML, true
	case "styles.css", "style.css":
		return row.CSS, true
	case "app.js", "main.js":
		return row.JS, true
	default:
		return nil, false
	}
}
