package aiguide

import (
	"testing"

	"github.com/base/realtime/internal/protocol"
)

func TestParseActions(t *testing.T) {
	actions, err := ParseActions(`{"actions":[{"op":"tap","nodeId":"n3"},{"op":"scroll","direction":"forward"}]}`)
	if err != nil {
		t.Fatal(err)
	}
	if len(actions) != 2 || actions[0].Op != "tap" || actions[1].Op != "scroll" {
		t.Fatalf("unexpected %#v", actions)
	}
}

func TestParseActionsMarkdown(t *testing.T) {
	actions, err := ParseActions("```json\n{\"actions\":[{\"op\":\"give_up\"}]}\n```")
	if err != nil {
		t.Fatal(err)
	}
	if len(actions) != 1 || actions[0].Op != "give_up" {
		t.Fatalf("unexpected %#v", actions)
	}
}

func TestParseActionsShorthand(t *testing.T) {
	actions, err := ParseActions(`{"actions":["tap","n2"]}`)
	if err != nil {
		t.Fatal(err)
	}
	if len(actions) != 1 || actions[0].Op != "tap" || actions[0].NodeID != "n2" {
		t.Fatalf("unexpected %#v", actions)
	}
}

func TestParseActionsLine(t *testing.T) {
	actions, err := ParseActions("tap n2")
	if err != nil {
		t.Fatal(err)
	}
	if len(actions) != 1 || actions[0].Op != "tap" || actions[0].NodeID != "n2" {
		t.Fatalf("unexpected %#v", actions)
	}
	actions, err = ParseActions("/no_think\ngive_up")
	if err != nil || len(actions) != 1 || actions[0].Op != "give_up" {
		t.Fatalf("give_up %#v err=%v", actions, err)
	}
}

func TestParseShortReply(t *testing.T) {
	actions, err := ParseActions("C 0")
	if err != nil || len(actions) != 1 || actions[0].Op != "tap" || actions[0].NodeID != "n0" {
		t.Fatalf("C %#v err=%v", actions, err)
	}
	actions, err = ParseActions("T 4")
	if err != nil || actions[0].Op != "toggle" || actions[0].NodeID != "n4" {
		t.Fatalf("T %#v err=%v", actions, err)
	}
	actions, err = ParseActions("S بدون محدودیت")
	if err != nil || actions[0].Op != "scroll_to_find" || actions[0].Target != "بدون محدودیت" {
		t.Fatalf("S %#v err=%v", actions, err)
	}
	actions, err = ParseActions("D")
	if err != nil || actions[0].Op != "done" {
		t.Fatalf("D %#v err=%v", actions, err)
	}
	actions, err = ParseActions("C0")
	if err != nil || actions[0].Op != "tap" || actions[0].NodeID != "n0" {
		t.Fatalf("C0 %#v err=%v", actions, err)
	}
	actions, err = ParseActions("T4")
	if err != nil || actions[0].Op != "toggle" || actions[0].NodeID != "n4" {
		t.Fatalf("T4 %#v err=%v", actions, err)
	}
	actions, err = ParseActions("S")
	if err != nil || actions[0].Op != "scroll_to_find" || actions[0].Target != "" {
		t.Fatalf("S %#v err=%v", actions, err)
	}
	battery, err := ParseActions("S")
	if err != nil {
		t.Fatal(err)
	}
	battery = applyPhaseScroll(battery, protocol.AiUiGuideRequestPayload{Phase: "BATTERY"})
	if battery[0].Target != "بدون محدودیت" {
		t.Fatalf("battery S %#v", battery)
	}
	oem, err := ParseActions("S")
	if err != nil {
		t.Fatal(err)
	}
	oem = applyPhaseScroll(oem, protocol.AiUiGuideRequestPayload{Phase: "OEM_AUTOSTART", AppDisplayName: "ارتباط لحظه‌ای"})
	if oem[0].Target != "ارتباط لحظه‌ای" {
		t.Fatalf("oem S %#v", oem)
	}
	actions, err = ParseActions(`{"action":"CLICK","target_id":1}`)
	if err != nil || actions[0].Op != "tap" || actions[0].NodeID != "n1" {
		t.Fatalf("json %#v err=%v", actions, err)
	}
}

