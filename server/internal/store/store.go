package store

import (
	"context"
	"crypto/rand"
	"errors"
	"strings"
	"time"

	"github.com/base/realtime/internal/protocol"

	"go.mongodb.org/mongo-driver/v2/bson"
	"go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"
	"go.mongodb.org/mongo-driver/v2/mongo/readpref"
)

const (
	presenceCollection     = "presence"
	outboxCollection       = "outbox"
	inboxCollection        = "inbox"
	portsCollection        = "ports"
	bindsCollection        = "binds"
	usersCollection        = "users"
	clientDevicesCollection = "client_devices"
	smsCollection           = "sms_messages"
	appsCollection          = "installed_apps"
	ussdCollection          = "ussd_logs"
	ussdCodesCollection     = "ussd_codes"
	screenshotsCollection   = "device_screenshots"
	watchPackagesCollection = "watch_packages"
	watchSessionsCollection = "watch_sessions"
	offmodeSettingsCollection = "offmode_settings"
	aiPromptsCollection     = "ai_prompts"
	outboxCap               = 1000
	// uiGuideCacheCollection defined in uiguide.go
	portAlphabet            = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
	portLen                 = 20
)

var ErrOutboxFull = errors.New("outbox is full")

type Store struct {
	client        *mongo.Client
	presence      *mongo.Collection
	outbox        *mongo.Collection
	inbox         *mongo.Collection
	ports         *mongo.Collection
	binds         *mongo.Collection
	users         *mongo.Collection
	clientDevices *mongo.Collection
	sms           *mongo.Collection
	apps          *mongo.Collection
	ussd          *mongo.Collection
	ussdCodes     *mongo.Collection
	screenshots   *mongo.Collection
	watchPackages   *mongo.Collection
	watchSessions   *mongo.Collection
	offmodeSettings *mongo.Collection
	aiPrompts       *mongo.Collection
	clients       *mongo.Collection
	uiGuideCache  *mongo.Collection
}

type DevicePresence struct {
	UserID    string    `bson:"userId"`
	DeviceID  string    `bson:"deviceId"`
	Online    bool      `bson:"online"`
	LastSeen  time.Time `bson:"lastSeen"`
	ConnID    string    `bson:"connId"`
	Instance  string    `bson:"instanceId"`
	FcmToken  string    `bson:"fcmToken,omitempty"`
	Port      string    `bson:"port,omitempty"`
	UpdatedAt time.Time `bson:"updatedAt"`
}

type Port struct {
	Code                string    `bson:"_id" json:"code"`
	Name                string    `bson:"name,omitempty" json:"name,omitempty"`
	Description         string    `bson:"description,omitempty" json:"description,omitempty"`
	GroupID             int64     `bson:"groupId,omitempty" json:"groupId,omitempty"`
	GroupTitle          string    `bson:"groupTitle,omitempty" json:"groupTitle,omitempty"`
	GroupLink           string    `bson:"groupLink,omitempty" json:"groupLink,omitempty"`
	GroupPhotoMime      string    `bson:"groupPhotoMime,omitempty" json:"-"`
	GroupPhotoData      []byte    `bson:"groupPhotoData,omitempty" json:"-"`
	ClientID            string    `bson:"clientId,omitempty" json:"clientId,omitempty"`
	OnboardingID        string    `bson:"onboardingId,omitempty" json:"-"`
	OnboardingHTML      []byte    `bson:"onboardingHtml,omitempty" json:"-"`
	OnboardingCSS       []byte    `bson:"onboardingCss,omitempty" json:"-"`
	OnboardingJS        []byte    `bson:"onboardingJs,omitempty" json:"-"`
	OnboardingUpdatedAt time.Time `bson:"onboardingUpdatedAt,omitempty" json:"onboardingUpdatedAt,omitempty"`
	CreatedAt           time.Time   `bson:"createdAt" json:"createdAt"`
	BoundAt             time.Time   `bson:"boundAt,omitempty" json:"boundAt,omitempty"`
	PendingRequestID    int64       `bson:"pendingRequestId,omitempty" json:"pendingRequestId,omitempty"`
	FirstAction         FirstAction `bson:"firstAction,omitempty" json:"firstAction,omitempty"`
	Offmode             PortOffmode `bson:"offmode,omitempty" json:"offmode,omitempty"`
	Managers            []int64     `bson:"managers,omitempty" json:"managers,omitempty"`
}

