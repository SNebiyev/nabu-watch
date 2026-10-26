package worker

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"math/rand/v2"
	"net/url"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/SNebiyev/nabu-watch/nabu-worker/internal/config"
	"github.com/SNebiyev/nabu-watch/nabu-worker/internal/scanner"
)

// domainSerializer ensures that at most one goroutine scans a given domain at
// a time and enforces a 10–20 second inter-request pause after each release.
// This prevents simultaneous requests from triggering Akamai / Cloudflare
// per-IP concurrency thresholds that return block pages for every connection
// beyond the first.
//
// Internally each domain gets a buffered channel of capacity 1 acting as a
// binary semaphore.  The channel is created with one token in it (available).
// acquire drains the token (blocks until it is returned); release returns the
// token after the inter-task sleep in a background goroutine so the caller's
// defer does not stall the goroutine pool.
type domainSerializer struct {
	mu    sync.Mutex
	gates map[string]chan struct{}
}

func newDomainSerializer() *domainSerializer {
	return &domainSerializer{gates: make(map[string]chan struct{})}
}

// acquire blocks until the domain gate is open, then holds it exclusively.
// Returns ctx.Err() if the context is cancelled while waiting.
func (ds *domainSerializer) acquire(ctx context.Context, domain string) error {
	ds.mu.Lock()
	ch, ok := ds.gates[domain]
	if !ok {
		ch = make(chan struct{}, 1)
		ch <- struct{}{} // token: initially available
		ds.gates[domain] = ch
	}
	ds.mu.Unlock()

	select {
	case <-ch: // grab the exclusive token
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

// release returns the token to the domain gate after a 10–20 s random delay.
// The sleep runs in a background goroutine so the caller's defer returns
// immediately while still enforcing the inter-task cooldown for the next waiter.
func (ds *domainSerializer) release(domain string) {
	go func() {
		jitter := time.Duration(10000+rand.IntN(10001)) * time.Millisecond
		time.Sleep(jitter)
		ds.returnToken(domain)
	}()
}

// returnToken gives the domain gate token back immediately.
// Safe to call multiple times — if the channel is already full, the call is a no-op.
func (ds *domainSerializer) returnToken(domain string) {
	ds.mu.Lock()
	if ch, ok := ds.gates[domain]; ok {
		select {
		case ch <- struct{}{}: // return token
		default: // already full — should never happen
		}
	}
	ds.mu.Unlock()
}

// Consumer reads from a Redis Stream consumer group and dispatches scan tasks
// to registered Scanner implementations using a bounded goroutine pool.
type Consumer struct {
	rdb        *redis.Client
	cfg        *config.Config
	scanners   map[string]scanner.Scanner // keyed by Scanner.Type()
	publisher  *ResultPublisher
	sem        chan struct{} // bounded semaphore = max concurrent goroutines
	wg         sync.WaitGroup
	domains    *domainSerializer   // serialises per-domain scans to avoid WAF bans
	wafBlocked *wafBlockRegistry   // fast-fail domains known to be WAF-blocked
	batchAbort *batchAbortRegistry // fast-fail monitors when scout aborts batch
}

func New(rdb *redis.Client, cfg *config.Config, scanners ...scanner.Scanner) *Consumer {
	registry := make(map[string]scanner.Scanner, len(scanners))
	for _, s := range scanners {
		registry[s.Type()] = s
	}
	return &Consumer{
		rdb:        rdb,
		cfg:        cfg,
		scanners:   registry,
		publisher:  newResultPublisher(rdb, cfg.Stream.ResultsKey),
		sem:        make(chan struct{}, cfg.Worker.MaxConcurrent),
		domains:    newDomainSerializer(),
		wafBlocked: newWAFBlockRegistry(5 * time.Minute),
		batchAbort: newBatchAbortRegistry(10 * time.Minute),
	}
}

// priorityPollDuration caps how long XReadGroup blocks on the priority
// stream before falling through to the standard stream. Short enough to
// avoid stalling the worker when only standard work is queued.
const priorityPollDuration = 100 * time.Millisecond

// Start blocks until ctx is cancelled, then drains in-flight goroutines.
func (c *Consumer) Start(ctx context.Context) error {
	if err := c.ensureConsumerGroup(ctx, c.cfg.Stream.Key); err != nil {
		return fmt.Errorf("create consumer group (standard): %w", err)
	}
	if c.cfg.Stream.PriorityKey != "" {
		if err := c.ensureConsumerGroup(ctx, c.cfg.Stream.PriorityKey); err != nil {
			return fmt.Errorf("create consumer group (priority): %w", err)
		}
	}

	c.evictPreviousConsumer(ctx)

	slog.Info("Consumer started",
		"stream", c.cfg.Stream.Key,
		"priority_stream", c.cfg.Stream.PriorityKey,
		"group", c.cfg.Stream.Group,
		"consumer", c.cfg.Worker.ConsumerName,
		"max_concurrent", c.cfg.Worker.MaxConcurrent,
	)

	// Periodically reclaim idle messages from dead consumers.
	go c.reclaimLoop(ctx)

	for {
		// Check for shutdown before blocking on Redis
		select {
		case <-ctx.Done():
			slog.Info("Shutdown signal received — draining in-flight tasks")
			c.wg.Wait()
			c.deregister()
			return nil
		default:
		}

		// Drain priority stream first (short non-blocking-ish poll). Only when
		// it is empty do we wait on the standard stream, so verified-tier work
		// always overtakes a queued standard backlog.
		streamKey := c.cfg.Stream.Key
		blockDuration := c.cfg.Stream.BlockDuration
		var msgs []redis.XStream
		var err error
		if c.cfg.Stream.PriorityKey != "" {
			msgs, err = c.rdb.XReadGroup(ctx, &redis.XReadGroupArgs{
				Group:    c.cfg.Stream.Group,
				Consumer: c.cfg.Worker.ConsumerName,
				Streams:  []string{c.cfg.Stream.PriorityKey, ">"},
				Count:    c.cfg.Stream.BatchSize,
				Block:    priorityPollDuration,
			}).Result()
			if err == nil && hasMessages(msgs) {
				streamKey = c.cfg.Stream.PriorityKey
			} else {
				msgs = nil
				if err != nil && !errors.Is(err, redis.Nil) {
					if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
						c.wg.Wait()
						return nil
					}
					slog.Warn("Priority XReadGroup error — falling through to standard", "error", err)
				}
			}
		}

		if msgs == nil {
			msgs, err = c.rdb.XReadGroup(ctx, &redis.XReadGroupArgs{
				Group:    c.cfg.Stream.Group,
				Consumer: c.cfg.Worker.ConsumerName,
				Streams:  []string{c.cfg.Stream.Key, ">"},
				Count:    c.cfg.Stream.BatchSize,
				Block:    blockDuration,
			}).Result()
		}

		if err != nil {
			if errors.Is(err, redis.Nil) {
				// Block timeout — no messages, loop again
				continue
			}
			if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
				c.wg.Wait()
				return nil
			}
			slog.Error("XReadGroup error — backing off", "error", err, "stream", streamKey)
			time.Sleep(time.Second)
			continue
		}

		for _, stream := range msgs {
			for _, msg := range stream.Messages {
				c.dispatchFromStream(ctx, stream.Stream, msg)
			}
		}
	}
}