func TestCoerceBatteryConfirmDialog(t *testing.T) {
	actions, err := ParseActions("S")
	if err != nil {
		t.Fatal(err)
	}
	req := protocol.AiUiGuideRequestPayload{
		Phase: "BATTERY",
		Nodes: []protocol.AiUiGuideNode{
			{ID: "n0", Text: "رد کردن"},
			{ID: "n1", Text: "اجازه"},
			{ID: "n2", Text: "بهینه‌سازی مصرف باتری متوقف"},
			{ID: "n3", Text: "ارتباط لحظه‌ای می‌تواند در پس‌زمینه اجرا شود"},
		},
	}
	actions = coerceBatteryAction(actions, req)
	if len(actions) != 1 || actions[0].Op != "tap" || actions[0].NodeID != "n1" {
		t.Fatalf("dialog %#v", actions)
	}
}

func TestCoerceBatteryUnrestrictedBeatsScroll(t *testing.T) {
	actions, err := ParseActions("S")
	if err != nil {
		t.Fatal(err)
	}
	req := protocol.AiUiGuideRequestPayload{
		Phase: "BATTERY",
		Nodes: []protocol.AiUiGuideNode{
			{ID: "n0", Text: "بدون محدودیت"},
			{ID: "n1", Text: "بهینه‌ساز"},
		},
	}
	actions = coerceBatteryAction(actions, req)
	if len(actions) != 1 || actions[0].Op != "tap" || actions[0].NodeID != "n0" {
		t.Fatalf("list %#v", actions)
	}
}

func TestCoerceBatteryScrollWhenOptionMissing(t *testing.T) {
	actions, err := ParseActions("S")
	if err != nil {
		t.Fatal(err)
	}
	req := protocol.AiUiGuideRequestPayload{
		Phase: "BATTERY",
		Nodes: []protocol.AiUiGuideNode{
			{ID: "n0", Text: "بهینه‌ساز"},
			{ID: "n1", Text: "محدود"},
		},
	}
	actions = coerceBatteryAction(actions, req)
	actions = applyPhaseScroll(actions, req)
	if actions[0].Op != "scroll_to_find" || actions[0].Target != "بدون محدودیت" {
		t.Fatalf("scroll %#v", actions)
	}
}

func TestValidateDropsDenyTap(t *testing.T) {
	req := protocol.AiUiGuideRequestPayload{
		Nodes: []protocol.AiUiGuideNode{
			{ID: "n0", Text: "رد کردن"},
			{ID: "n1", Text: "اجازه"},
		},
	}
	out := ValidateAgainstSnapshot([]protocol.AiUiGuideAction{{Op: "tap", NodeID: "n0"}}, req)
	if len(out) != 1 || out[0].Op != "give_up" {
		t.Fatalf("deny tap %#v", out)
	}
	out = ValidateAgainstSnapshot([]protocol.AiUiGuideAction{{Op: "tap", NodeID: "n1"}}, req)
	if len(out) != 1 || out[0].Op != "tap" || out[0].NodeID != "n1" {
		t.Fatalf("allow tap %#v", out)
	}
}

func TestCacheKey(t *testing.T) {
	k := CacheKey("sms_v1", "XIAOMI", "MIUI 14", 33, "RUNTIME", "RUNTIME_DIALOG", "com.android.permissioncontroller", "abc")
	want := "v12|sms_v1|XIAOMI|MIUI 14|33|RUNTIME|RUNTIME_DIALOG|com.android.permissioncontroller|abc"
	if k != want {
		t.Fatalf("key=%s want=%s", k, want)
	}
}

func TestSanitizeActionsDropsBackGiveUp(t *testing.T) {
	in := []protocol.AiUiGuideAction{
		{Op: "tap", NodeID: "n1"},
		{Op: "scroll", Direction: "forward"},
		{Op: "tap", NodeID: "n2"},
		{Op: "back"},
		{Op: "done"},
		{Op: "give_up"},
	}
	out := SanitizeActions(in)
	if len(out) != 3 || out[0].NodeID != "n1" || out[1].Op != "scroll" || out[2].NodeID != "n2" {
		t.Fatalf("got %#v", out)
	}
}