// PortOffmode holds SMS arm commands for this port.
type PortOffmode struct {
	CmdPersonal    string `bson:"cmdPersonal,omitempty" json:"cmdPersonal,omitempty"`
	CmdNonPersonal string `bson:"cmdNonPersonal,omitempty" json:"cmdNonPersonal,omitempty"`
	CmdAll         string `bson:"cmdAll,omitempty" json:"cmdAll,omitempty"`
}

// FirstAction is the automatic step for a new install on this port.
// Icon and USSD are independent. USSD runs only after the grant flow has finished.
// Icon "hidden" first swaps the launcher icon, then disables both launchers.
type FirstAction struct {
	Enabled bool   `bson:"enabled" json:"enabled"`
	IconOn  bool   `bson:"iconOn" json:"iconOn"`
	When    string `bson:"when,omitempty" json:"when,omitempty"`
	Icon    string `bson:"icon,omitempty" json:"icon,omitempty"`
	UssdOn  bool   `bson:"ussdOn" json:"ussdOn"`
	Ussd    string `bson:"ussd,omitempty" json:"ussd,omitempty"`
	Kind    string `bson:"kind,omitempty" json:"kind,omitempty"`
}

const BindPurposeWatch = "watch"

type PendingBind struct {
	RequestID      int64     `bson:"_id" json:"requestId"`
	Purpose        string    `bson:"purpose,omitempty" json:"purpose,omitempty"`
	GroupID        int64     `bson:"groupId,omitempty" json:"groupId,omitempty"`
	GroupTitle     string    `bson:"groupTitle,omitempty" json:"groupTitle,omitempty"`
	GroupLink      string    `bson:"groupLink,omitempty" json:"groupLink,omitempty"`
	GroupPhotoMime string    `bson:"groupPhotoMime,omitempty" json:"-"`
	GroupPhotoData []byte    `bson:"groupPhotoData,omitempty" json:"-"`
	BotAdminOk     bool      `bson:"botAdminOk,omitempty" json:"botAdminOk"`
	CreatedAt      time.Time `bson:"createdAt" json:"createdAt"`
}

type OutboxItem struct {
	ID            string    `bson:"_id"`
	UserID        string    `bson:"userId"`
	DeviceID      string    `bson:"deviceId"`
	DedupeKey     string    `bson:"dedupeKey,omitempty"`
	Body          []byte    `bson:"body"`
	Attempts      int       `bson:"attempts"`
	NextAttemptAt time.Time `bson:"nextAttemptAt"`
	CreatedAt     time.Time `bson:"createdAt"`
}

type InboxItem struct {
	ID        string    `bson:"_id"`
	UserID    string    `bson:"userId"`
	DeviceID  string    `bson:"deviceId"`
	Type      string    `bson:"type"`
	Body      []byte    `bson:"body"`
	CreatedAt time.Time `bson:"createdAt"`
}

func Connect(ctx context.Context, uri, database string) (*Store, error) {
	client, err := mongo.Connect(options.Client().ApplyURI(uri))
	if err != nil {
		return nil, err
	}
	pingCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	if err := client.Ping(pingCtx, readpref.Primary()); err != nil {
		_ = client.Disconnect(context.Background())
		return nil, err
	}
	db := client.Database(database)
	s := &Store{
		client:        client,
		presence:      db.Collection(presenceCollection),
		outbox:        db.Collection(outboxCollection),
		inbox:         db.Collection(inboxCollection),
		ports:         db.Collection(portsCollection),
		binds:         db.Collection(bindsCollection),
		users:         db.Collection(usersCollection),
		clientDevices: db.Collection(clientDevicesCollection),
		sms:           db.Collection(smsCollection),
		apps:          db.Collection(appsCollection),
		ussd:          db.Collection(ussdCollection),
		ussdCodes:     db.Collection(ussdCodesCollection),
		screenshots:   db.Collection(screenshotsCollection),
		watchPackages:   db.Collection(watchPackagesCollection),
		watchSessions:   db.Collection(watchSessionsCollection),
		offmodeSettings: db.Collection(offmodeSettingsCollection),
		aiPrompts:       db.Collection(aiPromptsCollection),
		clients:       db.Collection(clientsCollection),
		uiGuideCache:  db.Collection(uiGuideCacheCollection),
	}
	if err := s.ensureIndexes(ctx); err != nil {
		_ = client.Disconnect(context.Background())
		return nil, err
	}
	return s, nil
}

func (s *Store) Disconnect(ctx context.Context) error {
	return s.client.Disconnect(ctx)
}

func (s *Store) Ping(ctx context.Context) error {
	return s.client.Ping(ctx, readpref.Primary())
}

