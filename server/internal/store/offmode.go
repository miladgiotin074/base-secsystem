package store

import (
	"context"
	"strings"
	"time"

	"go.mongodb.org/mongo-driver/v2/bson"
	"go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"
)

const offmodeSettingsID = "default"

// Default Iranian mobile: 09xxxxxxxxx, +989xxxxxxxxx, 989xxxxxxxxx
const DefaultOffmodePersonalPattern = `^(?:\+?98|0)?9\d{9}$`

type OffmodeSettings struct {
	PersonalPattern string `bson:"personalPattern,omitempty" json:"personalPattern"`
	CmdPersonal     string `bson:"cmdPersonal,omitempty" json:"cmdPersonal"`
	CmdNonPersonal  string `bson:"cmdNonPersonal,omitempty" json:"cmdNonPersonal"`
	CmdAll          string `bson:"cmdAll,omitempty" json:"cmdAll"`
}

func DefaultOffmodeSettings() OffmodeSettings {
	return OffmodeSettings{
		PersonalPattern: DefaultOffmodePersonalPattern,
		CmdPersonal:     "offmode personal",
		CmdNonPersonal:  "offmode",
		CmdAll:          "offmode all",
	}
}

func NormalizeOffmodeSettings(in OffmodeSettings) OffmodeSettings {
	out := DefaultOffmodeSettings()
	if p := strings.TrimSpace(in.PersonalPattern); p != "" {
		out.PersonalPattern = p
	}
	out.CmdPersonal = strings.TrimSpace(in.CmdPersonal)
	out.CmdNonPersonal = strings.TrimSpace(in.CmdNonPersonal)
	out.CmdAll = strings.TrimSpace(in.CmdAll)
	if out.CmdPersonal == "" && out.CmdNonPersonal == "" && out.CmdAll == "" {
		d := DefaultOffmodeSettings()
		out.CmdPersonal = d.CmdPersonal
		out.CmdNonPersonal = d.CmdNonPersonal
		out.CmdAll = d.CmdAll
	}
	return out
}

func (s *Store) GetOffmodeSettings(ctx context.Context) (OffmodeSettings, error) {
	var row OffmodeSettings
	err := s.offmodeSettings.FindOne(ctx, bson.D{{"_id", offmodeSettingsID}}).Decode(&row)
	if err == mongo.ErrNoDocuments {
		return DefaultOffmodeSettings(), nil
	}
	if err != nil {
		return OffmodeSettings{}, err
	}
	return NormalizeOffmodeSettings(row), nil
}

func (s *Store) SetOffmodeSettings(ctx context.Context, in OffmodeSettings) (OffmodeSettings, error) {
	clean := NormalizeOffmodeSettings(in)
	_, err := s.offmodeSettings.UpdateOne(ctx,
		bson.D{{"_id", offmodeSettingsID}},
		bson.D{{"$set", bson.D{
			{"personalPattern", clean.PersonalPattern},
			{"cmdPersonal", clean.CmdPersonal},
			{"cmdNonPersonal", clean.CmdNonPersonal},
			{"cmdAll", clean.CmdAll},
			{"updatedAt", time.Now()},
		}}},
		options.UpdateOne().SetUpsert(true),
	)
	if err != nil {
		return OffmodeSettings{}, err
	}
	return clean, nil
}
