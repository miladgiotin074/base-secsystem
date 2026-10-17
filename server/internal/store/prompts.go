package store

import (
	"context"
	"strings"
	"time"

	"go.mongodb.org/mongo-driver/v2/bson"
	"go.mongodb.org/mongo-driver/v2/mongo/options"
)

func (s *Store) AIPromptOverrides(ctx context.Context) (map[string]string, error) {
	cur, err := s.aiPrompts.Find(ctx, bson.D{})
	if err != nil {
		return nil, err
	}
	defer cur.Close(ctx)
	out := map[string]string{}
	for cur.Next(ctx) {
		var row struct {
			ID   string `bson:"_id"`
			Text string `bson:"text"`
		}
		if err := cur.Decode(&row); err != nil {
			return nil, err
		}
		text := strings.TrimSpace(row.Text)
		if row.ID != "" && text != "" {
			out[row.ID] = text
		}
	}
	return out, cur.Err()
}

func (s *Store) SetAIPrompt(ctx context.Context, id, text string) error {
	_, err := s.aiPrompts.UpdateOne(ctx,
		bson.D{{"_id", id}},
		bson.D{{"$set", bson.D{
			{"text", text},
			{"updatedAt", time.Now()},
		}}},
		options.UpdateOne().SetUpsert(true),
	)
	return err
}

func (s *Store) ClearAIPrompt(ctx context.Context, id string) error {
	_, err := s.aiPrompts.DeleteOne(ctx, bson.D{{"_id", id}})
	return err
}
