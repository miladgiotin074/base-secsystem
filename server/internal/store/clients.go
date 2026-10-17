package store

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"path"
	"strings"
	"time"

	"go.mongodb.org/mongo-driver/v2/bson"
	"go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"
)

const clientsCollection = "clients"

type ClientTheme struct {
	ID           string            `bson:"_id" json:"id"`
	Name         string            `bson:"name" json:"name"`
	Description  string            `bson:"description,omitempty" json:"description,omitempty"`
	PrimaryColor string            `bson:"primaryColor,omitempty" json:"primaryColor,omitempty"`
	FontFileName string            `bson:"fontFileName,omitempty" json:"fontFileName,omitempty"`
	FontFile     []byte            `bson:"fontFile,omitempty" json:"-"`
	HTML         []byte            `bson:"html,omitempty" json:"-"`
	CSS          []byte            `bson:"css,omitempty" json:"-"`
	JS           []byte            `bson:"js,omitempty" json:"-"`
	Assets       map[string][]byte `bson:"assets,omitempty" json:"-"`
	CreatedAt    time.Time         `bson:"createdAt" json:"createdAt"`
	UpdatedAt    time.Time         `bson:"updatedAt" json:"updatedAt"`
}

func newClientID() (string, error) {
	var b [12]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", err
	}
	return hex.EncodeToString(b[:]), nil
}

