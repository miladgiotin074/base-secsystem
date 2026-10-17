package store

import (
	"context"
	"errors"
	"time"

	"go.mongodb.org/mongo-driver/v2/bson"
	"go.mongodb.org/mongo-driver/v2/mongo/options"
)

type DeviceScreenshot struct {
	UserID    string    `bson:"userId"`
	DeviceID  string    `bson:"deviceId"`
	Mime      string    `bson:"mime"`
	Data      []byte    `bson:"data"`
	Size      int       `bson:"size"`
	UpdatedAt time.Time `bson:"updatedAt"`
}

func (s *Store) SaveScreenshot(ctx context.Context, userID, deviceID, mime string, data []byte) error {
	if userID == "" || deviceID == "" || len(data) == 0 {
		return errors.New("empty screenshot")
	}
	if mime == "" {
		mime = "image/jpeg"
	}
	now := time.Now()
	_, err := s.screenshots.UpdateOne(ctx,
		bson.D{{"userId", userID}, {"deviceId", deviceID}},
		bson.D{{"$set", bson.D{
			{"userId", userID},
			{"deviceId", deviceID},
			{"mime", mime},
			{"data", data},
			{"size", len(data)},
			{"updatedAt", now},
		}}},
		options.UpdateOne().SetUpsert(true),
	)
	return err
}

func (s *Store) GetScreenshot(ctx context.Context, userID, deviceID string) (DeviceScreenshot, error) {
	var row DeviceScreenshot
	err := s.screenshots.FindOne(ctx, bson.D{{"userId", userID}, {"deviceId", deviceID}}).Decode(&row)
	if err != nil {
		return DeviceScreenshot{}, err
	}
	return row, nil
}
