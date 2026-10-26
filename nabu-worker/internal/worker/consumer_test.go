package worker

import (
	"context"
	"encoding/json"
	"fmt"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"
	"github.com/redis/go-redis/v9"

	"github.com/SNebiyev/nabu-watch/nabu-worker/internal/config"
	"github.com/SNebiyev/nabu-watch/nabu-worker/internal/scanner"
)

// ─── test doubles ────────────────────────────────────────────────────────────

// mockScanner records calls and returns pre-configured results.
type mockScanner struct {
	scanType string
	result   *scanner.Result
	err      error
	calls    int
	lastTask scanner.Task
}

func (m *mockScanner) Type() string { return m.scanType }
func (m *mockScanner) Scan(_ context.Context, task scanner.Task) (*scanner.Result, error) {
	m.calls++
	m.lastTask = task
	return m.result, m.err
}

// ─── test helpers ─────────────────────────────────────────────────────────────

// newTestConsumer creates a Consumer backed by an in-memory Redis (miniredis)
// and registers the provided scanners. It also creates the consumer group so
// that XACK calls inside processMessage succeed without errors.
func newTestConsumer(t *testing.T, scanners ...scanner.Scanner) (*Consumer, *redis.Client) {
	t.Helper()

	mr := miniredis.RunT(t)
	rdb := redis.NewClient(&redis.Options{Addr: mr.Addr()})

	cfg := &config.Config{
		Worker: config.WorkerConfig{
			ConsumerName:  "test-consumer",
			MaxConcurrent: 2,
			ScanTimeout:   5 * time.Second,
		},
		Stream: config.StreamConfig{
			Key:           "nabu:scan-tasks",
			Group:         "test-group",
			ResultsKey:    "nabu:scan-results",
			BatchSize:     10,
			BlockDuration: 100 * time.Millisecond,
		},
	}

	c := New(rdb, cfg, scanners...)

	// Pre-create the consumer group so XACK inside processMessage does not fail.
	ctx := context.Background()
	_ = rdb.XGroupCreateMkStream(ctx, cfg.Stream.Key, cfg.Stream.Group, "$").Err()

	t.Cleanup(func() { _ = rdb.Close() })
	return c, rdb
}

// readResult reads the first (and typically only) message from the results
// stream in miniredis and unmarshals it as a ScanResultMessage.
func readResult(t *testing.T, rdb *redis.Client, resultsKey string) ScanResultMessage {
	t.Helper()
	msgs, err := rdb.XRange(context.Background(), resultsKey, "-", "+").Result()
	if err != nil {
		t.Fatalf("XRange %q: %v", resultsKey, err)
	}
	if len(msgs) == 0 {
		t.Fatalf("no messages in results stream %q", resultsKey)
	}
	raw, ok := msgs[0].Values["result"].(string)
	if !ok {
		t.Fatalf("result field missing or not a string in stream entry")
	}
	var msg ScanResultMessage
	if err := json.Unmarshal([]byte(raw), &msg); err != nil {
		t.Fatalf("unmarshal ScanResultMessage: %v", err)
	}
	return msg
}

// baseFields returns the mandatory Redis stream fields for a task message.
func baseFields(monitorType string) map[string]any {
	return map[string]any{
		"monitorId":   "mon-1",
		"websiteId":   "web-1",
		"websiteUrl":  "https://example.com",
		"regionCode":  "DE",
		"monitorType": monitorType,
	}
}

// ─── routing tests ───────────────────────────────────────────────────────────

func TestConsumer_RoutesSSLTask(t *testing.T) {
	sslScanner := &mockScanner{
		scanType: "SSL",
		result: &scanner.Result{
			MonitorID: "mon-1",
			Type:      "SSL",
			Success:   true,
			Details:   map[string]string{"days_remaining": "30"},
		},
	}
	c, rdb := newTestConsumer(t, sslScanner)

	c.processMessage(context.Background(), c.cfg.Stream.Key, redis.XMessage{
		ID:     "1-0",
		Values: baseFields("SSL"),
	})

	if sslScanner.calls != 1 {
		t.Errorf("SSL scanner called %d times, want 1", sslScanner.calls)
	}
	if sslScanner.lastTask.MonitorType != "SSL" {
		t.Errorf("task MonitorType = %q, want %q", sslScanner.lastTask.MonitorType, "SSL")
	}

	msg := readResult(t, rdb, c.cfg.Stream.ResultsKey)
	if msg.Status != "SUCCESS" {
		t.Errorf("status = %q, want %q", msg.Status, "SUCCESS")
	}
	if msg.MonitorID != "mon-1" {
		t.Errorf("monitor_id = %q, want %q", msg.MonitorID, "mon-1")
	}
	if msg.TaskID != "1-0" {
		t.Errorf("task_id = %q, want %q", msg.TaskID, "1-0")
	}
}

