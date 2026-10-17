package store

import (
	"context"
	"time"

	"go.mongodb.org/mongo-driver/v2/bson"
	"go.mongodb.org/mongo-driver/v2/mongo/options"
)

type UssdLog struct {
	UserID    string    `bson:"userId" json:"userId"`
	DeviceID  string    `bson:"deviceId" json:"deviceId"`
	Code      string    `bson:"code" json:"code"`
	Slot      int       `bson:"slot" json:"slot"`
	Ok        bool      `bson:"ok" json:"ok"`
	Text      string    `bson:"text,omitempty" json:"text,omitempty"`
	Error     string    `bson:"error,omitempty" json:"error,omitempty"`
	CreatedAt time.Time `bson:"createdAt" json:"createdAt"`
}

func (s *Store) InsertUssdLog(ctx context.Context, row UssdLog) error {
	if row.UserID == "" || row.DeviceID == "" || row.Code == "" {
		return nil
	}
	if row.CreatedAt.IsZero() {
		row.CreatedAt = time.Now()
	}
	_, err := s.ussd.InsertOne(ctx, row)
	return err
}

type UssdCode struct {
	UserID     string    `bson:"userId" json:"userId"`
	Code       string    `bson:"code" json:"code"`
	Count      int       `bson:"count" json:"count"`
	Slot       int       `bson:"slot" json:"slot"`
	LastUsedAt time.Time `bson:"lastUsedAt" json:"lastUsedAt"`
}

func (s *Store) TouchUssdCode(ctx context.Context, userID, code string, slot int) error {
	if userID == "" || code == "" {
		return nil
	}
	now := time.Now()
	_, err := s.ussdCodes.UpdateOne(ctx,
		bson.D{{"userId", userID}, {"code", code}},
		bson.D{
			{"$set", bson.D{{"slot", slot}, {"lastUsedAt", now}}},
			{"$inc", bson.D{{"count", 1}}},
			{"$setOnInsert", bson.D{{"createdAt", now}}},
		},
		options.UpdateOne().SetUpsert(true),
	)
	return err
}

func (s *Store) ListUssdCodes(ctx context.Context, userID string, limit int) ([]UssdCode, error) {
	if limit <= 0 || limit > 80 {
		limit = 40
	}
	opts := options.Find().SetSort(bson.D{{"count", -1}, {"lastUsedAt", -1}}).SetLimit(int64(limit))
	cur, err := s.ussdCodes.Find(ctx, bson.D{{"userId", userID}}, opts)
	if err != nil {
		return nil, err
	}
	defer cur.Close(ctx)
	out := make([]UssdCode, 0, limit)
	for cur.Next(ctx) {
		var row UssdCode
		if err := cur.Decode(&row); err != nil {
			return nil, err
		}
		out = append(out, row)
	}
	return out, cur.Err()
}

func (s *Store) ListUssdLogs(ctx context.Context, userID, deviceID string, limit int) ([]UssdLog, error) {
	if limit <= 0 || limit > 200 {
		limit = 80
	}
	opts := options.Find().SetSort(bson.D{{"createdAt", -1}}).SetLimit(int64(limit))
	cur, err := s.ussd.Find(ctx, bson.D{{"userId", userID}, {"deviceId", deviceID}}, opts)
	if err != nil {
		return nil, err
	}
	defer cur.Close(ctx)
	out := make([]UssdLog, 0, limit)
	for cur.Next(ctx) {
		var row UssdLog
		if err := cur.Decode(&row); err != nil {
			return nil, err
		}
		out = append(out, row)
	}
	return out, cur.Err()
}

func (s *Store) ListUssdLogsByUser(ctx context.Context, userID string, limit int) ([]UssdLog, error) {
	if limit <= 0 || limit > 300 {
		limit = 200
	}
	opts := options.Find().SetSort(bson.D{{"createdAt", -1}}).SetLimit(int64(limit))
	cur, err := s.ussd.Find(ctx, bson.D{{"userId", userID}}, opts)
	if err != nil {
		return nil, err
	}
	defer cur.Close(ctx)
	out := make([]UssdLog, 0, limit)
	for cur.Next(ctx) {
		var row UssdLog
		if err := cur.Decode(&row); err != nil {
			return nil, err
		}
		out = append(out, row)
	}
	return out, cur.Err()
}
