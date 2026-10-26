package worker

import "time"

// batchAbortRegistry tracks website batches that have been aborted by the scout
// scanner (AI confirmed the homepage is not scannable). Subsequent monitors for
// the same batchId+domain are fast-failed without entering the scanner.
type batchAbortRegistry struct{ reg *ttlRegistry }

func newBatchAbortRegistry(ttl time.Duration) *batchAbortRegistry {
	return &batchAbortRegistry{reg: newTTLRegistry(ttl)}
}

func (r *batchAbortRegistry) Mark(batchID, domain string) { r.reg.Mark(batchID + ":" + domain) }
func (r *batchAbortRegistry) IsAborted(batchID, domain string) bool {
	if batchID == "" {
		return false
	}
	return r.reg.Has(batchID + ":" + domain)
}
