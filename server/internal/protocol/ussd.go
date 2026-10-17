package protocol

import (
	"strings"
	"unicode"
)

const ussdMaxLen = 160

func NormalizeUssd(raw string) (string, bool) {
	var b strings.Builder
	b.Grow(len(raw))
	for _, r := range strings.TrimSpace(raw) {
		switch {
		case r >= '۰' && r <= '۹':
			b.WriteRune('0' + (r - '۰'))
		case r >= '٠' && r <= '٩':
			b.WriteRune('0' + (r - '٠'))
		case r == ' ' || r == '\n' || r == '\t':
		case unicode.Is(unicode.Cf, r) || r == '\u200c' || r == '\u200d' || r == '\u061c':
		default:
			b.WriteRune(r)
		}
	}
	code := b.String()
	if len(code) < 2 || len(code) > ussdMaxLen {
		return "", false
	}
	for _, r := range code {
		if (r < '0' || r > '9') && r != '*' && r != '#' {
			return "", false
		}
	}
	if !strings.ContainsAny(code, "*#") {
		return "", false
	}
	if strings.HasPrefix(code, "*") && !strings.Contains(code, "#") {
		code += "#"
	}
	return code, true
}

func NoServiceLabel(label string) bool {
	value := strings.ToLower(strings.TrimSpace(label))
	value = strings.ReplaceAll(value, "\u200c", "")
	if value == "" {
		return false
	}
	for _, phrase := range []string{
		"بدون سرویس",
		"بدون خدمات",
		"no service",
		"no network",
		"emergency calls only",
		"فقط تماس اضطراری",
		"خارج از دسترس",
		"not registered",
	} {
		if strings.Contains(value, phrase) {
			return true
		}
	}
	return false
}

// SimUssdBlock is empty when this slot may dial. An empty SIM list means the
// device has not reported slots yet, so the phone decides.
func SimUssdBlock(sims []SimSlot, slot int) string {
	if len(sims) == 0 {
		return ""
	}
	for _, sim := range sims {
		if sim.Slot != slot {
			continue
		}
		switch sim.State {
		case "on":
			if NoServiceLabel(sim.Label) {
				return "no service"
			}
			return ""
		case "noservice":
			return "no service"
		case "empty":
			return "sim empty"
		default:
			return "sim off"
		}
	}
	return "sim off"
}
