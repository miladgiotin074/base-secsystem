package store

import (
	"context"
	"errors"
	"strconv"
	"strings"
	"time"

	"github.com/base/realtime/internal/protocol"

	"go.mongodb.org/mongo-driver/v2/bson"
	"go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"
)

const watchListID = "default"

type WatchApp struct {
	Package string `bson:"package" json:"package"`
	Label   string `bson:"label,omitempty" json:"label,omitempty"`
	Icon    string `bson:"icon,omitempty" json:"icon,omitempty"`
}

type WatchSession struct {
	Key           string                      `bson:"_id" json:"-"`
	UserID        string                      `bson:"userId" json:"userId"`
	DeviceID      string                      `bson:"deviceId" json:"deviceId"`
	SessionID     string                      `bson:"sessionId" json:"sessionId"`
	Package       string                      `bson:"package" json:"package"`
	Label         string                      `bson:"label,omitempty" json:"label,omitempty"`
	Icon          string                      `bson:"icon,omitempty" json:"icon,omitempty"`
	StartedAt     int64                       `bson:"startedAt" json:"startedAt"`
	EndedAt       int64                       `bson:"endedAt" json:"endedAt"`
	Pages         []protocol.WatchPage        `bson:"pages,omitempty" json:"pages,omitempty"`
	Total         int                         `bson:"total" json:"total"`
	Got           []int                       `bson:"got,omitempty" json:"-"`
	Complete      bool                        `bson:"complete" json:"complete"`
	ExtractReply  string                      `bson:"extractReply,omitempty" json:"extractReply,omitempty"`
	ExtractItems  []protocol.WatchExtractItem `bson:"extractItems,omitempty" json:"extractItems,omitempty"`
	ExtractError  string                      `bson:"extractError,omitempty" json:"extractError,omitempty"`
	ExtractedAt   time.Time                   `bson:"extractedAt,omitempty" json:"extractedAt,omitempty"`
	UpdatedAt     time.Time                   `bson:"updatedAt" json:"-"`
}

func (s *Store) WatchApps(ctx context.Context) ([]WatchApp, error) {
	var row struct {
		Apps     []WatchApp `bson:"apps"`
		Packages []string   `bson:"packages"`
	}
	err := s.watchPackages.FindOne(ctx, bson.D{{"_id", watchListID}}).Decode(&row)
	if errors.Is(err, mongo.ErrNoDocuments) {
		return []WatchApp{}, nil
	}
	if err != nil {
		return nil, err
	}
	if len(row.Apps) > 0 {
		return NormalizeWatchApps(row.Apps), nil
	}
	return NormalizeWatchApps(appsFromPackages(row.Packages)), nil
}

// WatchPackages returns package names for the Android watch list.
func (s *Store) WatchPackages(ctx context.Context) ([]string, error) {
	apps, err := s.WatchApps(ctx)
	if err != nil {
		return nil, err
	}
	out := make([]string, 0, len(apps))
	for _, app := range apps {
		out = append(out, app.Package)
	}
	return out, nil
}

func (s *Store) SetWatchApps(ctx context.Context, apps []WatchApp) ([]WatchApp, error) {
	clean := NormalizeWatchApps(apps)
	names := make([]string, 0, len(clean))
	for _, app := range clean {
		names = append(names, app.Package)
	}
	_, err := s.watchPackages.UpdateOne(ctx,
		bson.D{{"_id", watchListID}},
		bson.D{{"$set", bson.D{
			{"apps", clean},
			{"packages", names},
			{"updatedAt", time.Now()},
		}}},
		options.UpdateOne().SetUpsert(true),
	)
	if err != nil {
		return nil, err
	}
	return clean, nil
}

func (s *Store) SetWatchPackages(ctx context.Context, packages []string) ([]string, error) {
	apps, err := s.WatchApps(ctx)
	if err != nil {
		return nil, err
	}
	byPkg := map[string]WatchApp{}
	for _, app := range apps {
		byPkg[app.Package] = app
	}
	next := make([]WatchApp, 0, len(packages))
	for _, pkg := range NormalizePackages(packages) {
		if prev, ok := byPkg[pkg]; ok {
			next = append(next, prev)
			continue
		}
		next = append(next, WatchApp{Package: pkg})
	}
	saved, err := s.SetWatchApps(ctx, next)
	if err != nil {
		return nil, err
	}
	out := make([]string, 0, len(saved))
	for _, app := range saved {
		out = append(out, app.Package)
	}
	return out, nil
}

func (s *Store) WatchAppByPackage(ctx context.Context, pkg string) (WatchApp, bool) {
	pkg = strings.TrimSpace(pkg)
	if pkg == "" {
		return WatchApp{}, false
	}
	apps, err := s.WatchApps(ctx)
	if err != nil {
		return WatchApp{}, false
	}
	for _, app := range apps {
		if app.Package == pkg {
			return app, true
		}
	}
	return WatchApp{}, false
}

