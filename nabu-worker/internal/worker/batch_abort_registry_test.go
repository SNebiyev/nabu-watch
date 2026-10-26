package worker

import (
	"sync"
	"testing"
	"time"
)

func TestBatchAbort_MarkAndIsAborted(t *testing.T) {
	r := newBatchAbortRegistry(5 * time.Minute)
	r.Mark("batch-1", "example.com")

	if !r.IsAborted("batch-1", "example.com") {
		t.Fatal("expected batch-1:example.com to be aborted")
	}
}

func TestBatchAbort_DifferentBatch(t *testing.T) {
	r := newBatchAbortRegistry(5 * time.Minute)
	r.Mark("batch-1", "example.com")

	if r.IsAborted("batch-2", "example.com") {
		t.Fatal("expected batch-2:example.com NOT to be aborted")
	}
}

func TestBatchAbort_EmptyBatchID(t *testing.T) {
	r := newBatchAbortRegistry(5 * time.Minute)
	r.Mark("batch-1", "example.com")

	if r.IsAborted("", "example.com") {
		t.Fatal("expected empty batchId to return false")
	}
}

func TestBatchAbort_Expiry(t *testing.T) {
	r := newBatchAbortRegistry(1 * time.Millisecond)
	r.Mark("batch-1", "example.com")
	time.Sleep(5 * time.Millisecond)

	if r.IsAborted("batch-1", "example.com") {
		t.Fatal("expected abort to have expired")
	}
}

func TestBatchAbort_ConcurrentAccess(t *testing.T) {
	r := newBatchAbortRegistry(5 * time.Minute)
	var wg sync.WaitGroup
	for i := 0; i < 100; i++ {
		wg.Add(2)
		go func() {
			defer wg.Done()
			r.Mark("batch-1", "concurrent.com")
		}()
		go func() {
			defer wg.Done()
			r.IsAborted("batch-1", "concurrent.com")
		}()
	}
	wg.Wait()
}
