package aiguide

import "strings"

// Fixed system text only. The screen (app= and numbered lines) is the user
// message after this, so the system prefix stays identical for each phase.
const allowCodePrompt = `Reply with one code only, nothing else.
C<N> where N = the line "مجاز" or "Allow" (exact match; never "مجاز نبودن", Deny).
Example: lines "0 مجاز / 1 مجاز نبودن" -> C0`

var promptCatalog = map[string]string{
	"sms_v1": allowCodePrompt,

	"battery": `Reply with one code only, nothing else.
C<N> if a line is exactly "بدون محدودیت", "Unrestricted" or "No restrictions".
Else C<N> if a line is exactly "اجازه", "Allow" or "مجاز" and another line is "رد کردن" or "Deny". Never tap the deny line.
S only if none of those lines exist.
Dialog: "0 رد کردن / 1 اجازه" -> C1
List: "0 بدون محدودیت off / 1 بهینه‌ساز" -> C0`,

	"alarm": allowCodePrompt,

	"oem": `Reply with one code only, nothing else. NAME is the app= value.
S if no line contains NAME.
T<N> if a line contains NAME and its switch is off (N = the switch line).
D if the switch next to NAME is on.
Example: app=X, lines "0 بازگشت / 1 راه‌اندازی خودکار" -> S`,

	"general": allowCodePrompt,

	"device_admin": `Reply with one code only, nothing else. This is the system "activate device admin" screen. NAME is the app= value.
S if the activate button is missing or disabled (scroll the policy text to the bottom first).
C<N> if line N is the activate button: Activate, Activate this device admin app, Enable, Allow, فعال‌سازی, فعال کردن, فعال کنید, 激活, 启用, 활성화.
Never tap Cancel, Deny, لغو, انصراف, 取消, or a back arrow.
D only if the screen already confirms the admin is on.
Example: "0 لغو / 1 فعال‌سازی" -> C1`,

	"sms_default": `Reply with one code only, nothing else. NAME is the app= value (SMS app to set as default).
S if NAME is not on screen (scroll the list).
C<N> if line N is exactly NAME (select that SMS app / radio row).
C<N> if NAME is already selected and line N is the confirm button (e.g. "تنظیم برای پیش‌فرض", "Set as default", "OK", "Yes").
C<N> if line N opens SMS / messaging default-app settings and NAME is not yet visible.
Never tap Cancel/لغو/Deny.
D if NAME is already the active default SMS app and no further tap is needed.
Example: app=Messages, lines "0 Phone / 1 Messages / 2 Set as default" -> C1 then next turn C2`,
}

const defaultPromptID = "sms_v1"

func PromptForPhase(phase string) (id, text string) {
	switch strings.ToUpper(strings.TrimSpace(phase)) {
	case "RUNTIME":
		return "sms_v1", promptCatalog["sms_v1"]
	case "BATTERY":
		return "battery", promptCatalog["battery"]
	case "EXACT_ALARM":
		return "alarm", promptCatalog["alarm"]
	case "OEM_AUTOSTART", "OEM":
		return "oem", promptCatalog["oem"]
	case "SMS_DEFAULT":
		return "sms_default", promptCatalog["sms_default"]
	case "DEVICE_ADMIN":
		return "device_admin", promptCatalog["device_admin"]
	default:
		return "general", promptCatalog["general"]
	}
}

func ResolvePrompt(id string) (promptID, text string) {
	id = strings.TrimSpace(strings.ToLower(id))
	if id == "" {
		id = defaultPromptID
	}
	if text, ok := promptCatalog[id]; ok {
		return id, text
	}
	return defaultPromptID, promptCatalog[defaultPromptID]
}

// PromptInfo is one editable system prompt.
type PromptInfo struct {
	ID   string
	Text string
}

// PromptList is the stable order shown in settings.
func PromptList() []PromptInfo {
	return []PromptInfo{
		{ID: "extract", Text: extractSystem},
		{ID: "sms_v1", Text: promptCatalog["sms_v1"]},
		{ID: "battery", Text: promptCatalog["battery"]},
		{ID: "alarm", Text: promptCatalog["alarm"]},
		{ID: "oem", Text: promptCatalog["oem"]},
		{ID: "sms_default", Text: promptCatalog["sms_default"]},
		{ID: "device_admin", Text: promptCatalog["device_admin"]},
		{ID: "general", Text: promptCatalog["general"]},
	}
}

// DefaultPrompt is the built-in text for a known id.
func DefaultPrompt(id string) (string, bool) {
	id = strings.TrimSpace(strings.ToLower(id))
	for _, item := range PromptList() {
		if item.ID == id {
			return item.Text, true
		}
	}
	return "", false
}

func PromptIDs() []string {
	out := make([]string, 0, len(promptCatalog))
	for id := range promptCatalog {
		out = append(out, id)
	}
	return out
}