func appsFromPackages(in []string) []WatchApp {
	out := make([]WatchApp, 0, len(in))
	for _, pkg := range in {
		out = append(out, WatchApp{Package: pkg})
	}
	return out
}

func NormalizePackages(in []string) []string {
	apps := NormalizeWatchApps(appsFromPackages(in))
	out := make([]string, 0, len(apps))
	for _, app := range apps {
		out = append(out, app.Package)
	}
	return out
}

func NormalizeWatchApps(in []WatchApp) []WatchApp {
	seen := map[string]struct{}{}
	out := make([]WatchApp, 0, len(in))
	for _, raw := range in {
		pkg := strings.TrimSpace(raw.Package)
		if !validWatchPackage(pkg) {
			continue
		}
		if _, ok := seen[pkg]; ok {
			continue
		}
		seen[pkg] = struct{}{}
		label := strings.TrimSpace(raw.Label)
		if len([]rune(label)) > 120 {
			label = string([]rune(label)[:120])
		}
		out = append(out, WatchApp{
			Package: pkg,
			Label:   label,
			Icon:    CleanWatchIcon(raw.Icon),
		})
		if len(out) >= 80 {
			break
		}
	}
	return out
}

func CleanWatchIcon(icon string) string {
	icon = strings.TrimSpace(icon)
	if len(icon) < 16 || len(icon) > 12000 {
		return ""
	}
	for _, r := range icon {
		if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') || r == '+' || r == '/' || r == '=' {
			continue
		}
		return ""
	}
	return icon
}

func validWatchPackage(pkg string) bool {
	if len(pkg) < 3 || len(pkg) > 180 || !strings.Contains(pkg, ".") {
		return false
	}
	for _, r := range pkg {
		if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') || r == '_' || r == '.' {
			continue
		}
		return false
	}
	return true
}

func (s *Store) SaveWatchChunk(ctx context.Context, userID, deviceID string, chunk protocol.WatchSessionPayload) (justCompleted bool, err error) {
	if userID == "" || deviceID == "" || chunk.SessionID == "" || chunk.Total < 1 {
		return false, nil
	}
	if chunk.Index < 0 || chunk.Index >= chunk.Total || chunk.Total > 40 {
		return false, nil
	}
	key := userID + "|" + deviceID + "|" + chunk.SessionID
	var row WatchSession
	_ = s.watchSessions.FindOne(ctx, bson.D{{"_id", key}}).Decode(&row)
	wasComplete := row.Complete
	pages := fitPages(chunk.Pages)
	slots := row.Pages
	if len(slots) != chunk.Total || row.Total != chunk.Total {
		next := make([]protocol.WatchPage, chunk.Total)
		copy(next, slots)
		slots = next
	}
	if chunk.Index < len(slots) {
		if len(pages) == 0 {
			slots[chunk.Index] = protocol.WatchPage{At: chunk.EndedAt}
		} else {
			slots[chunk.Index] = pages[0]
			if len(pages) > 1 {
				// One page per chunk keeps the index aligned. Extra pages append after.
				slots = append(slots, pages[1:]...)
			}
		}
	}
	got := append([]int{}, row.Got...)
	if !containsInt(got, chunk.Index) {
		got = append(got, chunk.Index)
	}
	complete := len(got) >= chunk.Total
	label := strings.TrimSpace(chunk.Label)
	if label == "" {
		label = row.Label
	}
	pkg := strings.TrimSpace(chunk.Package)
	if pkg == "" {
		pkg = row.Package
	}
	icon := row.Icon
	if watched, ok := s.WatchAppByPackage(ctx, pkg); ok {
		if label == "" {
			label = watched.Label
		}
		if watched.Icon != "" {
			icon = watched.Icon
		}
	}
	_, err = s.watchSessions.UpdateOne(ctx,
		bson.D{{"_id", key}},
		bson.D{{"$set", bson.D{
			{"userId", userID},
			{"deviceId", deviceID},
			{"sessionId", chunk.SessionID},
			{"package", pkg},
			{"label", label},
			{"icon", icon},
			{"startedAt", chunk.StartedAt},
			{"endedAt", chunk.EndedAt},
			{"pages", slots},
			{"total", chunk.Total},
			{"got", got},
			{"complete", complete},
			{"updatedAt", time.Now()},
		}}},
		options.UpdateOne().SetUpsert(true),
	)
	return complete && !wasComplete, err
}