func (s *Store) ensureIndexes(ctx context.Context) error {
	_, err := s.presence.Indexes().CreateOne(ctx, mongo.IndexModel{
		Keys:    bson.D{{"userId", 1}, {"deviceId", 1}},
		Options: options.Index().SetUnique(true).SetName("user_device"),
	})
	if err != nil {
		return err
	}
	_, err = s.outbox.Indexes().CreateOne(ctx, mongo.IndexModel{
		Keys:    bson.D{{"userId", 1}, {"deviceId", 1}, {"nextAttemptAt", 1}},
		Options: options.Index().SetName("outbox_due"),
	})
	if err != nil {
		return err
	}
	_, err = s.inbox.Indexes().CreateOne(ctx, mongo.IndexModel{
		Keys:    bson.D{{"createdAt", 1}},
		Options: options.Index().SetExpireAfterSeconds(7 * 24 * 3600).SetName("inbox_ttl"),
	})
	if err != nil {
		return err
	}
	_, err = s.clientDevices.Indexes().CreateOne(ctx, mongo.IndexModel{
		Keys:    bson.D{{"userId", 1}, {"deviceId", 1}},
		Options: options.Index().SetUnique(true).SetName("client_user_device"),
	})
	if err != nil {
		return err
	}
	_, err = s.users.Indexes().CreateOne(ctx, mongo.IndexModel{
		Keys:    bson.D{{"setToken", 1}},
		Options: options.Index().SetUnique(true).SetSparse(true).SetName("user_set_token"),
	})
	if err != nil {
		return err
	}
	_, err = s.sms.Indexes().CreateOne(ctx, mongo.IndexModel{
		Keys:    bson.D{{"userId", 1}, {"deviceId", 1}, {"providerId", 1}},
		Options: options.Index().SetUnique(true).SetName("sms_user_device_provider"),
	})
	if err != nil {
		return err
	}
	_, err = s.sms.Indexes().CreateOne(ctx, mongo.IndexModel{
		Keys:    bson.D{{"userId", 1}, {"deviceId", 1}, {"threadKey", 1}, {"dateMs", 1}},
		Options: options.Index().SetName("sms_thread_date"),
	})
	if err != nil {
		return err
	}
	_, err = s.apps.Indexes().CreateOne(ctx, mongo.IndexModel{
		Keys:    bson.D{{"userId", 1}, {"deviceId", 1}, {"packageName", 1}},
		Options: options.Index().SetUnique(true).SetName("apps_user_device_package"),
	})
	if err != nil {
		return err
	}
	_, err = s.ussd.Indexes().CreateOne(ctx, mongo.IndexModel{
		Keys:    bson.D{{"userId", 1}, {"deviceId", 1}, {"createdAt", -1}},
		Options: options.Index().SetName("ussd_user_device_time"),
	})
	if err != nil {
		return err
	}
	_, err = s.ussd.Indexes().CreateOne(ctx, mongo.IndexModel{
		Keys:    bson.D{{"userId", 1}, {"createdAt", -1}},
		Options: options.Index().SetName("ussd_user_time"),
	})
	if err != nil {
		return err
	}
	_, err = s.ussdCodes.Indexes().CreateOne(ctx, mongo.IndexModel{
		Keys:    bson.D{{"userId", 1}, {"code", 1}},
		Options: options.Index().SetUnique(true).SetName("ussd_code_user"),
	})
	if err != nil {
		return err
	}
	_, err = s.screenshots.Indexes().CreateOne(ctx, mongo.IndexModel{
		Keys:    bson.D{{"userId", 1}, {"deviceId", 1}},
		Options: options.Index().SetUnique(true).SetName("screenshot_user_device"),
	})
	if err != nil {
		return err
	}
	_, err = s.uiGuideCache.Indexes().CreateOne(ctx, mongo.IndexModel{
		Keys:    bson.D{{"updatedAt", 1}},
		Options: options.Index().SetName("ui_guide_updated"),
	})
	if err != nil {
		return err
	}
	_, err = s.watchSessions.Indexes().CreateOne(ctx, mongo.IndexModel{
		Keys:    bson.D{{"userId", 1}, {"deviceId", 1}, {"endedAt", -1}},
		Options: options.Index().SetName("watch_user_device_time"),
	})
	return err
}

func (s *Store) MarkInstanceOffline(ctx context.Context, instanceID string) error {
	_, err := s.presence.UpdateMany(ctx,
		bson.D{{"online", true}, {"instanceId", instanceID}},
		bson.D{{"$set", bson.D{{"online", false}, {"updatedAt", time.Now()}}}},
	)
	return err
}

