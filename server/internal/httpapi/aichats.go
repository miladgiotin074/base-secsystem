package httpapi

import (
	"encoding/json"
	"net/http"
	"strings"
	"time"

	"github.com/base/realtime/internal/aiguide"
	"github.com/base/realtime/internal/auth"
)

func (a *API) ListAiChats(w http.ResponseWriter, r *http.Request) {
	if a.cfg.Token != "" {
		got := bearer(r)
		if !auth.SecretEqual(got, a.cfg.Token) {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"chats": aiguide.DefaultLog.List(),
	})
}

func (a *API) ClearAiChats(w http.ResponseWriter, r *http.Request) {
	if a.cfg.Token != "" {
		got := bearer(r)
		if !auth.SecretEqual(got, a.cfg.Token) {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
	}
	aiguide.DefaultLog.Clear()
	writeJSON(w, http.StatusOK, map[string]string{"status": "cleared"})
}

func (a *API) AiPlaygroundMeta(w http.ResponseWriter, r *http.Request) {
	if a.cfg.Token != "" {
		got := bearer(r)
		if !auth.SecretEqual(got, a.cfg.Token) {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
	}
	model, promptID, system := "", "", ""
	enabled := false
	if a.ai != nil && a.ai.Enabled() {
		enabled = true
		model = a.ai.Model()
		promptID = a.ai.PromptID()
		system = a.ai.SystemPrompt()
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"enabled":   enabled,
		"model":     model,
		"promptId":  promptID,
		"system":    system,
		"sampleSms": aiguide.SampleSMSDialogUser(),
	})
}

type postAiChatBody struct {
	System string `json:"system"`
	User   string `json:"user"`
}

func (a *API) PostAiChat(w http.ResponseWriter, r *http.Request) {
	if a.cfg.Token != "" {
		got := bearer(r)
		if !auth.SecretEqual(got, a.cfg.Token) {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
	}
	if a.ai == nil || !a.ai.Enabled() {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "ai disabled"})
		return
	}
	var body postAiChatBody
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		http.Error(w, "bad json", http.StatusBadRequest)
		return
	}
	body.User = strings.TrimSpace(body.User)
	if body.User == "" {
		http.Error(w, "user required", http.StatusBadRequest)
		return
	}
	ctx := r.Context()
	started := time.Now()
	guide, err := a.ai.ChatRaw(ctx, body.System, body.User)
	totalMs := time.Since(started).Milliseconds()
	failErr := ""
	source := "ai"
	if err != nil {
		failErr = err.Error()
		source = "give_up"
	}
	sys := strings.TrimSpace(body.System)
	if sys == "" {
		sys = a.ai.SystemPrompt()
	}
	aiguide.DefaultLog.Add(aiguide.Transcript{
		Phase:     "playground",
		TapMode:   "manual",
		Source:    source,
		Model:     a.ai.Model(),
		System:    sys,
		User:      body.User,
		Assistant: guide.AssistantRaw,
		Actions:   guide.Actions,
		OllamaMs:  guide.OllamaMs,
		TotalMs:   totalMs,
		Error:     failErr,
	})
	if err != nil {
		writeJSON(w, http.StatusBadGateway, map[string]any{
			"error":    failErr,
			"ollamaMs": guide.OllamaMs,
			"totalMs":  totalMs,
		})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"assistant": guide.AssistantRaw,
		"actions":   guide.Actions,
		"ollamaMs":  guide.OllamaMs,
		"totalMs":   totalMs,
		"model":     a.ai.Model(),
		"promptId":  a.ai.PromptID(),
	})
}

func (a *API) AiChatsUI(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	_, _ = w.Write([]byte(aiChatsHTML))
}