// hasMessages reports whether any of the returned XStreams contain payload.
func hasMessages(streams []redis.XStream) bool {
	for _, s := range streams {
		if len(s.Messages) > 0 {
			return true
		}
	}
	return false
}

// dispatch acquires a semaphore slot and processes the message in a new goroutine.
// Defaults to the standard stream key for ACK targeting (back-compat for callers
// that don't track which stream the message came from).
func (c *Consumer) dispatch(ctx context.Context, msg redis.XMessage) {
	c.dispatchFromStream(ctx, c.cfg.Stream.Key, msg)
}

func (c *Consumer) dispatchFromStream(ctx context.Context, streamKey string, msg redis.XMessage) {
	c.sem <- struct{}{}
	c.wg.Add(1)
	go func(m redis.XMessage) {
		defer func() { <-c.sem }()
		defer c.wg.Done()
		c.processMessage(ctx, streamKey, m)
	}(msg)
}

// reclaimLoop periodically checks for pending messages stuck on dead consumers
// and reclaims them. Uses XPENDING to check first (read-only, doesn't resurrect
// dead consumers), then XAUTOCLAIM only when there are actual messages to claim.
func (c *Consumer) reclaimLoop(ctx context.Context) {
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()

	const minIdleTime = 2 * time.Minute

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}

		streams := []string{c.cfg.Stream.Key}
		if c.cfg.Stream.PriorityKey != "" {
			streams = append(streams, c.cfg.Stream.PriorityKey)
		}

		for _, streamKey := range streams {
			// Check if there are ANY pending messages in the group before calling
			// XAUTOCLAIM. XPENDING is read-only and won't resurrect dead consumers.
			pending, err := c.rdb.XPending(ctx, streamKey, c.cfg.Stream.Group).Result()
			if err != nil || pending.Count == 0 {
				continue
			}

			start := "0-0"
			for {
				msgs, newStart, err := c.rdb.XAutoClaim(ctx, &redis.XAutoClaimArgs{
					Stream:   streamKey,
					Group:    c.cfg.Stream.Group,
					Consumer: c.cfg.Worker.ConsumerName,
					MinIdle:  minIdleTime,
					Start:    start,
					Count:    10,
				}).Result()
				if err != nil {
					if !errors.Is(err, context.Canceled) {
						slog.Warn("XAUTOCLAIM error", "stream", streamKey, "error", err)
					}
					break
				}

				for _, msg := range msgs {
					slog.Info("Reclaimed idle message", "stream", streamKey, "message_id", msg.ID)
					c.dispatchFromStream(ctx, streamKey, msg)
				}

				if newStart == "0-0" || len(msgs) == 0 {
					break
				}
				start = newStart
			}
		}
	}
}