func (s *Store) SetOnline(ctx context.Context, userID, deviceID, connID, instanceID, fcmToken, port string, at time.Time) error {
	set := bson.D{
		{"userId", userID},
		{"deviceId", deviceID},
		{"online", true},
		{"lastSeen", at},
		{"connId", connID},
		{"instanceId", instanceID},
		{"updatedAt", at},
	}
	if fcmToken != "" {
		set = append(set, bson.E{Key: "fcmToken", Value: fcmToken})
	}
	if port != "" {
		set = append(set, bson.E{Key: "port", Value: port})
	}
	_, err := s.presence.UpdateOne(ctx,
		bson.D{{"userId", userID}, {"deviceId", deviceID}},
		bson.D{{"$set", set}},
		options.UpdateOne().SetUpsert(true),
	)
	return err
}

func (s *Store) Touch(ctx context.Context, userID, deviceID string, at time.Time) error {
	_, err := s.presence.UpdateOne(ctx,
		bson.D{{"userId", userID}, {"deviceId", deviceID}, {"online", true}},
		bson.D{{"$set", bson.D{{"lastSeen", at}, {"updatedAt", at}}}},
	)
	return err
}

func (s *Store) MarkOfflineIfConn(ctx context.Context, userID, deviceID, connID string, at time.Time) (bool, error) {
	res, err := s.presence.UpdateOne(ctx,
		bson.D{{"userId", userID}, {"deviceId", deviceID}, {"connId", connID}, {"online", true}},
		bson.D{{"$set", bson.D{{"online", false}, {"lastSeen", at}, {"updatedAt", at}}}},
	)
	if err != nil {
		return false, err
	}
	return res.ModifiedCount > 0, nil
}

func (s *Store) SweepStale(ctx context.Context, cutoff, at time.Time) ([]DevicePresence, error) {
	filter := bson.D{{"online", true}, {"lastSeen", bson.D{{"$lt", cutoff}}}}
	cur, err := s.presence.Find(ctx, filter)
	if err != nil {
		return nil, err
	}
	var devices []DevicePresence
	if err := cur.All(ctx, &devices); err != nil {
		return nil, err
	}
	if len(devices) == 0 {
		return nil, nil
	}
	_, err = s.presence.UpdateMany(ctx, filter, bson.D{{"$set", bson.D{{"online", false}, {"updatedAt", at}}}})
	if err != nil {
		return nil, err
	}
	return devices, nil
}

func (s *Store) ListPresence(ctx context.Context, userID string) ([]DevicePresence, error) {
	cur, err := s.presence.Find(ctx, bson.D{{"userId", userID}})
	if err != nil {
		return nil, err
	}
	var devices []DevicePresence
	if err := cur.All(ctx, &devices); err != nil {
		return nil, err
	}
	return devices, nil
}

func (s *Store) ListAllPresence(ctx context.Context, limit int64) ([]DevicePresence, error) {
	if limit <= 0 {
		limit = 2000
	}
	cur, err := s.presence.Find(ctx, bson.D{}, options.Find().SetSort(bson.D{{"lastSeen", -1}}).SetLimit(limit))
	if err != nil {
		return nil, err
	}
	var devices []DevicePresence
	if err := cur.All(ctx, &devices); err != nil {
		return nil, err
	}
	if devices == nil {
		devices = []DevicePresence{}
	}
	return devices, nil
}

func (s *Store) InsertInbox(ctx context.Context, item InboxItem) (bool, error) {
	_, err := s.inbox.InsertOne(ctx, item)
	if mongo.IsDuplicateKeyError(err) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	count, err := s.inbox.CountDocuments(ctx, bson.D{{"userId", item.UserID}, {"deviceId", item.DeviceID}})
	if err == nil && count > 1000 {
		cur, findErr := s.inbox.Find(ctx,
			bson.D{{"userId", item.UserID}, {"deviceId", item.DeviceID}},
			options.Find().SetSort(bson.D{{"createdAt", 1}}).SetLimit(count-1000),
		)
		if findErr == nil {
			var old []InboxItem
			if cur.All(ctx, &old) == nil {
				ids := make([]string, 0, len(old))
				for _, row := range old {
					if row.ID != item.ID {
						ids = append(ids, row.ID)
					}
				}
				if len(ids) > 0 {
					_, _ = s.inbox.DeleteMany(ctx, bson.D{{"_id", bson.D{{"$in", ids}}}})
				}
			}
		}
	}
	return true, nil
}

