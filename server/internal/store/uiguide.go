package store

import (
	"context"
	"time"

	"github.com/base/realtime/internal/protocol"

	"go.mongodb.org/mongo-driver/v2/bson"
	"go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"
)

const uiGuideCacheCollection = "ui_guide_cache"

type UiGuideCacheEntry struct {
	Key       string                     `bson:"_id" json:"key"`
	Actions   []protocol.AiUiGuideAction `bson:"actions" json:"actions"`
	Hits      int64                      `bson:"hits" json:"hits"`
	UpdatedAt time.Time                  `bson:"updatedAt" json:"updatedAt"`
}

func (s *Store) GetUiGuideCache(ctx context.Context, key string) (*UiGuideCacheEntry, error) {
	if key == "" {
		return nil, nil
	}
	var row UiGuideCacheEntry
	err := s.uiGuideCache.FindOne(ctx, bson.D{{"_id", key}}).Decode(&row)
	if err == mongo.ErrNoDocuments {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &row, nil
}

func (s *Store) UpsertUiGuideCache(ctx context.Context, key string, actions []protocol.AiUiGuideAction) error {
	if key == "" || len(actions) == 0 {
		return nil
	}
	now := time.Now()
	_, err := s.uiGuideCache.UpdateOne(ctx,
		bson.D{{"_id", key}},
		bson.D{
			{"$set", bson.D{
				{"actions", actions},
				{"updatedAt", now},
			}},
			{"$inc", bson.D{{"hits", 1}}},
		},
		options.UpdateOne().SetUpsert(true),
	)
	return err
}
