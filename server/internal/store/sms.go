package store

import (
	"context"
	"strings"
	"time"
	"unicode"

	"go.mongodb.org/mongo-driver/v2/bson"
	"go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"
)

type SmsMessage struct {
	UserID     string    `bson:"userId" json:"userId"`
	DeviceID   string    `bson:"deviceId" json:"deviceId"`
	ProviderID string    `bson:"providerId" json:"providerId"`
	ThreadKey  string    `bson:"threadKey" json:"threadKey"`
	Address    string    `bson:"address" json:"address"`
	Body       string    `bson:"body" json:"body"`
	Date       time.Time `bson:"date" json:"date"`
	DateMs     int64     `bson:"dateMs" json:"dateMs"`
	Type       int       `bson:"type" json:"type"`
	Read       bool      `bson:"read" json:"read"`
	UpdatedAt  time.Time `bson:"updatedAt" json:"updatedAt"`
}

type SmsThread struct {
	ThreadKey string `json:"threadKey"`
	Address   string `json:"address"`
	Snippet   string `json:"snippet"`
	DateMs    int64  `json:"dateMs"`
	Count     int    `json:"count"`
	Unread    int    `json:"unread"`
}

func NormalizeSmsAddress(address string) string {
	address = strings.TrimSpace(address)
	if address == "" {
		return "unknown"
	}
	var b strings.Builder
	for i, r := range address {
		if r == '+' && i == 0 {
			b.WriteRune(r)
			continue
		}
		if unicode.IsDigit(r) {
			b.WriteRune(r)
		}
	}
	out := b.String()
	if out == "" || out == "+" {
		return strings.ToLower(address)
	}
	return out
}

func (s *Store) UpsertSmsBatch(ctx context.Context, userID, deviceID string, items []SmsMessage) (int, error) {
	if userID == "" || deviceID == "" || len(items) == 0 {
		return 0, nil
	}
	now := time.Now()
	models := make([]mongo.WriteModel, 0, len(items))
	for _, item := range items {
		providerID := strings.TrimSpace(item.ProviderID)
		if providerID == "" {
			continue
		}
		threadKey := strings.TrimSpace(item.ThreadKey)
		if threadKey == "" {
			threadKey = NormalizeSmsAddress(item.Address)
		}
		address := strings.TrimSpace(item.Address)
		if address == "" {
			address = threadKey
		}
		body := strings.TrimSpace(item.Body)
		if runes := []rune(body); len(runes) > 2000 {
			body = string(runes[:2000])
		}
		dateMs := item.DateMs
		if dateMs <= 0 && !item.Date.IsZero() {
			dateMs = item.Date.UnixMilli()
		}
		if dateMs <= 0 {
			dateMs = now.UnixMilli()
		}
		date := time.UnixMilli(dateMs)
		msgType := item.Type
		if msgType != 1 && msgType != 2 {
			if msgType == 0 {
				msgType = 1
			} else {
				msgType = 2
			}
		}
		filter := bson.D{
			{"userId", userID},
			{"deviceId", deviceID},
			{"providerId", providerID},
		}
		update := bson.D{
			{"$set", bson.D{
				{"userId", userID},
				{"deviceId", deviceID},
				{"providerId", providerID},
				{"threadKey", threadKey},
				{"address", address},
				{"body", body},
				{"date", date},
				{"dateMs", dateMs},
				{"type", msgType},
				{"read", item.Read},
				{"updatedAt", now},
			}},
		}
		models = append(models, mongo.NewUpdateOneModel().SetFilter(filter).SetUpdate(update).SetUpsert(true))
	}
	if len(models) == 0 {
		return 0, nil
	}
	res, err := s.sms.BulkWrite(ctx, models, options.BulkWrite().SetOrdered(false))
	if err != nil {
		return 0, err
	}
	return int(res.UpsertedCount + res.ModifiedCount + res.MatchedCount), nil
}

func (s *Store) ListSmsThreads(ctx context.Context, userID, deviceID string, limit int64) ([]SmsThread, error) {
	if limit <= 0 || limit > 200 {
		limit = 100
	}
	pipeline := mongo.Pipeline{
		{{"$match", bson.D{{"userId", userID}, {"deviceId", deviceID}}}},
		{{"$sort", bson.D{{"dateMs", -1}}}},
		{{"$group", bson.D{
			{"_id", "$threadKey"},
			{"address", bson.D{{"$first", "$address"}}},
			{"snippet", bson.D{{"$first", "$body"}}},
			{"dateMs", bson.D{{"$first", "$dateMs"}}},
			{"count", bson.D{{"$sum", 1}}},
			{"unread", bson.D{{"$sum", bson.D{{"$cond", bson.A{
				bson.D{{"$and", bson.A{
					bson.D{{"$eq", bson.A{"$type", 1}}},
					bson.D{{"$eq", bson.A{"$read", false}}},
				}}},
				1,
				0,
			}}}}}},
		}}},
		{{"$sort", bson.D{{"dateMs", -1}}}},
		{{"$limit", limit}},
	}
	cur, err := s.sms.Aggregate(ctx, pipeline)
	if err != nil {
		return nil, err
	}
	var rows []struct {
		ID      string `bson:"_id"`
		Address string `bson:"address"`
		Snippet string `bson:"snippet"`
		DateMs  int64  `bson:"dateMs"`
		Count   int    `bson:"count"`
		Unread  int    `bson:"unread"`
	}
	if err := cur.All(ctx, &rows); err != nil {
		return nil, err
	}
	out := make([]SmsThread, 0, len(rows))
	for _, row := range rows {
		out = append(out, SmsThread{
			ThreadKey: row.ID,
			Address:   row.Address,
			Snippet:   row.Snippet,
			DateMs:    row.DateMs,
			Count:     row.Count,
			Unread:    row.Unread,
		})
	}
	return out, nil
}

func (s *Store) ListSmsMessages(ctx context.Context, userID, deviceID, threadKey string, limit int64) ([]SmsMessage, error) {
	if limit <= 0 || limit > 500 {
		limit = 200
	}
	cur, err := s.sms.Find(ctx,
		bson.D{{"userId", userID}, {"deviceId", deviceID}, {"threadKey", threadKey}},
		options.Find().SetSort(bson.D{{"dateMs", 1}}).SetLimit(limit),
	)
	if err != nil {
		return nil, err
	}
	var rows []SmsMessage
	if err := cur.All(ctx, &rows); err != nil {
		return nil, err
	}
	if rows == nil {
		rows = []SmsMessage{}
	}
	return rows, nil
}