// consumerLockFile is the path where the current consumer name is persisted.
// On startup the previous name is read and that consumer is deleted from Redis;
// then the new name is written. This guarantees exactly one live consumer per
// worker instance, even across air hot-reloads.
const consumerLockFile = ".consumer-id"

// evictPreviousConsumer reads the lock file, deletes the old consumer from
// Redis, then writes the current consumer name to the file.
func (c *Consumer) evictPreviousConsumer(ctx context.Context) {
	lockPath := consumerLockPath()

	data, err := os.ReadFile(lockPath)
	if err == nil {
		prev := strings.TrimSpace(string(data))
		if prev != "" && prev != c.cfg.Worker.ConsumerName {
			for _, streamKey := range c.streamKeys() {
				pending, delErr := c.rdb.XGroupDelConsumer(ctx, streamKey, c.cfg.Stream.Group, prev).Result()
				if delErr != nil {
					slog.Warn("Failed to evict previous consumer",
						"stream", streamKey, "previous", prev, "error", delErr)
				} else {
					slog.Info("Evicted previous consumer",
						"stream", streamKey,
						"previous", prev,
						"current", c.cfg.Worker.ConsumerName,
						"released_pending", pending,
					)
				}
			}
		}
	}

	if writeErr := os.WriteFile(lockPath, []byte(c.cfg.Worker.ConsumerName), 0644); writeErr != nil {
		slog.Warn("Failed to write consumer lock file", "path", lockPath, "error", writeErr)
	}
}

// consumerLockPath returns the absolute path to the lock file in the working
// directory. Using cwd (not the binary dir) because air cleans its tmp/ dir
// on every rebuild, which would delete the lock file.
func consumerLockPath() string {
	return consumerLockFile
}