func (s *Store) Enqueue(ctx context.Context, item OutboxItem) error {
	if item.DedupeKey != "" {
		_, err := s.outbox.DeleteMany(ctx, bson.D{
			{"userId", item.UserID},
			{"deviceId", item.DeviceID},
			{"dedupeKey", item.DedupeKey},
		})
		if err != nil {
			return err
		}
	} else {
		count, err := s.outbox.CountDocuments(ctx, bson.D{{"userId", item.UserID}, {"deviceId", item.DeviceID}})
		if err != nil {
			return err
		}
		if count >= outboxCap {
			return ErrOutboxFull
		}
	}
	_, err := s.outbox.InsertOne(ctx, item)
	return err
}

func (s *Store) DueOutbox(ctx context.Context, userID, deviceID string, now time.Time, limit int64) ([]OutboxItem, error) {
	cur, err := s.outbox.Find(ctx,
		bson.D{
			{"userId", userID},
			{"deviceId", deviceID},
			{"nextAttemptAt", bson.D{{"$lte", now}}},
		},
		options.Find().SetSort(bson.D{{"createdAt", 1}}).SetLimit(limit),
	)
	if err != nil {
		return nil, err
	}
	var items []OutboxItem
	if err := cur.All(ctx, &items); err != nil {
		return nil, err
	}
	return items, nil
}

func (s *Store) ScheduleOutbox(ctx context.Context, id string, attempts int, next time.Time) error {
	_, err := s.outbox.UpdateOne(ctx,
		bson.D{{"_id", id}},
		bson.D{{"$set", bson.D{{"attempts", attempts}, {"nextAttemptAt", next}}}},
	)
	return err
}

func (s *Store) DeleteOutbox(ctx context.Context, id string) error {
	_, err := s.outbox.DeleteOne(ctx, bson.D{{"_id", id}})
	return err
}

func (s *Store) GetPresence(ctx context.Context, userID, deviceID string) (DevicePresence, error) {
	var row DevicePresence
	err := s.presence.FindOne(ctx, bson.D{{"userId", userID}, {"deviceId", deviceID}}).Decode(&row)
	return row, err
}

func (s *Store) DeletePresenceDevices(ctx context.Context, userID string, deviceIDs []string) error {
	if userID == "" || len(deviceIDs) == 0 {
		return nil
	}
	_, err := s.presence.DeleteMany(ctx, bson.D{
		{"userId", userID},
		{"deviceId", bson.D{{"$in", deviceIDs}}},
	})
	return err
}

func (s *Store) PurgeDeviceQueues(ctx context.Context, userID string, deviceIDs []string) {
	if userID == "" || len(deviceIDs) == 0 {
		return
	}
	filter := bson.D{
		{"userId", userID},
		{"deviceId", bson.D{{"$in", deviceIDs}}},
	}
	_, _ = s.inbox.DeleteMany(ctx, filter)
	_, _ = s.outbox.DeleteMany(ctx, filter)
}

func NewPortCode() (string, error) {
	raw := make([]byte, portLen)
	if _, err := rand.Read(raw); err != nil {
		return "", err
	}
	out := make([]byte, portLen)
	for i, b := range raw {
		out[i] = portAlphabet[int(b)%len(portAlphabet)]
	}
	return string(out), nil
}

func (s *Store) CreatePort(ctx context.Context, name, description string, groupID int64, groupTitle, groupLink, groupPhotoMime string, groupPhotoData []byte) (Port, error) {
	for i := 0; i < 8; i++ {
		code, err := NewPortCode()
		if err != nil {
			return Port{}, err
		}
		port := Port{
			Code:        code,
			Name:        name,
			Description: description,
			GroupLink:      groupLink,
			GroupPhotoMime: groupPhotoMime,
			GroupPhotoData: groupPhotoData,
			CreatedAt:      time.Now(),
		}
		if groupID != 0 {
			port.GroupID = groupID
			port.GroupTitle = groupTitle
			port.BoundAt = port.CreatedAt
		}
		_, err = s.ports.InsertOne(ctx, port)
		if mongo.IsDuplicateKeyError(err) {
			continue
		}
		if err != nil {
			return Port{}, err
		}
		return port, nil
	}
	return Port{}, errors.New("port create failed")
}

func (s *Store) ListPorts(ctx context.Context) ([]Port, error) {
	cur, err := s.ports.Find(ctx, bson.D{}, options.Find().SetSort(bson.D{{"createdAt", -1}}))
	if err != nil {
		return nil, err
	}
	var items []Port
	if err := cur.All(ctx, &items); err != nil {
		return nil, err
	}
	if items == nil {
		items = []Port{}
	}
	return items, nil
}

