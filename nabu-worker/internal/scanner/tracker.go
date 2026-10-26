package scanner

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"sort"
	"strconv"
	"time"
)

// TrackerScanner detects third-party trackers loaded by a website.
// It navigates via headless Chrome, captures all outgoing network requests,
// and classifies them against the known tracker database.
//
// This scanner is dispatched by ComplianceRouter for SubType "TRACKER_DETECTION"
// and shares the "COMPLIANCE" MonitorType so no new enum is needed on the Java side.
type TrackerScanner struct {
	headless *HeadlessAllocator
}

// NewTrackerScanner creates a TrackerScanner backed by the shared headless allocator.
func NewTrackerScanner(headless *HeadlessAllocator) *TrackerScanner {
	return &TrackerScanner{headless: headless}
}

// Type returns "COMPLIANCE" — this scanner is not registered directly in the
// consumer's scanner map; it is invoked exclusively via ComplianceRouter.
func (t *TrackerScanner) Type() string { return "COMPLIANCE" }

// detectedTracker represents a single third-party tracker found on the page.
type detectedTracker struct {
	Name         string   `json:"name"`
	Domain       string   `json:"domain"`
	Category     string   `json:"category"`
	RequestCount int      `json:"request_count"`
	SampleURLs   []string `json:"sample_urls"`
}

// maxSampleURLs caps the number of example URLs stored per tracker domain.
const maxSampleURLs = 3

// Scan loads the website in headless Chrome, captures network requests, and
// classifies third-party domains against the known tracker database.
func (t *TrackerScanner) Scan(ctx context.Context, task Task) (*Result, error) {

	if !t.headless.Available() {
		return &Result{
			MonitorID: task.MonitorID,
			Type:      t.Type(),
			Success:   false,
			Details:   map[string]string{"error": "headless Chrome not available"},
			ScannedAt: time.Now().UTC(),
			BatchID:   task.BatchID,
		}, nil
	}

	slog.Info("TrackerScanner: starting scan",
		"monitor_id", task.MonitorID,
		"url", task.WebsiteURL,
	)

	// Derive the site's own domain for first-party filtering.
	siteDomain := apexDomain(task.WebsiteURL)
	if siteDomain == "" {
		return nil, fmt.Errorf("could not extract domain from %q", task.WebsiteURL)
	}

	loc := GetLocalization(task.RegionCode)
	// Hold proxy in reserve — try direct egress first, proxy only on failure.
	proxyURL := loc.HTTPProxy
	loc.HTTPProxy = ""
	_, _, captured, err := t.headless.FetchPageWithNetworkCapture(ctx, task.WebsiteURL, loc)
	if err != nil && proxyURL != "" {
		slog.Info("TrackerScanner: retrying with residential proxy",
			"monitor_id", task.MonitorID,
			"url", task.WebsiteURL,
		)
		proxyCtx, proxyCancel := context.WithTimeout(context.Background(), proxyRetryTimeout)
		proxyLoc := loc
		proxyLoc.HTTPProxy = proxyURL
		_, _, captured, err = t.headless.FetchPageWithNetworkCapture(proxyCtx, task.WebsiteURL, proxyLoc)
		proxyCancel()
	}
	if err != nil {
		slog.Warn("TrackerScanner: headless fetch failed",
			"monitor_id", task.MonitorID,
			"error", err,
		)
		return &Result{
			MonitorID: task.MonitorID,
			Type:      t.Type(),
			Success:   false,
			Details:   map[string]string{"error": err.Error()},
			ScannedAt: time.Now().UTC(),
			BatchID:   task.BatchID,
		}, nil
	}

	// ── Classify captured requests ───────────────────────────────────────────
	type domainStats struct {
		info       TrackerInfo
		count      int
		sampleURLs []string
	}
	trackerMap := make(map[string]*domainStats) // keyed by tracker domain
	thirdPartyCount := 0

	for _, req := range captured {
		if req.Domain == "" || !isThirdParty(req.Domain, siteDomain) {
			continue
		}
		thirdPartyCount++

		info, known := classifyDomain(req.Domain)
		if !known {
			continue
		}

		// Use the canonical tracker domain from the DB as the key.
		key := info.Name + "|" + string(info.Category)
		ds, exists := trackerMap[key]
		if !exists {
			ds = &domainStats{info: info}
			trackerMap[key] = ds
		}
		ds.count++
		if len(ds.sampleURLs) < maxSampleURLs {
			ds.sampleURLs = append(ds.sampleURLs, req.URL)
		}
		// Track the most specific domain seen.
		if ds.info.Name == "" || len(req.Domain) > len(ds.info.Name) {
			ds.info = info
		}
	}

	// ── Build result ─────────────────────────────────────────────────────────
	trackers := make([]detectedTracker, 0, len(trackerMap))
	categories := make(map[string]bool)

	for _, ds := range trackerMap {
		trackers = append(trackers, detectedTracker{
			Name:         ds.info.Name,
			Domain:       ds.sampleURLs[0], // first sample URL for domain reference
			Category:     string(ds.info.Category),
			RequestCount: ds.count,
			SampleURLs:   ds.sampleURLs,
		})
		categories[string(ds.info.Category)] = true
	}

	// Sort by request count descending for consistent output.
	sort.Slice(trackers, func(i, j int) bool {
		return trackers[i].RequestCount > trackers[j].RequestCount
	})

	// Fix Domain field to be the actual domain, not the URL
	for i := range trackers {
		if len(trackers[i].SampleURLs) > 0 {
			trackers[i].Domain = extractDomain(trackers[i].SampleURLs[0])
		}
	}

	status := "CLEAN"
	if len(trackers) > 0 {
		status = "TRACKERS_FOUND"
	}

	categoryList := make([]string, 0, len(categories))
	for cat := range categories {
		categoryList = append(categoryList, cat)
	}
	sort.Strings(categoryList)

	trackersJSON, _ := json.Marshal(trackers)
	categoriesJSON, _ := json.Marshal(categoryList)

	details := map[string]string{
		"status":            status,
		"total_requests":    strconv.Itoa(len(captured)),
		"third_party_count": strconv.Itoa(thirdPartyCount),
		"tracker_count":     strconv.Itoa(len(trackers)),
		"trackers":          string(trackersJSON),
		"categories":        string(categoriesJSON),
		"scanned_via":       "HEADLESS",
	}

	slog.Info("TrackerScanner: scan complete",
		"monitor_id", task.MonitorID,
		"url", task.WebsiteURL,
		"status", status,
		"trackers", len(trackers),
		"third_party", thirdPartyCount,
		"total_requests", len(captured),
	)

	return &Result{
		MonitorID: task.MonitorID,
		Type:      t.Type(),
		Success:   true,
		Details:   details,
		ScannedAt: time.Now().UTC(),
		BatchID:   task.BatchID,
	}, nil
}
