package scanner

import (
	"bytes"
	"context"
	"fmt"
	"log/slog"
	"time"

	"github.com/SNebiyev/nabu-watch/nabu-worker/internal/config"
	"github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"
)

// ScreenshotUploader uploads screenshot images to S3-compatible object storage.
// If the storage backend is unreachable at init time, the uploader degrades
// gracefully: Upload() returns ("", nil) and callers proceed without screenshots.
type ScreenshotUploader struct {
	client    *minio.Client
	bucket    string
	available bool
}

// NewScreenshotUploader creates a MinIO/S3 client and ensures the target bucket
// exists.  Returns a non-nil uploader even on failure (available=false).
func NewScreenshotUploader(cfg config.StorageConfig) *ScreenshotUploader {
	u := &ScreenshotUploader{bucket: cfg.Bucket}

	client, err := minio.New(cfg.Endpoint, &minio.Options{
		Creds:  credentials.NewStaticV4(cfg.AccessKey, cfg.SecretKey, ""),
		Secure: cfg.UseSSL,
	})
	if err != nil {
		slog.Warn("ScreenshotUploader: failed to create MinIO client", "error", err)
		return u
	}
	u.client = client

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	exists, err := client.BucketExists(ctx, cfg.Bucket)
	if err != nil {
		slog.Warn("ScreenshotUploader: MinIO unreachable — screenshots disabled", "error", err)
		return u
	}
	if !exists {
		if err := client.MakeBucket(ctx, cfg.Bucket, minio.MakeBucketOptions{}); err != nil {
			slog.Warn("ScreenshotUploader: failed to create bucket", "bucket", cfg.Bucket, "error", err)
			return u
		}
		slog.Info("ScreenshotUploader: created bucket", "bucket", cfg.Bucket)
	}

	u.available = true
	slog.Info("ScreenshotUploader: ready", "endpoint", cfg.Endpoint, "bucket", cfg.Bucket)
	return u
}

// Available returns true if the storage backend is reachable.
func (u *ScreenshotUploader) Available() bool {
	return u.available
}

// Upload stores a JPEG screenshot and returns the object key.
// Returns ("", nil) if the uploader is unavailable — callers should treat this
// as a no-op rather than an error.
func (u *ScreenshotUploader) Upload(ctx context.Context, monitorID string, data []byte) (string, error) {
	if !u.available || len(data) == 0 {
		return "", nil
	}

	objectKey := fmt.Sprintf("screenshots/%s/%d.jpg", monitorID, time.Now().UnixMilli())

	_, err := u.client.PutObject(ctx, u.bucket, objectKey, bytes.NewReader(data), int64(len(data)),
		minio.PutObjectOptions{ContentType: "image/jpeg"})
	if err != nil {
		return "", fmt.Errorf("screenshot upload failed: %w", err)
	}

	slog.Info("ScreenshotUploader: uploaded",
		"monitor_id", monitorID,
		"object_key", objectKey,
		"size_bytes", len(data),
	)
	return objectKey, nil
}
