package scanner

import (
	"testing"
	"time"
)

func TestHomepageCrawlCache_PutAndGet(t *testing.T) {
	c := newHomepageCrawlCache(5 * time.Minute)

	entry := &crawlEntry{
		rawHTML:    "<html><body><a href='/test'>link</a></body></html>",
		statusCode: 200,
		scannedVia: "HEADLESS",
		cachedAt:   time.Now(),
	}
	c.put("example.com", entry)

	got, ok := c.get("example.com")
	if !ok {
		t.Fatal("expected cache hit")
	}
	if got.rawHTML != entry.rawHTML {
		t.Errorf("rawHTML = %q, want %q", got.rawHTML, entry.rawHTML)
	}
	if got.scannedVia != "HEADLESS" {
		t.Errorf("scannedVia = %q, want HEADLESS", got.scannedVia)
	}
}

func TestHomepageCrawlCache_Miss(t *testing.T) {
	c := newHomepageCrawlCache(5 * time.Minute)

	_, ok := c.get("nonexistent.com")
	if ok {
		t.Fatal("expected cache miss for nonexistent key")
	}
}

func TestHomepageCrawlCache_TTLExpiry(t *testing.T) {
	c := newHomepageCrawlCache(10 * time.Millisecond)

	c.put("example.com", &crawlEntry{
		rawHTML:    "<html></html>",
		statusCode: 200,
		scannedVia: "STATIC",
		cachedAt:   time.Now(),
	})

	time.Sleep(20 * time.Millisecond)

	_, ok := c.get("example.com")
	if ok {
		t.Fatal("expected cache miss after TTL expiry")
	}
}

func TestHomepageCrawlCache_Reparse(t *testing.T) {
	entry := &crawlEntry{
		rawHTML:    "<html><body><a href='/privacy'>Privacy</a></body></html>",
		statusCode: 200,
		scannedVia: "HEADLESS",
		cachedAt:   time.Now(),
	}

	doc, err := entry.reparse()
	if err != nil {
		t.Fatalf("reparse error: %v", err)
	}

	links := doc.Find("a")
	if links.Length() != 1 {
		t.Errorf("expected 1 link, got %d", links.Length())
	}
}

func TestCacheKey_WithBatchID(t *testing.T) {
	key := cacheKey("batch-123", "example.com")
	if key != "batch-123:example.com" {
		t.Errorf("cacheKey = %q, want %q", key, "batch-123:example.com")
	}
}

func TestCacheKey_WithoutBatchID(t *testing.T) {
	key := cacheKey("", "example.com")
	if key != "example.com" {
		t.Errorf("cacheKey = %q, want %q", key, "example.com")
	}
}

func TestApexDomain(t *testing.T) {
	tests := []struct {
		input string
		want  string
	}{
		{"https://www.example.com/path", "example.com"},
		{"https://example.com", "example.com"},
		{"https://sub.example.com", "sub.example.com"},
		{"invalid", ""},
	}
	for _, tt := range tests {
		got := apexDomain(tt.input)
		if got != tt.want {
			t.Errorf("apexDomain(%q) = %q, want %q", tt.input, got, tt.want)
		}
	}
}
