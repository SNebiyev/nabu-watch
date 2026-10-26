package config

import (
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"
)

type Config struct {
	Redis   RedisConfig
	Worker  WorkerConfig
	Stream  StreamConfig
	Storage StorageConfig
}

type RedisConfig struct {
	Addr     string
	Password string
	DB       int
}

type WorkerConfig struct {
	ConsumerName        string        // unique per process instance
	MaxConcurrent       int           // goroutine pool size (all task types)
	MaxConcurrentChrome int           // max simultaneous headless tabs; protects RAM on small VMs
	ScanTimeout         time.Duration // per-task TLS/network timeout
}

type StorageConfig struct {
	Endpoint  string
	AccessKey string
	SecretKey string
	Bucket    string
	UseSSL    bool
}

type StreamConfig struct {
	Key           string
	PriorityKey   string
	Group         string
	ResultsKey    string
	BatchSize     int64
	BlockDuration time.Duration
}

func Load() (*Config, error) {
	hostname, _ := os.Hostname()

	maxConcurrent, err := strconv.Atoi(getEnv("WORKER_MAX_CONCURRENT", "10"))
	if err != nil {
		return nil, fmt.Errorf("invalid WORKER_MAX_CONCURRENT: %w", err)
	}

	// NABU_MAX_CONCURRENT_CHROME caps how many goroutines may hold an open
	// headless tab at once.  Each Chrome tab consumes ~150–300 MB of RAM, so
	// this must be sized to the server's available memory.  Default is 3,
	// which is safe on a 2 GB VM.  Set higher on larger instances.
	maxConcurrentChrome, err := strconv.Atoi(getEnv("NABU_MAX_CONCURRENT_CHROME", "3"))
	if err != nil {
		return nil, fmt.Errorf("invalid NABU_MAX_CONCURRENT_CHROME: %w", err)
	}

	useSSL, _ := strconv.ParseBool(getEnv("STORAGE_USE_SSL", "false"))

	// MinIO Go client expects host:port without scheme. Strip http(s)://
	// so callers can use either "localhost:9000" or "http://localhost:9000".
	// Auto-enable SSL when the endpoint uses https://.
	storageEndpoint := getEnv("STORAGE_ENDPOINT", "localhost:9000")
	if strings.HasPrefix(storageEndpoint, "https://") {
		useSSL = true
	}
	storageEndpoint = strings.TrimPrefix(storageEndpoint, "https://")
	storageEndpoint = strings.TrimPrefix(storageEndpoint, "http://")

	return &Config{
		Storage: StorageConfig{
			Endpoint:  storageEndpoint,
			AccessKey: getEnv("STORAGE_ACCESS_KEY", "minioadmin"),
			SecretKey: getEnv("STORAGE_SECRET_KEY", "minioadmin"),
			Bucket:    getEnv("STORAGE_BUCKET", "nabu-files"),
			UseSSL:    useSSL,
		},
		Redis: RedisConfig{
			Addr:     getEnv("REDIS_ADDR", "localhost:6379"),
			Password: getEnv("REDIS_PASSWORD", ""),
			DB:       0,
		},
		Worker: WorkerConfig{
			ConsumerName:        fmt.Sprintf("%s-%d", hostname, os.Getpid()),
			MaxConcurrent:       maxConcurrent,
			MaxConcurrentChrome: maxConcurrentChrome,
			ScanTimeout:         60 * time.Second, // must exceed internal fetchTimeout (45 s) + headless budget
		},
		Stream: StreamConfig{
			Key:           "nabu:scan-tasks",
			PriorityKey:   "nabu:scan-tasks:priority",
			Group:         "worker-group-1",
			ResultsKey:    "nabu:scan-results",
			BatchSize:     10,
			BlockDuration: 2 * time.Second,
		},
	}, nil
}

func getEnv(key, fallback string) string {
	if v, ok := os.LookupEnv(key); ok {
		return v
	}
	return fallback
}
