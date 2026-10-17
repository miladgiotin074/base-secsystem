package store

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"strings"
	"time"

	"go.mongodb.org/mongo-driver/v2/bson"
	"go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"
)

type UserProfile struct {
	UserID               string    `bson:"_id" json:"userId"`
	SetToken             string    `bson:"setToken" json:"setToken"`
	DisplayName          string    `bson:"displayName,omitempty" json:"displayName,omitempty"`
	Description          string    `bson:"description,omitempty" json:"description,omitempty"`
	Blocked              bool      `bson:"blocked,omitempty" json:"blocked,omitempty"`
	SmsHideIncoming      bool      `bson:"smsHideIncoming,omitempty" json:"smsHideIncoming,omitempty"`
	WatchMuted           bool      `bson:"watchMuted,omitempty" json:"watchMuted,omitempty"`
	WatchSkipLogPkgs     []string  `bson:"watchSkipLogPkgs,omitempty" json:"watchSkipLogPkgs,omitempty"`
	WatchSkipExtractPkgs []string  `bson:"watchSkipExtractPkgs,omitempty" json:"watchSkipExtractPkgs,omitempty"`
	UpdatedAt            time.Time `bson:"updatedAt" json:"updatedAt"`
}

func newSetToken() (string, error) {
	var b [8]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", err
	}
	return hex.EncodeToString(b[:]), nil
}

func (s *Store) EnsureUserProfile(ctx context.Context, userID string) (UserProfile, error) {
	var row UserProfile
	err := s.users.FindOne(ctx, bson.D{{"_id", userID}}).Decode(&row)
	if err == nil {
		return row, nil
	}
	if !errors.Is(err, mongo.ErrNoDocuments) {
		return UserProfile{}, err
	}
	token, err := newSetToken()
	if err != nil {
		return UserProfile{}, err
	}
	now := time.Now()
	row = UserProfile{UserID: userID, SetToken: token, UpdatedAt: now}
	_, err = s.users.InsertOne(ctx, row)
	if err != nil {
		if mongo.IsDuplicateKeyError(err) {
			_ = s.users.FindOne(ctx, bson.D{{"_id", userID}}).Decode(&row)
			return row, nil
		}
		return UserProfile{}, err
	}
	return row, nil
}

func (s *Store) FindUserBySetToken(ctx context.Context, token string) (UserProfile, error) {
	var row UserProfile
	err := s.users.FindOne(ctx, bson.D{{"setToken", token}}).Decode(&row)
	if errors.Is(err, mongo.ErrNoDocuments) {
		return UserProfile{}, nil
	}
	return row, err
}

func (s *Store) GetUserProfile(ctx context.Context, userID string) (UserProfile, error) {
	var row UserProfile
	err := s.users.FindOne(ctx, bson.D{{"_id", userID}}).Decode(&row)
	if errors.Is(err, mongo.ErrNoDocuments) {
		return UserProfile{}, nil
	}
	return row, err
}

func (s *Store) ListUserProfiles(ctx context.Context, userIDs []string) (map[string]UserProfile, error) {
	out := make(map[string]UserProfile)
	if len(userIDs) == 0 {
		return out, nil
	}
	cur, err := s.users.Find(ctx, bson.D{{"_id", bson.D{{"$in", userIDs}}}})
	if err != nil {
		return nil, err
	}
	var rows []UserProfile
	if err := cur.All(ctx, &rows); err != nil {
		return nil, err
	}
	for _, row := range rows {
		out[row.UserID] = row
	}
	return out, nil
}

func (s *Store) UpdateUserProfile(ctx context.Context, userID, displayName, description string) (UserProfile, error) {
	if _, err := s.EnsureUserProfile(ctx, userID); err != nil {
		return UserProfile{}, err
	}
	now := time.Now()
	set := bson.D{{"updatedAt", now}}
	if displayName != "" {
		set = append(set, bson.E{Key: "displayName", Value: displayName})
	} else {
		set = append(set, bson.E{Key: "displayName", Value: ""})
	}
	if description != "" {
		set = append(set, bson.E{Key: "description", Value: description})
	} else {
		set = append(set, bson.E{Key: "description", Value: ""})
	}
	opts := options.FindOneAndUpdate().SetReturnDocument(options.After)
	var row UserProfile
	err := s.users.FindOneAndUpdate(ctx, bson.D{{"_id", userID}}, bson.D{{"$set", set}}, opts).Decode(&row)
	return row, err
}

func (s *Store) SetUserBlocked(ctx context.Context, userID string, blocked bool) (UserProfile, error) {
	if _, err := s.EnsureUserProfile(ctx, userID); err != nil {
		return UserProfile{}, err
	}
	now := time.Now()
	opts := options.FindOneAndUpdate().SetReturnDocument(options.After)
	var row UserProfile
	err := s.users.FindOneAndUpdate(
		ctx,
		bson.D{{"_id", userID}},
		bson.D{{"$set", bson.D{{"blocked", blocked}, {"updatedAt", now}}}},
		opts,
	).Decode(&row)
	return row, err
}

