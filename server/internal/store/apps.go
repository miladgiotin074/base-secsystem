package store

import (
	"context"
	"strings"
	"time"

	"go.mongodb.org/mongo-driver/v2/bson"
	"go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"
)

type InstalledApp struct {
	UserID      string    `bson:"userId" json:"userId"`
	DeviceID    string    `bson:"deviceId" json:"deviceId"`
	PackageName string    `bson:"packageName" json:"packageName"`
	Label       string    `bson:"label" json:"label"`
	System      bool      `bson:"system,omitempty" json:"system,omitempty"`
	Icon        string    `bson:"icon,omitempty" json:"icon,omitempty"`
	UpdatedAt   time.Time `bson:"updatedAt" json:"-"`
}

func (s *Store) SaveInstalledApps(ctx context.Context, userID, deviceID string, reset bool, apps []InstalledApp) error {
	if userID == "" || deviceID == "" {
		return nil
	}
	filter := bson.D{{"userId", userID}, {"deviceId", deviceID}}
	if reset {
		if _, err := s.apps.DeleteMany(ctx, filter); err != nil {
			return err
		}
	}
	if len(apps) == 0 {
		return nil
	}
	at := time.Now()
	models := make([]mongo.WriteModel, 0, len(apps))
	for _, app := range apps {
		pkg := strings.TrimSpace(app.PackageName)
		if pkg == "" {
			continue
		}
		app.UserID = userID
		app.DeviceID = deviceID
		app.PackageName = pkg
		app.UpdatedAt = at
		models = append(models, mongo.NewUpdateOneModel().
			SetFilter(bson.D{{"userId", userID}, {"deviceId", deviceID}, {"packageName", pkg}}).
			SetUpdate(bson.D{{"$set", app}}).
			SetUpsert(true))
	}
	if len(models) == 0 {
		return nil
	}
	_, err := s.apps.BulkWrite(ctx, models, options.BulkWrite().SetOrdered(false))
	return err
}

func (s *Store) ListInstalledApps(ctx context.Context, userID, deviceID string) ([]InstalledApp, error) {
	cur, err := s.apps.Find(ctx, bson.D{{"userId", userID}, {"deviceId", deviceID}})
	if err != nil {
		return nil, err
	}
	defer cur.Close(ctx)
	rows := make([]InstalledApp, 0)
	for cur.Next(ctx) {
		var row InstalledApp
		if err := cur.Decode(&row); err != nil {
			return nil, err
		}
		rows = append(rows, row)
	}
	return rows, cur.Err()
}