// domainOf extracts the apex hostname from a URL, stripping the www. prefix.
// Returns "" when the URL cannot be parsed or has no host.
func domainOf(rawURL string) string {
	u, err := url.Parse(rawURL)
	if err != nil || u.Host == "" {
		return ""
	}
	return strings.TrimPrefix(u.Hostname(), "www.")
}

// processMessage parses, dispatches, publishes the result, and always ACKs.
func (c *Consumer) processMessage(ctx context.Context, streamKey string, msg redis.XMessage) {
	task, err := scanner.ParseTask(msg.Values)
	if err != nil {
		slog.Error("Failed to parse task — ACKing to avoid poison pill",
			"message_id", msg.ID,
			"stream", streamKey,
			"error", err,
		)
		// No monitor_id available — publish a minimal error result.
		c.publisher.publishError(ctx, msg.ID, "unknown", err)
		c.ack(ctx, streamKey, msg.ID)
		return
	}

	slog.Info("Processing task",
		"message_id", msg.ID,
		"monitor_id", task.MonitorID,
		"type", task.MonitorType,
		"url", task.WebsiteURL,
		"region", task.RegionCode,
	)

	domain := domainOf(task.WebsiteURL)

	// Pre-gate fast-fail: skip instantly without waiting for the serializer.
	if c.skipIfAborted(ctx, msg, task, domain) {
		return
	}

	// Domain serialisation: hold an exclusive per-domain gate for the entire
	// duration of the scan, then release it after a 10–20 s random cooldown.
	if domain != "" {
		if err := c.domains.acquire(ctx, domain); err != nil {
			return
		}
		defer c.domains.release(domain)
		slog.Info("Domain serializer: gate acquired",
			"domain", domain,
			"monitor_id", task.MonitorID,
		)
	}

	// Post-gate re-check: the scout may have aborted the batch while this
	// goroutine was waiting for the domain serializer.
	if c.skipIfAborted(ctx, msg, task, domain) {
		return
	}

	s, ok := c.scanners[task.MonitorType]
	if !ok {
		slog.Warn("No scanner registered for type — ACKing",
			"type", task.MonitorType,
			"message_id", msg.ID,
		)
		c.publisher.publishError(ctx, msg.ID, task.MonitorID,
			fmt.Errorf("no scanner registered for type %q", task.MonitorType))
		c.ack(ctx, streamKey, msg.ID)
		return
	}

	// Per-task timeout enforced via child context
	scanCtx, cancel := context.WithTimeout(ctx, c.cfg.Worker.ScanTimeout)
	defer cancel()

	result, err := s.Scan(scanCtx, task)
	if err != nil {
		if errors.Is(err, scanner.ErrWAFStillBlocking) {
			// Record the block so subsequent tasks for this domain fast-fail.
			if domain != "" {
				c.wafBlocked.Mark(domain)
			}
			slog.Warn("Domain still protected by WAF — skipping result update to prevent false negatives",
				"monitor_id", task.MonitorID,
				"type", task.MonitorType,
			)
			c.publisher.publishWAFSkip(ctx, msg.ID, task.MonitorID, task.BatchID)
		} else if errors.Is(err, scanner.ErrBatchAborted) {
			// Scout confirmed homepage is not scannable — abort remaining batch monitors.
			if task.BatchID != "" && domain != "" {
				c.batchAbort.Mark(task.BatchID, domain)
			}
			slog.Warn("Scout aborted batch — homepage not scannable",
				"monitor_id", task.MonitorID,
				"batch_id", task.BatchID,
				"domain", domain,
			)
			// Publish scout's own FAILED result (contains abort details)
			if result != nil {
				c.publisher.Publish(ctx, msg.ID, result)
			}
		} else if errors.Is(err, scanner.ErrTransientHeadless) {
			// Transient site-side error (JS crash, etc.) — preserve previous state.
			slog.Warn("Transient headless error — preserving previous monitor state",
				"monitor_id", task.MonitorID,
				"type", task.MonitorType,
				"error", err,
			)
			c.publisher.publishTransientSkip(ctx, msg.ID, task.MonitorID, task.BatchID)
		} else {
			// Hard error from scanner (e.g. unparseable URL) — scanner did not
			// return a Result. Build a synthetic FAILED message and publish it.
			slog.Error("Scanner returned hard error",
				"monitor_id", task.MonitorID,
				"type", task.MonitorType,
				"error", err,
			)
			c.publisher.publishError(ctx, msg.ID, task.MonitorID, err)
		}
	} else {
		// result is always non-nil here (network failures return a failed Result,
		// not an error). Publish regardless of result.Success.
		c.publisher.Publish(ctx, msg.ID, result)
	}

	// Always ACK: result has been published; re-delivery would cause duplicates.
	c.ack(ctx, streamKey, msg.ID)
}

