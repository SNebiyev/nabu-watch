package worker

import (
	"bytes"
	"context"
	"log/slog"
	"sync"

	"github.com/redis/go-redis/v9"
)

// PubSubLogHandler wraps an inner slog.Handler and publishes each log record
// to a Redis pub/sub channel. Subscribers (e.g. nabu-test CLI) can filter by
// structured fields like monitor_id and batch_id for live scan debugging.
//
// A single background goroutine drains a buffered channel, so log calls never
// block on Redis RTT and goroutine count stays constant regardless of log volume.
type PubSubLogHandler struct {
	inner   slog.Handler
	rdb     *redis.Client
	channel string
	attrs   []slog.Attr
	pubCh   chan string
	bufPool *sync.Pool
}

func NewPubSubLogHandler(inner slog.Handler, rdb *redis.Client, channel string) *PubSubLogHandler {
	h := &PubSubLogHandler{
		inner:   inner,
		rdb:     rdb,
		channel: channel,
		pubCh:   make(chan string, 256),
		bufPool: &sync.Pool{New: func() any { return new(bytes.Buffer) }},
	}
	go h.publishLoop()
	return h
}

func (h *PubSubLogHandler) publishLoop() {
	for payload := range h.pubCh {
		h.rdb.Publish(context.Background(), h.channel, payload)
	}
}

func (h *PubSubLogHandler) Enabled(ctx context.Context, level slog.Level) bool {
	return h.inner.Enabled(ctx, level)
}

func (h *PubSubLogHandler) Handle(ctx context.Context, r slog.Record) error {
	if err := h.inner.Handle(ctx, r); err != nil {
		return err
	}

	clone := r.Clone()
	clone.AddAttrs(h.attrs...)

	buf := h.bufPool.Get().(*bytes.Buffer)
	buf.Reset()

	jsonH := slog.NewJSONHandler(buf, &slog.HandlerOptions{Level: slog.LevelInfo})
	if err := jsonH.Handle(ctx, clone); err != nil {
		h.bufPool.Put(buf)
		return nil
	}

	payload := buf.String()
	h.bufPool.Put(buf)

	// Non-blocking send — drop the message if the channel is full.
	select {
	case h.pubCh <- payload:
	default:
	}
	return nil
}

func (h *PubSubLogHandler) WithAttrs(attrs []slog.Attr) slog.Handler {
	merged := make([]slog.Attr, len(h.attrs), len(h.attrs)+len(attrs))
	copy(merged, h.attrs)
	merged = append(merged, attrs...)
	return &PubSubLogHandler{
		inner:   h.inner.WithAttrs(attrs),
		rdb:     h.rdb,
		channel: h.channel,
		attrs:   merged,
		pubCh:   h.pubCh,
		bufPool: h.bufPool,
	}
}

func (h *PubSubLogHandler) WithGroup(name string) slog.Handler {
	return &PubSubLogHandler{
		inner:   h.inner.WithGroup(name),
		rdb:     h.rdb,
		channel: h.channel,
		attrs:   h.attrs,
		pubCh:   h.pubCh,
		bufPool: h.bufPool,
	}
}