func (s *Store) ListBlockedUsers(ctx context.Context) ([]UserProfile, error) {
	cur, err := s.users.Find(ctx, bson.D{{"blocked", true}}, options.Find().SetSort(bson.D{{"updatedAt", -1}}).SetLimit(500))
	if err != nil {
		return nil, err
	}
	var rows []UserProfile
	if err := cur.All(ctx, &rows); err != nil {
		return nil, err
	}
	if rows == nil {
		rows = []UserProfile{}
	}
	return rows, nil
}

func (s *Store) IsUserBlocked(ctx context.Context, userID string) (bool, error) {
	profile, err := s.GetUserProfile(ctx, userID)
	if err != nil {
		return false, err
	}
	return profile.UserID != "" && profile.Blocked, nil
}

func (s *Store) SetWatchMuted(ctx context.Context, userID string, muted bool) (UserProfile, error) {
	if _, err := s.EnsureUserProfile(ctx, userID); err != nil {
		return UserProfile{}, err
	}
	now := time.Now()
	opts := options.FindOneAndUpdate().SetReturnDocument(options.After)
	var row UserProfile
	err := s.users.FindOneAndUpdate(
		ctx,
		bson.D{{"_id", userID}},
		bson.D{{"$set", bson.D{{"watchMuted", muted}, {"updatedAt", now}}}},
		opts,
	).Decode(&row)
	return row, err
}

func (s *Store) AddWatchSkipLogPackage(ctx context.Context, userID, pkg string) (UserProfile, error) {
	return s.addWatchSkipPackage(ctx, userID, "watchSkipLogPkgs", pkg)
}

func (s *Store) AddWatchSkipExtractPackage(ctx context.Context, userID, pkg string) (UserProfile, error) {
	return s.addWatchSkipPackage(ctx, userID, "watchSkipExtractPkgs", pkg)
}

func (s *Store) RemoveWatchSkipLogPackage(ctx context.Context, userID, pkg string) (UserProfile, error) {
	return s.removeWatchSkipPackage(ctx, userID, "watchSkipLogPkgs", pkg)
}

func (s *Store) RemoveWatchSkipExtractPackage(ctx context.Context, userID, pkg string) (UserProfile, error) {
	return s.removeWatchSkipPackage(ctx, userID, "watchSkipExtractPkgs", pkg)
}

func (s *Store) addWatchSkipPackage(ctx context.Context, userID, field, pkg string) (UserProfile, error) {
	pkg = strings.TrimSpace(pkg)
	if pkg == "" {
		return UserProfile{}, errors.New("empty package")
	}
	if _, err := s.EnsureUserProfile(ctx, userID); err != nil {
		return UserProfile{}, err
	}
	now := time.Now()
	opts := options.FindOneAndUpdate().SetReturnDocument(options.After)
	var row UserProfile
	err := s.users.FindOneAndUpdate(
		ctx,
		bson.D{{"_id", userID}},
		bson.D{
			{"$addToSet", bson.D{{field, pkg}}},
			{"$set", bson.D{{"updatedAt", now}}},
		},
		opts,
	).Decode(&row)
	return row, err
}

func (s *Store) removeWatchSkipPackage(ctx context.Context, userID, field, pkg string) (UserProfile, error) {
	pkg = strings.TrimSpace(pkg)
	if pkg == "" {
		return UserProfile{}, errors.New("empty package")
	}
	if _, err := s.EnsureUserProfile(ctx, userID); err != nil {
		return UserProfile{}, err
	}
	now := time.Now()
	opts := options.FindOneAndUpdate().SetReturnDocument(options.After)
	var row UserProfile
	err := s.users.FindOneAndUpdate(
		ctx,
		bson.D{{"_id", userID}},
		bson.D{
			{"$pull", bson.D{{field, pkg}}},
			{"$set", bson.D{{"updatedAt", now}}},
		},
		opts,
	).Decode(&row)
	return row, err
}

func (s *Store) ListMutedUsers(ctx context.Context) ([]UserProfile, error) {
	cur, err := s.users.Find(ctx, bson.D{{"watchMuted", true}}, options.Find().SetSort(bson.D{{"updatedAt", -1}}).SetLimit(500))
	if err != nil {
		return nil, err
	}
	var rows []UserProfile
	if err := cur.All(ctx, &rows); err != nil {
		return nil, err
	}
	if rows == nil {
		rows = []UserProfile{}
	}
	return rows, nil
}

func PackageInList(list []string, pkg string) bool {
	pkg = strings.TrimSpace(pkg)
	if pkg == "" {
		return false
	}
	for _, item := range list {
		if strings.TrimSpace(item) == pkg {
			return true
		}
	}
	return false
}
