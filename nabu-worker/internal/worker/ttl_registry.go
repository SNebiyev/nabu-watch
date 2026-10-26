package worker

import (
	"sync"
	"time"
)

// ttlRegistry is a thread-safe map with TTL-based eviction.
// Keys expire after the configured TTL and are cleaned up periodically.
type ttlRegistry struct {
	mu      sync.RWMutex
	entries map[string]time.Time
	ttl     time.Duration
}

func newTTLRegistry(ttl time.Duration) *ttlRegistry {
	r := &ttlRegistry{
		entries: make(map[string]time.Time),
		ttl:     ttl,
	}
	go r.evictLoop()
	return r
}

// Mark records the given key as of now.
func (r *ttlRegistry) Mark(key string) {
	r.mu.Lock()
	r.entries[key] = time.Now()
	r.mu.Unlock()
}

// Has returns true if the key was marked within the TTL window.
func (r *ttlRegistry) Has(key string) bool {
	r.mu.RLock()
	t, ok := r.entries[key]
	r.mu.RUnlock()
	return ok && time.Since(t) < r.ttl
}

func (r *ttlRegistry) evictLoop() {
	ticker := time.NewTicker(1 * time.Minute)
	defer ticker.Stop()
	for range ticker.C {
		r.mu.Lock()
		for key, t := range r.entries {
			if time.Since(t) >= r.ttl {
				delete(r.entries, key)
			}
		}
		r.mu.Unlock()
	}
}