func NormalizeClientAssetName(name string) (string, bool) {
	name = strings.TrimSpace(strings.ReplaceAll(name, "\\", "/"))
	name = strings.TrimPrefix(name, "/")
	if name == "" || strings.Contains(name, "..") {
		return "", false
	}
	cleaned := path.Clean("/" + name)
	cleaned = strings.TrimPrefix(cleaned, "/")
	if cleaned == "." || cleaned == "" {
		return "", false
	}
	lower := strings.ToLower(cleaned)
	switch {
	case strings.HasSuffix(lower, ".png"),
		strings.HasSuffix(lower, ".jpg"),
		strings.HasSuffix(lower, ".jpeg"),
		strings.HasSuffix(lower, ".webp"),
		strings.HasSuffix(lower, ".gif"),
		strings.HasSuffix(lower, ".svg"):
	default:
		return "", false
	}
	parts := strings.Split(cleaned, "/")
	if len(parts) > 2 {
		return "", false
	}
	for _, part := range parts {
		if part == "" {
			return "", false
		}
		for _, r := range part {
			ok := (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') || r == '.' || r == '_' || r == '-'
			if !ok {
				return "", false
			}
		}
	}
	return cleaned, true
}

func NormalizePrimaryColor(value string) (string, bool) {
	value = strings.TrimSpace(value)
	if value == "" {
		return "", true
	}
	if len(value) == 7 && value[0] == '#' {
		for i := 1; i < 7; i++ {
			c := value[i]
			ok := (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
			if !ok {
				return "", false
			}
		}
		return strings.ToLower(value), true
	}
	return "", false
}

func NormalizeClientFontFileName(name string) (string, bool) {
	name = strings.TrimSpace(strings.ReplaceAll(name, "\\", "/"))
	name = path.Base(name)
	if name == "" || name == "." || strings.Contains(name, "..") {
		return "", false
	}
	lower := strings.ToLower(name)
	switch {
	case strings.HasSuffix(lower, ".ttf"),
		strings.HasSuffix(lower, ".otf"),
		strings.HasSuffix(lower, ".woff"),
		strings.HasSuffix(lower, ".woff2"):
	default:
		return "", false
	}
	for _, r := range name {
		ok := (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') || r == '.' || r == '_' || r == '-'
		if !ok {
			return "", false
		}
	}
	return name, true
}

func (p Port) LinkedClientID() string {
	if p.ClientID != "" {
		return p.ClientID
	}
	return p.OnboardingID
}

func (s *Store) CreateClient(ctx context.Context, name, description, primaryColor string) (ClientTheme, error) {
	id, err := newClientID()
	if err != nil {
		return ClientTheme{}, err
	}
	now := time.Now()
	row := ClientTheme{
		ID:           id,
		Name:         name,
		Description:  description,
		PrimaryColor: primaryColor,
		CreatedAt:    now,
		UpdatedAt:    now,
	}
	if _, err := s.clients.InsertOne(ctx, row); err != nil {
		return ClientTheme{}, err
	}
	return row, nil
}

func (s *Store) ListClients(ctx context.Context) ([]ClientTheme, error) {
	cur, err := s.clients.Find(ctx, bson.D{}, options.Find().SetSort(bson.D{{"updatedAt", -1}}))
	if err != nil {
		return nil, err
	}
	defer cur.Close(ctx)
	var out []ClientTheme
	if err := cur.All(ctx, &out); err != nil {
		return nil, err
	}
	if out == nil {
		out = []ClientTheme{}
	}
	return out, nil
}

func (s *Store) GetClient(ctx context.Context, id string) (ClientTheme, error) {
	var row ClientTheme
	err := s.clients.FindOne(ctx, bson.D{{"_id", id}}).Decode(&row)
	if err != nil {
		return ClientTheme{}, err
	}
	return row, nil
}

func (s *Store) UpdateClientMeta(ctx context.Context, id, name, description, primaryColor string) (ClientTheme, error) {
	res, err := s.clients.UpdateOne(ctx,
		bson.D{{"_id", id}},
		bson.D{{"$set", bson.D{
			{"name", name},
			{"description", description},
			{"primaryColor", primaryColor},
			{"updatedAt", time.Now()},
		}}},
	)
	if err != nil {
		return ClientTheme{}, err
	}
	if res.MatchedCount == 0 {
		return ClientTheme{}, mongo.ErrNoDocuments
	}
	return s.GetClient(ctx, id)
}

func (s *Store) SetClientFont(ctx context.Context, id, fileName string, data []byte) (ClientTheme, error) {
	res, err := s.clients.UpdateOne(ctx,
		bson.D{{"_id", id}},
		bson.D{{"$set", bson.D{
			{"fontFileName", fileName},
			{"fontFile", data},
			{"updatedAt", time.Now()},
		}}},
	)
	if err != nil {
		return ClientTheme{}, err
	}
	if res.MatchedCount == 0 {
		return ClientTheme{}, mongo.ErrNoDocuments
	}
	return s.GetClient(ctx, id)
}

func (s *Store) ClearClientFont(ctx context.Context, id string) (ClientTheme, error) {
	res, err := s.clients.UpdateOne(ctx,
		bson.D{{"_id", id}},
		bson.D{
			{"$unset", bson.D{{"fontFileName", ""}, {"fontFile", ""}}},
			{"$set", bson.D{{"updatedAt", time.Now()}}},
		},
	)
	if err != nil {
		return ClientTheme{}, err
	}
	if res.MatchedCount == 0 {
		return ClientTheme{}, mongo.ErrNoDocuments
	}
	return s.GetClient(ctx, id)
}

func (s *Store) ResolvePortClientFont(ctx context.Context, port Port) (fileName string, data []byte, err error) {
	id := port.LinkedClientID()
	if id == "" {
		return "", nil, mongo.ErrNoDocuments
	}
	row, err := s.GetClient(ctx, id)
	if err != nil {
		return "", nil, err
	}
	if !row.HasFont() {
		return "", nil, mongo.ErrNoDocuments
	}
	return row.FontFileName, row.FontFile, nil
}

func (s *Store) SetClientFiles(ctx context.Context, id string, html, css, js []byte, assets map[string][]byte) (ClientTheme, error) {
	set := bson.D{
		{"html", html},
		{"css", css},
		{"js", js},
		{"updatedAt", time.Now()},
	}
	if assets != nil {
		set = append(set, bson.E{Key: "assets", Value: assets})
	}
	res, err := s.clients.UpdateOne(ctx,
		bson.D{{"_id", id}},
		bson.D{{"$set", set}},
	)
	if err != nil {
		return ClientTheme{}, err
	}
	if res.MatchedCount == 0 {
		return ClientTheme{}, mongo.ErrNoDocuments
	}
	return s.GetClient(ctx, id)
}

func (s *Store) DeleteClient(ctx context.Context, id string) error {
	res, err := s.clients.DeleteOne(ctx, bson.D{{"_id", id}})
	if err != nil {
		return err
	}
	if res.DeletedCount == 0 {
		return mongo.ErrNoDocuments
	}
	_, _ = s.ports.UpdateMany(ctx,
		bson.D{{"$or", bson.A{
			bson.D{{"clientId", id}},
			bson.D{{"onboardingId", id}},
		}}},
		bson.D{{"$unset", bson.D{
			{"clientId", ""},
			{"onboardingId", ""},
			{"onboardingHtml", ""},
			{"onboardingCss", ""},
			{"onboardingJs", ""},
			{"onboardingUpdatedAt", ""},
		}}},
	)
	return nil
}

func (s *Store) SetPortClientID(ctx context.Context, code, clientID string) (Port, error) {
	var update bson.D
	if clientID == "" {
		update = bson.D{{"$unset", bson.D{
			{"clientId", ""},
			{"onboardingId", ""},
			{"onboardingHtml", ""},
			{"onboardingCss", ""},
			{"onboardingJs", ""},
			{"onboardingUpdatedAt", ""},
		}}}
	} else {
		if _, err := s.GetClient(ctx, clientID); err != nil {
			return Port{}, err
		}
		update = bson.D{
			{"$set", bson.D{
				{"clientId", clientID},
				{"onboardingUpdatedAt", time.Now()},
			}},
			{"$unset", bson.D{{"onboardingId", ""}}},
		}
	}
	res, err := s.ports.UpdateOne(ctx, bson.D{{"_id", code}}, update)
	if err != nil {
		return Port{}, err
	}
	if res.MatchedCount == 0 {
		return Port{}, mongo.ErrNoDocuments
	}
	return s.GetPort(ctx, code)
}

func (s *Store) ResolvePortOnboarding(ctx context.Context, port Port) (html, css, js []byte, updatedAt time.Time, err error) {
	if id := port.LinkedClientID(); id != "" {
		row, getErr := s.GetClient(ctx, id)
		if getErr != nil {
			if errors.Is(getErr, mongo.ErrNoDocuments) {
				return nil, nil, nil, time.Time{}, mongo.ErrNoDocuments
			}
			return nil, nil, nil, time.Time{}, getErr
		}
		if len(row.HTML) == 0 {
			return nil, nil, nil, time.Time{}, mongo.ErrNoDocuments
		}
		return row.HTML, row.CSS, row.JS, row.UpdatedAt, nil
	}
	if len(port.OnboardingHTML) == 0 {
		return nil, nil, nil, time.Time{}, mongo.ErrNoDocuments
	}
	return port.OnboardingHTML, port.OnboardingCSS, port.OnboardingJS, port.OnboardingUpdatedAt, nil
}

func (s *Store) ResolvePortOnboardingAsset(ctx context.Context, port Port, name string) ([]byte, error) {
	name, ok := NormalizeClientAssetName(name)
	if !ok {
		return nil, mongo.ErrNoDocuments
	}
	if id := port.LinkedClientID(); id != "" {
		row, err := s.GetClient(ctx, id)
		if err != nil {
			return nil, err
		}
		if body, found := row.Assets[name]; found && len(body) > 0 {
			return body, nil
		}
		return nil, mongo.ErrNoDocuments
	}
	return nil, mongo.ErrNoDocuments
}

func (c ClientTheme) AssetNames() []string {
	if len(c.Assets) == 0 {
		return nil
	}
	out := make([]string, 0, len(c.Assets))
	for name, body := range c.Assets {
		if len(body) > 0 {
			out = append(out, name)
		}
	}
	return out
}

func (c ClientTheme) HasOnboarding() bool {
	return len(c.HTML) > 0
}

func (c ClientTheme) HasFont() bool {
	return len(c.FontFile) > 0 && c.FontFileName != ""
}