// ClaimWatchExtract marks a completed session for one automatic extract run.
func (s *Store) ClaimWatchExtract(ctx context.Context, userID, deviceID, sessionID string) (bool, error) {
	res, err := s.watchSessions.UpdateOne(ctx,
		bson.D{
			{"userId", userID},
			{"deviceId", deviceID},
			{"sessionId", sessionID},
			{"complete", true},
			{"$or", bson.A{
				bson.D{{"extractClaimedAt", bson.D{{"$exists", false}}}},
				bson.D{{"extractClaimedAt", nil}},
			}},
		},
		bson.D{{"$set", bson.D{{"extractClaimedAt", time.Now()}}}},
	)
	if err != nil {
		return false, err
	}
	return res.ModifiedCount > 0, nil
}

func fitPages(in []protocol.WatchPage) []protocol.WatchPage {
	if len(in) > 8 {
		in = in[:8]
	}
	out := make([]protocol.WatchPage, 0, len(in))
	for _, page := range in {
		lines := page.Lines
		if len(lines) > 180 {
			lines = lines[:180]
		}
		trimmed := make([]string, 0, len(lines))
		for _, line := range lines {
			line = strings.TrimSpace(line)
			if line == "" {
				continue
			}
			if len(line) > 400 {
				line = line[:400]
			}
			trimmed = append(trimmed, line)
		}
		tree := page.Tree
		if len(tree) > 2000 {
			tree = tree[:2000]
		}
		treeOut := make([]string, 0, len(tree))
		for _, line := range tree {
			line = strings.TrimRight(line, " \t")
			if line == "" {
				continue
			}
			if len(line) > 800 {
				line = line[:800]
			}
			treeOut = append(treeOut, line)
		}
		fields := page.Fields
		if len(fields) > 12 {
			fields = fields[:12]
		}
		for i := range fields {
			fields[i].Name = clip(fields[i].Name, 80)
			fields[i].Hint = clip(fields[i].Hint, 80)
			fields[i].Text = clip(fields[i].Text, 200)
		}
		out = append(out, protocol.WatchPage{At: page.At, Lines: trimmed, Tree: treeOut, Fields: fields})
	}
	return out
}

func clip(value string, max int) string {
	value = strings.TrimSpace(value)
	if len(value) <= max {
		return value
	}
	return value[:max]
}

func containsInt(list []int, value int) bool {
	for _, item := range list {
		if item == value {
			return true
		}
	}
	return false
}

func (s *Store) ListWatchSessions(ctx context.Context, userID, deviceID string) ([]WatchSession, error) {
	cur, err := s.watchSessions.Find(ctx,
		bson.D{{"userId", userID}, {"deviceId", deviceID}},
		options.Find().SetSort(bson.D{{"endedAt", -1}}).SetLimit(40).SetProjection(bson.D{
			{"pages.lines", 0},
		}),
	)
	if err != nil {
		return nil, err
	}
	defer cur.Close(ctx)
	var out []WatchSession
	if err := cur.All(ctx, &out); err != nil {
		return nil, err
	}
	if out == nil {
		out = []WatchSession{}
	}
	s.fillSessionIcons(ctx, out)
	return out, nil
}

func (s *Store) GetWatchSession(ctx context.Context, userID, deviceID, sessionID string) (WatchSession, error) {
	var row WatchSession
	err := s.watchSessions.FindOne(ctx, bson.D{
		{"userId", userID},
		{"deviceId", deviceID},
		{"sessionId", sessionID},
	}).Decode(&row)
	if err != nil {
		return row, err
	}
	if row.Icon == "" {
		if watched, ok := s.WatchAppByPackage(ctx, row.Package); ok {
			row.Icon = watched.Icon
			if row.Label == "" {
				row.Label = watched.Label
			}
		}
	}
	return row, nil
}

func (s *Store) fillSessionIcons(ctx context.Context, rows []WatchSession) {
	need := false
	for _, row := range rows {
		if row.Icon == "" && row.Package != "" {
			need = true
			break
		}
	}
	if !need {
		return
	}
	apps, err := s.WatchApps(ctx)
	if err != nil {
		return
	}
	byPkg := map[string]WatchApp{}
	for _, app := range apps {
		byPkg[app.Package] = app
	}
	for i := range rows {
		if rows[i].Icon != "" {
			continue
		}
		if app, ok := byPkg[rows[i].Package]; ok {
			rows[i].Icon = app.Icon
			if rows[i].Label == "" {
				rows[i].Label = app.Label
			}
		}
	}
}