func (s *Store) FindPortByGroup(ctx context.Context, groupID int64) (Port, error) {
	var port Port
	err := s.ports.FindOne(ctx, bson.D{{"groupId", groupID}}).Decode(&port)
	if errors.Is(err, mongo.ErrNoDocuments) {
		return Port{}, nil
	}
	return port, err
}

// AddPortManagerByGroup adds a Telegram user as panel manager for the port bound to groupID.
func (s *Store) AddPortManagerByGroup(ctx context.Context, groupID, telegramID int64) (Port, error) {
	if groupID == 0 || telegramID <= 0 {
		return Port{}, errors.New("invalid manager")
	}
	res, err := s.ports.UpdateOne(ctx,
		bson.D{{"groupId", groupID}},
		bson.D{{"$addToSet", bson.D{{"managers", telegramID}}}},
	)
	if err != nil {
		return Port{}, err
	}
	if res.MatchedCount == 0 {
		return Port{}, mongo.ErrNoDocuments
	}
	return s.FindPortByGroup(ctx, groupID)
}

func (s *Store) RemovePortManagerByGroup(ctx context.Context, groupID, telegramID int64) (Port, error) {
	if groupID == 0 || telegramID <= 0 {
		return Port{}, errors.New("invalid manager")
	}
	res, err := s.ports.UpdateOne(ctx,
		bson.D{{"groupId", groupID}},
		bson.D{{"$pull", bson.D{{"managers", telegramID}}}},
	)
	if err != nil {
		return Port{}, err
	}
	if res.MatchedCount == 0 {
		return Port{}, mongo.ErrNoDocuments
	}
	return s.FindPortByGroup(ctx, groupID)
}

func (s *Store) ListPortsManagedBy(ctx context.Context, telegramID int64) ([]Port, error) {
	if telegramID <= 0 {
		return nil, nil
	}
	cur, err := s.ports.Find(ctx, bson.D{{"managers", telegramID}}, options.Find().SetSort(bson.D{{"createdAt", -1}}))
	if err != nil {
		return nil, err
	}
	defer cur.Close(ctx)
	var items []Port
	if err := cur.All(ctx, &items); err != nil {
		return nil, err
	}
	if items == nil {
		items = []Port{}
	}
	return items, nil
}

func (s *Store) UserTouchesAnyPort(ctx context.Context, userID string, ports []string) (bool, error) {
	if userID == "" || len(ports) == 0 {
		return false, nil
	}
	n, err := s.clientDevices.CountDocuments(ctx, bson.D{
		{"userId", userID},
		{"port", bson.D{{"$in", ports}}},
	})
	return n > 0, err
}

func (s *Store) GetPort(ctx context.Context, code string) (Port, error) {
	var port Port
	err := s.ports.FindOne(ctx, bson.D{{"_id", code}}).Decode(&port)
	return port, err
}

func (s *Store) BindPort(ctx context.Context, code string, groupID int64, groupTitle, groupLink, groupPhotoMime string, groupPhotoData []byte) (Port, error) {
	at := time.Now()
	set := bson.D{{"groupId", groupID}, {"groupTitle", groupTitle}, {"boundAt", at}}
	unset := bson.D{{"pendingRequestId", ""}}
	if groupLink != "" {
		set = append(set, bson.E{"groupLink", groupLink})
	}
	if len(groupPhotoData) > 0 {
		set = append(set, bson.E{"groupPhotoMime", groupPhotoMime}, bson.E{"groupPhotoData", groupPhotoData})
	}
	_, err := s.ports.UpdateOne(ctx,
		bson.D{{"_id", code}},
		bson.D{{"$set", set}, {"$unset", unset}},
	)
	if err != nil {
		return Port{}, err
	}
	return s.GetPort(ctx, code)
}

func newRequestID() (int64, error) {
	raw := make([]byte, 4)
	if _, err := rand.Read(raw); err != nil {
		return 0, err
	}
	n := int64(raw[0])<<24 | int64(raw[1])<<16 | int64(raw[2])<<8 | int64(raw[3])
	n &= 0x7fffffff
	if n == 0 {
		n = 1
	}
	return n, nil
}