// skipIfAborted checks WAF and batch-abort registries and publishes a skip
// result if the scan should be fast-failed. Returns true when the message was
// handled (published + ACKed) and the caller should return immediately.
func (c *Consumer) skipIfAborted(ctx context.Context, msg redis.XMessage, task scanner.Task, domain string) bool {
	if domain != "" && c.wafBlocked.IsBlocked(domain) {
		slog.Warn("WAF fast-fail: domain recently blocked, skipping scan",
			"monitor_id", task.MonitorID, "domain", domain)
		c.publisher.publishWAFSkip(ctx, msg.ID, task.MonitorID, task.BatchID)
		c.ack(ctx, c.cfg.Stream.Key, msg.ID)
		return true
	}
	if task.MonitorType != "SSL" && task.BatchID != "" && domain != "" && c.batchAbort.IsAborted(task.BatchID, domain) {
		slog.Warn("Batch abort fast-fail: scout aborted this batch, skipping scan",
			"monitor_id", task.MonitorID, "batch_id", task.BatchID, "domain", domain)
		c.publisher.publishBatchAbortSkip(ctx, msg.ID, task.MonitorID, task.BatchID)
		c.ack(ctx, c.cfg.Stream.Key, msg.ID)
		return true
	}
	return false
}

// ack sends XACK against the originating stream and logs on failure.
func (c *Consumer) ack(ctx context.Context, streamKey, msgID string) {
	if err := c.rdb.XAck(ctx, streamKey, c.cfg.Stream.Group, msgID).Err(); err != nil {
		slog.Error("XACK failed", "stream", streamKey, "message_id", msgID, "error", err)
	}
}

// deregister removes this consumer from the group on graceful shutdown.
// Any pending messages (which shouldn't exist after wg.Wait) are released
// back to the group for other consumers to claim.
func (c *Consumer) deregister() {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	for _, streamKey := range c.streamKeys() {
		pending, err := c.rdb.XGroupDelConsumer(ctx, streamKey, c.cfg.Stream.Group, c.cfg.Worker.ConsumerName).Result()
		if err != nil {
			slog.Warn("Failed to deregister consumer", "stream", streamKey, "error", err)
		} else {
			slog.Info("Consumer deregistered",
				"stream", streamKey, "consumer", c.cfg.Worker.ConsumerName, "released_pending", pending)
		}
	}
}

// streamKeys returns the streams this consumer is registered on (standard
// always present, priority appended only when configured).
func (c *Consumer) streamKeys() []string {
	if c.cfg.Stream.PriorityKey == "" {
		return []string{c.cfg.Stream.Key}
	}
	return []string{c.cfg.Stream.Key, c.cfg.Stream.PriorityKey}
}

// ensureConsumerGroup creates the consumer group on the given stream if it
// does not exist. The "$" start ID means the group will only receive new
// messages.
func (c *Consumer) ensureConsumerGroup(ctx context.Context, streamKey string) error {
	err := c.rdb.XGroupCreateMkStream(ctx, streamKey, c.cfg.Stream.Group, "$").Err()
	if err != nil && err.Error() != "BUSYGROUP Consumer Group name already exists" {
		return err
	}
	return nil
}
