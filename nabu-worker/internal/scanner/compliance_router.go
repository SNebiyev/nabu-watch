package scanner

import "context"

// ComplianceRouter implements Scanner for MonitorType "COMPLIANCE" and
// dispatches to the appropriate sub-scanner based on the task's SubType.
//
// This lets BannerScanner and TrackerScanner share the "COMPLIANCE" MonitorType
// with ComplianceScanner without requiring new MonitorType enums on the Java side
// and without modifying the consumer's scanner dispatch map.
type ComplianceRouter struct {
	keyword *ComplianceScanner
	banner  *BannerScanner
	tracker *TrackerScanner
}

// NewComplianceRouter returns a ComplianceRouter that routes COOKIE_BANNER
// to BannerScanner, TRACKER_DETECTION to TrackerScanner, and everything else
// to ComplianceScanner.
func NewComplianceRouter(keyword *ComplianceScanner, banner *BannerScanner, tracker *TrackerScanner) *ComplianceRouter {
	return &ComplianceRouter{keyword: keyword, banner: banner, tracker: tracker}
}

// Type returns "COMPLIANCE" so the router is registered under the same key as
// the original ComplianceScanner in the consumer's scanner map.
func (r *ComplianceRouter) Type() string { return "COMPLIANCE" }

// Scan dispatches to the appropriate sub-scanner based on SubType.
func (r *ComplianceRouter) Scan(ctx context.Context, task Task) (*Result, error) {
	switch task.SubType {
	case "COOKIE_BANNER":
		return r.banner.Scan(ctx, task)
	case "TRACKER_DETECTION":
		return r.tracker.Scan(ctx, task)
	default:
		return r.keyword.Scan(ctx, task)
	}
}