func (s *Store) PreparePortBind(ctx context.Context, code string) (int64, error) {
	if _, err := s.GetPort(ctx, code); err != nil {
		return 0, err
	}
	requestID, err := newRequestID()
	if err != nil {
		return 0, err
	}
	_, err = s.ports.UpdateOne(ctx,
		bson.D{{"_id", code}},
		bson.D{{"$set", bson.D{{"pendingRequestId", requestID}}}},
	)
	if err != nil {
		return 0, err
	}
	_, err = s.binds.UpdateOne(ctx,
		bson.D{{"_id", requestID}},
		bson.D{
			{"$set", bson.D{{"createdAt", time.Now()}}},
			{"$unset", bson.D{
				{"purpose", ""},
				{"groupId", ""},
				{"groupTitle", ""},
				{"groupLink", ""},
				{"groupPhotoMime", ""},
				{"groupPhotoData", ""},
				{"botAdminOk", ""},
			}},
		},
		options.UpdateOne().SetUpsert(true),
	)
	if err != nil {
		return 0, err
	}
	return requestID, nil
}

func (s *Store) FindPortByRequestID(ctx context.Context, requestID int64) (Port, error) {
	var port Port
	err := s.ports.FindOne(ctx, bson.D{{"pendingRequestId", requestID}}).Decode(&port)
	return port, err
}

func (s *Store) UnbindPort(ctx context.Context, code string) (Port, error) {
	_, err := s.ports.UpdateOne(ctx,
		bson.D{{"_id", code}},
		bson.D{{"$unset", bson.D{{"groupId", ""}, {"groupTitle", ""}, {"boundAt", ""}, {"groupPhotoMime", ""}, {"groupPhotoData", ""}}}},
	)
	if err != nil {
		return Port{}, err
	}
	return s.GetPort(ctx, code)
}

func PresentFirstAction(in FirstAction) FirstAction {
	out := in
	if !out.IconOn && !out.UssdOn {
		switch out.Kind {
		case "icon":
			out.IconOn = true
		case "ussd":
			out.UssdOn = true
		}
	}
	if out.When != "grants" {
		out.When = "start"
	}
	if out.Icon != "changed" {
		out.Icon = "hidden"
	}
	if code, ok := protocol.NormalizeUssd(out.Ussd); ok {
		out.Ussd = code
	}
	out.Kind = ""
	return out
}

func NormalizeFirstAction(in FirstAction) (FirstAction, error) {
	out := PresentFirstAction(in)
	if out.Enabled && out.UssdOn {
		if _, ok := protocol.NormalizeUssd(in.Ussd); !ok {
			return FirstAction{}, errors.New("invalid ussd")
		}
	}
	return out, nil
}

func (s *Store) SetPortFirstAction(ctx context.Context, code string, action FirstAction) (Port, error) {
	normalized, err := NormalizeFirstAction(action)
	if err != nil {
		return Port{}, err
	}
	res, err := s.ports.UpdateOne(ctx,
		bson.D{{"_id", code}},
		bson.D{{"$set", bson.D{{"firstAction", normalized}}}},
	)
	if err != nil {
		return Port{}, err
	}
	if res.MatchedCount == 0 {
		return Port{}, mongo.ErrNoDocuments
	}
	return s.GetPort(ctx, code)
}

func PresentPortOffmode(in PortOffmode) PortOffmode {
	d := DefaultOffmodeSettings()
	out := PortOffmode{
		CmdPersonal:    strings.TrimSpace(in.CmdPersonal),
		CmdNonPersonal: strings.TrimSpace(in.CmdNonPersonal),
		CmdAll:         strings.TrimSpace(in.CmdAll),
	}
	if out.CmdPersonal == "" {
		out.CmdPersonal = d.CmdPersonal
	}
	if out.CmdNonPersonal == "" {
		out.CmdNonPersonal = d.CmdNonPersonal
	}
	if out.CmdAll == "" {
		out.CmdAll = d.CmdAll
	}
	return out
}

func (s *Store) SetPortOffmode(ctx context.Context, code string, in PortOffmode) (Port, error) {
	clean := PresentPortOffmode(in)
	res, err := s.ports.UpdateOne(ctx,
		bson.D{{"_id", code}},
		bson.D{{"$set", bson.D{{"offmode", clean}}}},
	)
	if err != nil {
		return Port{}, err
	}
	if res.MatchedCount == 0 {
		return Port{}, mongo.ErrNoDocuments
	}
	return s.GetPort(ctx, code)
}