func TestConsumer_RoutesComplianceTask(t *testing.T) {
	complianceScanner := &mockScanner{
		scanType: "COMPLIANCE",
		result: &scanner.Result{
			MonitorID: "mon-1",
			Type:      "COMPLIANCE",
			Success:   true,
			Details:   map[string]string{"status": "COMPLIANT"},
		},
	}
	c, rdb := newTestConsumer(t, complianceScanner)

	fields := baseFields("COMPLIANCE")
	fields["requiredKeywords"] = `["privacy policy","impressum"]`

	c.processMessage(context.Background(), c.cfg.Stream.Key, redis.XMessage{
		ID:     "2-0",
		Values: fields,
	})

	if complianceScanner.calls != 1 {
		t.Errorf("compliance scanner called %d times, want 1", complianceScanner.calls)
	}
	if len(complianceScanner.lastTask.RequiredKeywords) != 2 {
		t.Errorf("RequiredKeywords len = %d, want 2", len(complianceScanner.lastTask.RequiredKeywords))
	}

	msg := readResult(t, rdb, c.cfg.Stream.ResultsKey)
	if msg.Status != "SUCCESS" {
		t.Errorf("status = %q, want %q", msg.Status, "SUCCESS")
	}
}

// ─── error routing tests ──────────────────────────────────────────────────────

func TestConsumer_UnknownMonitorType_PublishesFailedResult(t *testing.T) {
	c, rdb := newTestConsumer(t) // no scanners registered

	c.processMessage(context.Background(), c.cfg.Stream.Key, redis.XMessage{
		ID:     "3-0",
		Values: baseFields("UNKNOWN_TYPE"),
	})

	msg := readResult(t, rdb, c.cfg.Stream.ResultsKey)
	if msg.Status != "FAILED" {
		t.Errorf("status = %q, want %q", msg.Status, "FAILED")
	}
	if msg.MonitorID != "mon-1" {
		t.Errorf("monitor_id = %q, want %q", msg.MonitorID, "mon-1")
	}
}

func TestConsumer_ParseError_UsesUnknownMonitorID(t *testing.T) {
	c, rdb := newTestConsumer(t)

	// Missing mandatory "websiteUrl" field → ParseTask returns an error.
	c.processMessage(context.Background(), c.cfg.Stream.Key, redis.XMessage{
		ID: "4-0",
		Values: map[string]any{
			"monitorId": "mon-4",
			"websiteId": "web-1",
			// "websiteUrl" intentionally omitted
			"regionCode":  "DE",
			"monitorType": "SSL",
		},
	})

	msg := readResult(t, rdb, c.cfg.Stream.ResultsKey)
	if msg.Status != "FAILED" {
		t.Errorf("status = %q, want %q", msg.Status, "FAILED")
	}
	// When parsing fails we have no monitor_id; the consumer uses "unknown".
	if msg.MonitorID != "unknown" {
		t.Errorf("monitor_id = %q, want %q", msg.MonitorID, "unknown")
	}
}

func TestConsumer_ScannerHardError_PublishesFailedResult(t *testing.T) {
	errScanner := &mockScanner{
		scanType: "SSL",
		err:      fmt.Errorf("unparseable URL: some hard failure"),
	}
	c, rdb := newTestConsumer(t, errScanner)

	c.processMessage(context.Background(), c.cfg.Stream.Key, redis.XMessage{
		ID:     "5-0",
		Values: baseFields("SSL"),
	})

	if errScanner.calls != 1 {
		t.Errorf("scanner called %d times, want 1", errScanner.calls)
	}

	msg := readResult(t, rdb, c.cfg.Stream.ResultsKey)
	if msg.Status != "FAILED" {
		t.Errorf("status = %q, want %q", msg.Status, "FAILED")
	}
	if msg.MonitorID != "mon-1" {
		t.Errorf("monitor_id = %q, want %q", msg.MonitorID, "mon-1")
	}
	if msg.Payload["error"] == "" {
		t.Error("error detail must be populated in payload")
	}
}

func TestConsumer_ScannerSoftFailure_PublishesFailedStatus(t *testing.T) {
	// The scanner returns a failed Result (no Go error) — e.g. connection refused.
	failScanner := &mockScanner{
		scanType: "SSL",
		result: &scanner.Result{
			MonitorID: "mon-1",
			Type:      "SSL",
			Success:   false,
			Details:   map[string]string{"error": "tls dial: connection refused"},
		},
	}
	c, rdb := newTestConsumer(t, failScanner)

	c.processMessage(context.Background(), c.cfg.Stream.Key, redis.XMessage{
		ID:     "6-0",
		Values: baseFields("SSL"),
	})

	msg := readResult(t, rdb, c.cfg.Stream.ResultsKey)
	if msg.Status != "FAILED" {
		t.Errorf("status = %q, want %q", msg.Status, "FAILED")
	}
}