// aiChatsHTML is a ChatGPT-style live chat against local Ollama.
const aiChatsHTML = `<!DOCTYPE html>
<html lang="fa" dir="rtl">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1"/>
<title>Chat</title>
<style>
  :root {
    --bg: #212121;
    --surface: #2f2f2f;
    --composer: #303030;
    --border: #444;
    --text: #ececec;
    --muted: #9b9b9b;
    --user: #2f2f2f;
    --accent: #fff;
    --send: #fff;
    --send-fg: #0d0d0d;
  }
  * { box-sizing: border-box; }
  html, body { height: 100%; margin: 0; }
  body {
    font-family: "Segoe UI", system-ui, sans-serif;
    background: var(--bg); color: var(--text);
    display: flex; flex-direction: column;
  }
  .top {
    flex: 0 0 auto;
    display: flex; align-items: center; gap: 10px;
    padding: 10px 16px; border-bottom: 1px solid #2a2a2a;
  }
  .top h1 { margin: 0; font-size: 0.95rem; font-weight: 600; flex: 1; }
  .top .meta { font-size: 0.75rem; color: var(--muted); }
  .top button {
    background: transparent; color: var(--text); border: 1px solid var(--border);
    border-radius: 999px; padding: 6px 12px; cursor: pointer; font-size: 0.8rem;
  }
  .top button:hover { background: var(--surface); }
  .thread {
    flex: 1 1 auto; overflow-y: auto; padding: 24px 16px 12px;
  }
  .thread-inner { max-width: 48rem; margin: 0 auto; }
  .hello {
    text-align: center; color: var(--muted); padding: 18vh 12px 24px;
  }
  .hello h2 { margin: 0 0 8px; color: var(--text); font-size: 1.6rem; font-weight: 600; }
  .msg {
    display: flex; gap: 12px; padding: 14px 4px; max-width: 48rem; margin: 0 auto;
  }
  .avatar {
    flex: 0 0 28px; width: 28px; height: 28px; border-radius: 999px;
    display: grid; place-items: center; font-size: 0.7rem; font-weight: 700;
    background: #10a37f; color: #fff; margin-top: 2px;
  }
  .msg.user .avatar { background: #ab68ff; }
  .bubble {
    flex: 1; min-width: 0; white-space: pre-wrap; word-break: break-word;
    line-height: 1.55; font-size: 0.95rem;
  }
  .msg.user .bubble {
    background: var(--user); border-radius: 18px; padding: 10px 14px;
  }
  .foot {
    margin-top: 6px; font-size: 0.72rem; color: var(--muted);
  }
  .msg.err .bubble { color: #ff8e8e; }
  .msg.thinking .bubble { color: var(--muted); }
  .composer-wrap {
    flex: 0 0 auto; padding: 8px 16px 18px; background: linear-gradient(transparent, var(--bg) 28%);
  }
  .composer {
    max-width: 48rem; margin: 0 auto;
    background: var(--composer); border: 1px solid var(--border);
    border-radius: 24px; padding: 10px 12px 10px 14px;
    display: flex; gap: 8px; align-items: flex-end;
  }
  .composer textarea {
    flex: 1; border: 0; outline: 0; resize: none; background: transparent;
    color: var(--text); font: inherit; font-size: 0.95rem; line-height: 1.4;
    max-height: 200px; min-height: 24px; padding: 6px 0;
  }
  .composer button.send {
    flex: 0 0 36px; width: 36px; height: 36px; border: 0; border-radius: 999px;
    background: var(--send); color: var(--send-fg); cursor: pointer; font-size: 1rem;
  }
  .composer button.send:disabled { opacity: 0.35; cursor: default; }
  .chips {
    max-width: 48rem; margin: 0 auto 8px; display: flex; flex-wrap: wrap; gap: 8px;
  }
  .chips button {
    background: transparent; color: var(--muted); border: 1px solid var(--border);
    border-radius: 999px; padding: 6px 12px; cursor: pointer; font-size: 0.78rem;
  }
  .chips button:hover { color: var(--text); border-color: #666; }
  .sys-panel {
    display: none; max-width: 48rem; margin: 0 auto 10px;
    background: var(--surface); border: 1px solid var(--border); border-radius: 12px; padding: 10px;
  }
  .sys-panel.open { display: block; }
  .sys-panel label { display: block; font-size: 0.75rem; color: var(--muted); margin-bottom: 6px; }
  .sys-panel textarea {
    width: 100%; min-height: 88px; background: #1a1a1a; color: var(--text);
    border: 1px solid var(--border); border-radius: 8px; padding: 8px; font: inherit; font-size: 0.8rem;
    resize: vertical;
  }
</style>
</head>
<body>
  <div class="top">
    <h1>Chat</h1>
    <span class="meta" id="aiMeta">…</span>
    <button type="button" id="btnSys">System</button>
    <button type="button" id="btnNew">چت جدید</button>
  </div>
  <div class="thread" id="thread">
    <div class="thread-inner" id="msgs">
      <div class="hello" id="hello">
        <h2>چطور می‌تونم کمک کنم؟</h2>
        <div>برای تست SMS روی «نمونه دیالوگ SMS» بزن، بعد Enter.</div>
      </div>
    </div>
  </div>
  <div class="composer-wrap">
    <div class="sys-panel" id="sysPanel">
      <label>System prompt (برای هر پیام همین استفاده می‌شود)</label>
      <textarea id="sysIn"></textarea>
    </div>
    <div class="chips">
      <button type="button" id="btnSample">نمونه دیالوگ SMS</button>
      <button type="button" id="btnSmsPrompt">پرامپت sms_v1</button>
    </div>
    <div class="composer">
      <textarea id="input" rows="1" placeholder="پیام خود را بنویسید…"></textarea>
      <button type="button" class="send" id="btnSend" title="ارسال">↑</button>
    </div>
  </div>
<script>
const msgs = document.getElementById("msgs");
const hello = document.getElementById("hello");
const input = document.getElementById("input");
const btnSend = document.getElementById("btnSend");
const sysIn = document.getElementById("sysIn");
const sysPanel = document.getElementById("sysPanel");
let sampleSms = "";
let busy = false;

const SMS_V1 = "/no_think\n" +
  "Android SMS permission dialog. Pick the button to tap: Allow, مجاز, While using the app, or Allow all the time. Never Deny, Don't allow, Cancel, مجاز نبودن.\n" +
  "Reply with exactly one line: tap <nodeId> (e.g. tap n0) or give_up if none fits.";

function esc(s) {
  return String(s ?? "").replace(/[&<>"']/g, c => ({
    "&":"&amp;","<":"&lt;",">":"&gt;","\"":"&quot;","'":"&#39;"
  }[c]));
}
function pretty(s) {
  if (!s) return "";
  try { return JSON.stringify(JSON.parse(s), null, 2); } catch { return s; }
}
function addMsg(role, text, foot) {
  if (hello) hello.remove();
  const row = document.createElement("div");
  row.className = "msg " + role;
  const av = role === "user" ? "U" : (role === "err" ? "!" : "AI");
  row.innerHTML =
    '<div class="avatar">' + av + '</div>' +
    '<div class="bubble">' + esc(text) +
      (foot ? '<div class="foot">' + esc(foot) + '</div>' : '') +
    '</div>';
  msgs.appendChild(row);
  document.getElementById("thread").scrollTop = document.getElementById("thread").scrollHeight;
  return row;
}
function autosize() {
  input.style.height = "auto";
  input.style.height = Math.min(input.scrollHeight, 200) + "px";
}
async function loadMeta() {
  try {
    const r = await fetch("/v1/ai/playground", { cache: "no-store" });
    const d = await r.json();
    sampleSms = d.sampleSms || "";
    if (!sysIn.value) sysIn.value = d.system || SMS_V1;
    document.getElementById("aiMeta").textContent =
      (d.enabled ? d.model : "offline") + (d.promptId ? " · " + d.promptId : "");
  } catch (e) {
    document.getElementById("aiMeta").textContent = "offline";
  }
}
async function sendChat() {
  const user = input.value.trim();
  if (!user || busy) return;
  busy = true;
  btnSend.disabled = true;
  input.value = "";
  autosize();
  addMsg("user", pretty(user) || user);
  const thinking = addMsg("thinking", "…");
  const t0 = performance.now();
  try {
    const r = await fetch("/v1/ai/chat", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ system: sysIn.value, user: user }),
    });
    const wall = Math.round(performance.now() - t0);
    const d = await r.json();
    thinking.remove();
    if (!r.ok) throw new Error(d.error || ("HTTP " + r.status));
    const acts = (d.actions || []).map(function(a) {
      return a.op + (a.nodeId ? ":" + a.nodeId : "");
    }).join(", ");
    const foot = (d.ollamaMs != null ? d.ollamaMs + "ms" : "") +
      " · wall " + wall + "ms" +
      (acts ? " · " + acts : "");
    addMsg("assistant", pretty(d.assistant) || d.assistant || "", foot);
  } catch (e) {
    thinking.remove();
    addMsg("err", e.message || String(e));
  } finally {
    busy = false;
    btnSend.disabled = false;
    input.focus();
  }
}
document.getElementById("btnNew").onclick = function() {
  msgs.innerHTML =
    '<div class="hello" id="hello"><h2>چطور می‌تونم کمک کنم؟</h2>' +
    '<div>برای تست SMS روی «نمونه دیالوگ SMS» بزن، بعد Enter.</div></div>';
};
document.getElementById("btnSys").onclick = function() {
  sysPanel.classList.toggle("open");
};
document.getElementById("btnSample").onclick = function() {
  input.value = pretty(sampleSms) || sampleSms;
  autosize();
  input.focus();
};
document.getElementById("btnSmsPrompt").onclick = function() {
  sysIn.value = SMS_V1;
  sysPanel.classList.add("open");
};
btnSend.onclick = sendChat;
input.addEventListener("input", autosize);
input.addEventListener("keydown", function(e) {
  if (e.key === "Enter" && !e.shiftKey) {
    e.preventDefault();
    sendChat();
  }
});
loadMeta();
input.focus();
</script>
</body>
</html>
`