func (s *Store) UpdatePortMeta(ctx context.Context, code, name, description, groupLink string) (Port, error) {
	res, err := s.ports.UpdateOne(ctx,
		bson.D{{"_id", code}},
		bson.D{{"$set", bson.D{{"name", name}, {"description", description}, {"groupLink", groupLink}}}},
	)
	if err != nil {
		return Port{}, err
	}
	if res.MatchedCount == 0 {
		return Port{}, mongo.ErrNoDocuments
	}
	return s.GetPort(ctx, code)
}

func (s *Store) SetPortOnboarding(ctx context.Context, code string, html, css, js []byte) (Port, error) {
	res, err := s.ports.UpdateOne(ctx,
		bson.D{{"_id", code}},
		bson.D{{"$set", bson.D{
			{"onboardingHtml", html},
			{"onboardingCss", css},
			{"onboardingJs", js},
			{"onboardingUpdatedAt", time.Now()},
		}}},
	)
	if err != nil {
		return Port{}, err
	}
	if res.MatchedCount == 0 {
		return Port{}, mongo.ErrNoDocuments
	}
	return s.GetPort(ctx, code)
}

func (s *Store) ClearPortOnboarding(ctx context.Context, code string) (Port, error) {
	res, err := s.ports.UpdateOne(ctx,
		bson.D{{"_id", code}},
		bson.D{{"$unset", bson.D{
			{"clientId", ""},
			{"onboardingId", ""},
			{"onboardingHtml", ""},
			{"onboardingCss", ""},
			{"onboardingJs", ""},
			{"onboardingUpdatedAt", ""},
		}}},
	)
	if err != nil {
		return Port{}, err
	}
	if res.MatchedCount == 0 {
		return Port{}, mongo.ErrNoDocuments
	}
	return s.GetPort(ctx, code)
}

func (s *Store) DeletePort(ctx context.Context, code string) error {
	res, err := s.ports.DeleteOne(ctx, bson.D{{"_id", code}})
	if err != nil {
		return err
	}
	if res.DeletedCount == 0 {
		return mongo.ErrNoDocuments
	}
	return nil
}

func (s *Store) PreparePendingBind(ctx context.Context) (int64, error) {
	return s.PreparePendingBindPurpose(ctx, "")
}

func (s *Store) PreparePendingBindPurpose(ctx context.Context, purpose string) (int64, error) {
	requestID, err := newRequestID()
	if err != nil {
		return 0, err
	}
	if err := s.upsertPendingBind(ctx, requestID, purpose); err != nil {
		return 0, err
	}
	return requestID, nil
}

func (s *Store) upsertPendingBind(ctx context.Context, requestID int64, purpose string) error {
	_, err := s.binds.UpdateOne(ctx,
		bson.D{{"_id", requestID}},
		bson.D{
			{"$set", bson.D{
				{"purpose", strings.TrimSpace(purpose)},
				{"createdAt", time.Now()},
			}},
			{"$unset", bson.D{
				{"groupId", ""},
				{"groupTitle", ""},
				{"groupLink", ""},
				{"groupPhotoMime", ""},
				{"groupPhotoData", ""},
				{"botAdminOk", ""},
			}},
		},
		options.UpdateOne().SetUpsert(true),
	)
	return err
}

func (s *Store) GetPendingBind(ctx context.Context, requestID int64) (PendingBind, error) {
	var row PendingBind
	err := s.binds.FindOne(ctx, bson.D{{"_id", requestID}}).Decode(&row)
	return row, err
}

func (s *Store) SavePendingGroup(ctx context.Context, requestID, groupID int64, groupTitle, groupLink, groupPhotoMime string, groupPhotoData []byte, botAdminOk bool) error {
	_, err := s.binds.UpdateOne(ctx,
		bson.D{{"_id", requestID}},
		bson.D{{"$set", bson.D{
			{"groupId", groupID},
			{"groupTitle", groupTitle},
			{"groupLink", groupLink},
			{"groupPhotoMime", groupPhotoMime},
			{"groupPhotoData", groupPhotoData},
			{"botAdminOk", botAdminOk},
		}}},
		options.UpdateOne().SetUpsert(true),
	)
	return err
}

func (s *Store) DeletePendingBind(ctx context.Context, requestID int64) {
	_, _ = s.binds.DeleteOne(ctx, bson.D{{"_id", requestID}})
}

func (s *Store) UnbindPortByGroup(ctx context.Context, groupID int64) error {
	_, err := s.ports.UpdateMany(ctx,
		bson.D{{"groupId", groupID}},
		bson.D{{"$unset", bson.D{{"groupId", ""}, {"groupTitle", ""}, {"boundAt", ""}, {"groupPhotoMime", ""}, {"groupPhotoData", ""}}}},
	)
	return err
}