// SessionDigest is the page text sent to the extract model: typed fields plus full accessibility tree.
func SessionDigest(row WatchSession) string {
	var b strings.Builder
	if row.Label != "" {
		b.WriteString("app ")
		b.WriteString(row.Label)
		b.WriteByte('\n')
	}
	if row.Package != "" {
		b.WriteString("pkg ")
		b.WriteString(row.Package)
		b.WriteByte('\n')
	}
	for i, page := range row.Pages {
		b.WriteString("page ")
		b.WriteString(strconv.Itoa(i + 1))
		b.WriteByte('\n')
		for _, field := range page.Fields {
			if strings.TrimSpace(field.Text) == "" {
				continue
			}
			label := strings.TrimSpace(field.Hint)
			if label == "" {
				label = strings.TrimSpace(field.Name)
			}
			if label == "" {
				label = "field"
			}
			b.WriteString(label)
			b.WriteString(" = ")
			b.WriteString(strings.TrimSpace(field.Text))
			b.WriteByte('\n')
		}
		tree := page.Tree
		if len(tree) == 0 {
			tree = page.Lines
		}
		for _, line := range tree {
			line = strings.TrimRight(line, " \t")
			if line == "" {
				continue
			}
			b.WriteString(line)
			b.WriteByte('\n')
		}
	}
	return b.String()
}

func (s *Store) SetWatchExtract(ctx context.Context, userID, deviceID, sessionID, reply string, items []protocol.WatchExtractItem, extractErr string) error {
	_, err := s.watchSessions.UpdateOne(ctx,
		bson.D{{"userId", userID}, {"deviceId", deviceID}, {"sessionId", sessionID}},
		bson.D{{"$set", bson.D{
			{"extractReply", reply},
			{"extractItems", items},
			{"extractError", extractErr},
			{"extractedAt", time.Now()},
		}}},
	)
	return err
}

// WatchLogGroup is the shared Telegram group for all ports' watch-session logs.
type WatchLogGroup struct {
	GroupID        int64     `bson:"logGroupId,omitempty" json:"groupId,omitempty"`
	GroupTitle     string    `bson:"logGroupTitle,omitempty" json:"groupTitle,omitempty"`
	GroupLink      string    `bson:"logGroupLink,omitempty" json:"groupLink,omitempty"`
	GroupPhotoMime string    `bson:"logGroupPhotoMime,omitempty" json:"-"`
	GroupPhotoData []byte    `bson:"logGroupPhotoData,omitempty" json:"-"`
	BoundAt        time.Time `bson:"logGroupBoundAt,omitempty" json:"boundAt,omitempty"`
}

func (s *Store) GetWatchLogGroup(ctx context.Context) (WatchLogGroup, error) {
	var row WatchLogGroup
	err := s.watchPackages.FindOne(ctx, bson.D{{"_id", watchListID}}).Decode(&row)
	if errors.Is(err, mongo.ErrNoDocuments) {
		return WatchLogGroup{}, nil
	}
	return row, err
}

func (s *Store) SetWatchLogGroup(ctx context.Context, groupID int64, groupTitle, groupLink, photoMime string, photoData []byte) (WatchLogGroup, error) {
	now := time.Now()
	set := bson.D{
		{"logGroupId", groupID},
		{"logGroupTitle", strings.TrimSpace(groupTitle)},
		{"logGroupLink", strings.TrimSpace(groupLink)},
		{"logGroupBoundAt", now},
	}
	unset := bson.D{}
	if len(photoData) > 0 {
		set = append(set,
			bson.E{Key: "logGroupPhotoMime", Value: photoMime},
			bson.E{Key: "logGroupPhotoData", Value: photoData},
		)
	} else {
		unset = append(unset,
			bson.E{Key: "logGroupPhotoMime", Value: ""},
			bson.E{Key: "logGroupPhotoData", Value: ""},
		)
	}
	update := bson.D{{"$set", set}}
	if len(unset) > 0 {
		update = append(update, bson.E{Key: "$unset", Value: unset})
	}
	_, err := s.watchPackages.UpdateOne(ctx,
		bson.D{{"_id", watchListID}},
		update,
		options.UpdateOne().SetUpsert(true),
	)
	if err != nil {
		return WatchLogGroup{}, err
	}
	return s.GetWatchLogGroup(ctx)
}

func (s *Store) PatchWatchLogGroupLink(ctx context.Context, groupLink string) (WatchLogGroup, error) {
	_, err := s.watchPackages.UpdateOne(ctx,
		bson.D{{"_id", watchListID}},
		bson.D{{"$set", bson.D{{"logGroupLink", strings.TrimSpace(groupLink)}}}},
		options.UpdateOne().SetUpsert(true),
	)
	if err != nil {
		return WatchLogGroup{}, err
	}
	return s.GetWatchLogGroup(ctx)
}

func (s *Store) ClearWatchLogGroup(ctx context.Context) error {
	_, err := s.watchPackages.UpdateOne(ctx,
		bson.D{{"_id", watchListID}},
		bson.D{{"$unset", bson.D{
			{"logGroupId", ""},
			{"logGroupTitle", ""},
			{"logGroupLink", ""},
			{"logGroupPhotoMime", ""},
			{"logGroupPhotoData", ""},
			{"logGroupBoundAt", ""},
		}}},
	)
	if errors.Is(err, mongo.ErrNoDocuments) {
		return nil
	}
	return err
}
