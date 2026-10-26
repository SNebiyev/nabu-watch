package worker

import (
	"sync"
	"testing"
	"time"
)

func TestMark_ThenIsBlocked(t *testing.T) {
	r := newWAFBlockRegistry(5 * time.Minute)

	r.Mark("example.com")

	if !r.IsBlocked("example.com") {
		t.Fatal("expected example.com to be blocked after Mark")
	}
}

func TestExpiry(t *testing.T) {
	r := newWAFBlockRegistry(1 * time.Millisecond)

	r.Mark("example.com")
	time.Sleep(5 * time.Millisecond)

	if r.IsBlocked("example.com") {
		t.Fatal("expected example.com block to have expired")
	}
}

func TestDifferentDomains(t *testing.T) {
	r := newWAFBlockRegistry(5 * time.Minute)

	r.Mark("blocked.com")

	if r.IsBlocked("other.com") {
		t.Fatal("expected other.com to NOT be blocked")
	}
	if !r.IsBlocked("blocked.com") {
		t.Fatal("expected blocked.com to be blocked")
	}
}

func TestConcurrentAccess(t *testing.T) {
	r := newWAFBlockRegistry(5 * time.Minute)

	var wg sync.WaitGroup
	for i := 0; i < 100; i++ {
		wg.Add(2)
		go func() {
			defer wg.Done()
			r.Mark("concurrent.com")
		}()
		go func() {
			defer wg.Done()
			r.IsBlocked("concurrent.com")
		}()
	}
	wg.Wait()

	if !r.IsBlocked("concurrent.com") {
		t.Fatal("expected concurrent.com to be blocked after concurrent marks")
	}
}
