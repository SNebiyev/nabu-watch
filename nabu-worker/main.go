package main

import (
	"context"
	"log/slog"
	"os"
	"os/signal"
	"syscall"

	"github.com/joho/godotenv"
	"github.com/redis/go-redis/v9"

	"github.com/SNebiyev/nabu-watch/nabu-worker/internal/config"
	"github.com/SNebiyev/nabu-watch/nabu-worker/internal/scanner"
	"github.com/SNebiyev/nabu-watch/nabu-worker/internal/worker"
)

const (
	Version = "0.6.0"
)

func main() {
	// Load .env file if present (dev convenience — ignored if file is absent)
	_ = godotenv.Load()

	cfg, err := config.Load()
	if err != nil {
		slog.Error("Failed to load config", "error", err)
		os.Exit(1)
	}

	rdb := redis.NewClient(&redis.Options{
		Addr:     cfg.Redis.Addr,
		Password: cfg.Redis.Password,
		DB:       cfg.Redis.DB,
	})

	// Structured JSON logging with Redis pub/sub forwarding for nabu-test CLI.
	// The PubSubLogHandler publishes each log entry to "nabu:worker-logs" so
	// external tools can subscribe and filter by monitor_id / batch_id.
	jsonHandler := slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo})
	slog.SetDefault(slog.New(worker.NewPubSubLogHandler(jsonHandler, rdb, "nabu:worker-logs")))

	// Verify Redis connectivity at startup
	pingCtx, cancel := context.WithTimeout(context.Background(), cfg.Worker.ScanTimeout)
	defer cancel()
	if err := rdb.Ping(pingCtx).Err(); err != nil {
		slog.Error("Redis ping failed", "addr", cfg.Redis.Addr, "error", err)
		os.Exit(1)
	}
	slog.Info("Redis connected", "addr", cfg.Redis.Addr)

	// Initialise headless Chrome allocator (no-op if Chrome is not installed)
	headlessAlloc := scanner.NewHeadlessAllocator()

	// Initialise screenshot uploader (graceful degradation if MinIO is unreachable)
	screenshotUploader := scanner.NewScreenshotUploader(cfg.Storage)

	// When headless mode is active, cap the global concurrency to
	// MaxConcurrentChrome so we never spawn more Chrome tabs than the server
	// can hold in RAM.  The compliance scanner is the only headless consumer;
	// SSL tasks are unaffected but share the same semaphore pool.
	// On the typical 2 GB VM this defaults to 3 concurrent scans.
	if headlessAlloc.Available() && cfg.Worker.MaxConcurrentChrome < cfg.Worker.MaxConcurrent {
		slog.Info("Clamping MaxConcurrent for headless mode",
			"was", cfg.Worker.MaxConcurrent,
			"now", cfg.Worker.MaxConcurrentChrome,
			"env", "NABU_MAX_CONCURRENT_CHROME",
		)
		cfg.Worker.MaxConcurrent = cfg.Worker.MaxConcurrentChrome
	}

	// Register all scanner implementations
	consumer := worker.New(rdb, cfg,
		scanner.NewSSLScanner(),
		scanner.NewComplianceRouter(
			scanner.NewComplianceScanner(headlessAlloc),
			scanner.NewBannerScanner(headlessAlloc, screenshotUploader),
			scanner.NewTrackerScanner(headlessAlloc),
		),
		// Future: scanner.NewSecurityScanner(),
	)

	// Graceful shutdown on SIGINT / SIGTERM
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	slog.Info("nabu-worker starting",
		"version", Version,
		"consumer", cfg.Worker.ConsumerName,
		"stream", cfg.Stream.Key,
		"group", cfg.Stream.Group,
	)

	if err := consumer.Start(ctx); err != nil {
		slog.Error("Consumer exited with error", "error", err)
		os.Exit(1)
	}

	if err := rdb.Close(); err != nil {
		slog.Warn("Redis client close error", "error", err)
	}
	headlessAlloc.Close()
	slog.Info("nabu-worker stopped cleanly")
}
