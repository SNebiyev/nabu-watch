package worker

import "time"

// wafBlockRegistry tracks domains that recently returned ErrWAFStillBlocking.
// Subsequent tasks for a blocked domain can skip the scan entirely, avoiding
// repeated 30-45s headless fetches that will hit the same WAF wall.
type wafBlockRegistry struct{ reg *ttlRegistry }

func newWAFBlockRegistry(ttl time.Duration) *wafBlockRegistry {
	return &wafBlockRegistry{reg: newTTLRegistry(ttl)}
}

func (r *wafBlockRegistry) Mark(domain string)           { r.reg.Mark(domain) }
func (r *wafBlockRegistry) IsBlocked(domain string) bool { return r.reg.Has(domain) }
