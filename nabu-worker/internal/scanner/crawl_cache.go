package scanner

import (
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/PuerkitoBio/goquery"
)

// crawlEntry holds the serialised result of one successful homepage fetch.
// Storing raw HTML (not *goquery.Document) is intentional: goquery Selections
// are not safe to share across goroutines even for reads because internal
// iteration state is mutated.  Re-parsing from a string takes ~1–3 ms —
// negligible compared to a 5–60 s live headless fetch.
type crawlEntry struct {
	rawHTML    string
	statusCode int
	scannedVia string // "STATIC" | "HEADLESS" | "FLARESOLVERR"
	cachedAt   time.Time
}

// reparse builds a fresh *goquery.Document from the cached raw HTML.
// Each call produces an independent document; callers may traverse and
// mutate it freely without affecting the cached string.
func (e *crawlEntry) reparse() (*goquery.Document, error) {
	return goquery.NewDocumentFromReader(strings.NewReader(e.rawHTML))
}

// HomepageCrawlCache is a thread-safe, TTL-evicting in-memory cache of
// homepage fetch results.
//
// # Design rationale
//
// The domainSerializer in consumer.go already guarantees that only one
// goroutine scans a given domain at a time, so a heavy distributed cache
// (Redis) would buy nothing here — a local map suffices and avoids the
// serialisation round-trip for potentially large HTML payloads.
//
// Cache key: "batchID:domain" when a batchID is present (manual rescan —
// results are scoped to the batch and cannot be stale-hit by a later
// unrelated batch), or just "domain" for scheduler-driven single scans
// (short TTL prevents cross-scan contamination).
type HomepageCrawlCache struct {
	mu      sync.RWMutex
	entries map[string]*crawlEntry
	ttl     time.Duration
}

// newHomepageCrawlCache returns an initialised cache and starts a background
// goroutine that evicts expired entries every minute.
// The goroutine exits automatically when the process terminates.
func newHomepageCrawlCache(ttl time.Duration) *HomepageCrawlCache {
	c := &HomepageCrawlCache{
		entries: make(map[string]*crawlEntry),
		ttl:     ttl,
	}
	go c.evictLoop()
	return c
}

// get returns the cached entry for key if it exists and has not yet expired.
func (c *HomepageCrawlCache) get(key string) (*crawlEntry, bool) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	e, ok := c.entries[key]
	if !ok || time.Since(e.cachedAt) > c.ttl {
		return nil, false
	}
	return e, true
}

// put stores entry under key, replacing any previous value.
func (c *HomepageCrawlCache) put(key string, e *crawlEntry) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.entries[key] = e
}

// evictLoop removes expired entries once per minute to bound memory growth.
func (c *HomepageCrawlCache) evictLoop() {
	ticker := time.NewTicker(time.Minute)
	defer ticker.Stop()
	for range ticker.C {
		c.mu.Lock()
		for k, e := range c.entries {
			if time.Since(e.cachedAt) > c.ttl {
				delete(c.entries, k)
			}
		}
		c.mu.Unlock()
	}
}

// cacheKey builds the map key used by HomepageCrawlCache.
//
//   - When batchID is non-empty, the key is "batchID:domain" — the cache entry
//     is tightly scoped to one manual-rescan batch and cannot be accidentally
//     reused by an unrelated later batch for the same domain.
//   - When batchID is empty (scheduler-driven scan), the key is just "domain".
//     The 5-minute TTL on the cache prevents cross-scan contamination.
func cacheKey(batchID, domain string) string {
	if batchID != "" {
		return batchID + ":" + domain
	}
	return domain
}

// apexDomain extracts the apex hostname from rawURL, stripping the www. prefix.
// Returns "" when the URL cannot be parsed or has no host.
// Mirrors the domainOf helper in the worker package so the scanner package
// remains self-contained without an import cycle.
func apexDomain(rawURL string) string {
	u, err := url.Parse(rawURL)
	if err != nil || u.Host == "" {
		return ""
	}
	return strings.TrimPrefix(u.Hostname(), "www.")
}
