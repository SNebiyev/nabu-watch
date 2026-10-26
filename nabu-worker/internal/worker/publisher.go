package worker

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/SNebiyev/nabu-watch/nabu-worker/internal/scanner"
)

const (
	statusSuccess           = "SUCCESS"
	statusFailed            = "FAILED"
	statusSkippedWAF        = "SKIPPED_WAF"
	statusSkippedTransient  = "SKIPPED_TRANSIENT"
	statusSkippedBatchAbort = "SKIPPED_BATCH_ABORT"
)

// ScanResultMessage is the canonical payload written to "nabu:scan-results".
// It is stored as a single JSON value under the "result" field of the stream entry.
type ScanResultMessage struct {
	TaskID      string            `json:"task_id"` // originating message ID from nabu:scan-tasks
	MonitorID   string            `json:"monitor_id"`
	Status      string            `json:"status"`             // "SUCCESS" | "FAILED"
	Payload     map[string]string `json:"payload"`            // scanner Details map
	PublishedAt time.Time         `json:"published_at"`       // UTC timestamp
	BatchID     string            `json:"batch_id,omitempty"` // echoed from task for audit grouping
	RawDOM      string            `json:"raw_dom,omitempty"`  // full merged DOM snapshot (when captured) — for audit
}

// ResultPublisher writes scan outcomes to the results stream.
type ResultPublisher struct {
	rdb        *redis.Client
	resultsKey string
}

func newResultPublisher(rdb *redis.Client, resultsKey string) *ResultPublisher {
	return &ResultPublisher{rdb: rdb, resultsKey: resultsKey}
}

// Publish serialises result into a ScanResultMessage and appends it to the
// results stream. It always logs the outcome, but never returns an error to
// the caller — a publish failure must not prevent the task from being ACKed.
func (p *ResultPublisher) Publish(ctx context.Context, taskID string, result *scanner.Result) {
	status := statusSuccess
	if !result.Success {
		status = statusFailed
	}

	msg := ScanResultMessage{
		TaskID:      taskID,
		MonitorID:   result.MonitorID,
		Status:      status,
		Payload:     result.Details,
		PublishedAt: time.Now().UTC(),
		BatchID:     result.BatchID,
		RawDOM:      result.RawDOM,
	}

	jsonBytes, err := json.Marshal(msg)
	if err != nil {
		slog.Error("Failed to marshal scan result",
			"task_id", taskID,
			"monitor_id", result.MonitorID,
			"error", err,
		)
		return
	}

	recordID, err := p.rdb.XAdd(ctx, &redis.XAddArgs{
		Stream: p.resultsKey,
		Values: map[string]any{"result": string(jsonBytes)},
	}).Result()

	if err != nil {
		slog.Error("Failed to publish scan result to stream",
			"task_id", taskID,
			"monitor_id", result.MonitorID,
			"stream", p.resultsKey,
			"error", err,
		)
		return
	}

	slog.Info("Scan result published",
		"record_id", recordID,
		"task_id", taskID,
		"monitor_id", result.MonitorID,
		"status", status,
		"stream", p.resultsKey,
	)
}

// publishError constructs a synthetic FAILED result when the scanner itself
// returns a hard error (e.g. unparseable URL) rather than a failure Result.
func (p *ResultPublisher) publishError(ctx context.Context, taskID, monitorID string, err error) {
	p.Publish(ctx, taskID, &scanner.Result{
		MonitorID: monitorID,
		Success:   false,
		Details:   map[string]string{"error": fmt.Sprintf("%v", err)},
	})
}

func (p *ResultPublisher) publishWAFSkip(ctx context.Context, taskID, monitorID, batchID string) {
	p.publishSkip(ctx, taskID, monitorID, batchID, statusSkippedWAF, "WAF_BLOCK")
}

func (p *ResultPublisher) publishTransientSkip(ctx context.Context, taskID, monitorID, batchID string) {
	p.publishSkip(ctx, taskID, monitorID, batchID, statusSkippedTransient, "TRANSIENT_ERROR")
}

func (p *ResultPublisher) publishBatchAbortSkip(ctx context.Context, taskID, monitorID, batchID string) {
	p.publishSkip(ctx, taskID, monitorID, batchID, statusSkippedBatchAbort, "BATCH_ABORTED")
}

// publishSkip publishes a skip result with the given status and error code.
func (p *ResultPublisher) publishSkip(ctx context.Context, taskID, monitorID, batchID, status, errorCode string) {
	msg := ScanResultMessage{
		TaskID:      taskID,
		MonitorID:   monitorID,
		Status:      status,
		Payload:     map[string]string{"error_code": errorCode},
		PublishedAt: time.Now().UTC(),
		BatchID:     batchID,
	}

	jsonBytes, err := json.Marshal(msg)
	if err != nil {
		slog.Error("Failed to marshal skip result", "task_id", taskID, "monitor_id", monitorID, "status", status, "error", err)
		return
	}

	recordID, err := p.rdb.XAdd(ctx, &redis.XAddArgs{
		Stream: p.resultsKey,
		Values: map[string]any{"result": string(jsonBytes)},
	}).Result()
	if err != nil {
		slog.Error("Failed to publish skip result to stream",
			"task_id", taskID, "monitor_id", monitorID, "status", status, "stream", p.resultsKey, "error", err)
		return
	}

	slog.Info("Skip result published",
		"record_id", recordID, "task_id", taskID, "monitor_id", monitorID, "status", status)
}
