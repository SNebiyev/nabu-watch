package scanner

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"math/rand/v2"
	"net/url"
	"os"
	"os/exec"
	"runtime"
	"strings"
	"sync"
	"time"

	"github.com/PuerkitoBio/goquery"
	"github.com/chromedp/cdproto/cdp"
	"github.com/chromedp/cdproto/emulation"
	"github.com/chromedp/cdproto/fetch"
	"github.com/chromedp/cdproto/network"
	"github.com/chromedp/cdproto/page"
	"github.com/chromedp/chromedp"
)

const (
	headlessFetchTimeout = 60 * time.Second // multi-phase consent wall: pre-click + click + post-click
	hardResetPreNavDelay = 5 * time.Second  // pre-navigation wait on hard-reset retry to let rate-limit windows reset
)

// shadowDOMExtractorJS is executed in the rendered browser context after the
// banner delay.  It returns the full outer HTML of the page *plus* a synthetic
// <div> that contains every <a href> found in:
//
//   - The main document (already in outerHTML — included for completeness)
//   - Every Shadow Root, recursively (OneTrust / Cookiebot use shadow-dom)
//   - Every <iframe>, including cross-origin CMP frames (Sourcepoint, Usercentrics)
//     — cross-origin access requires Chrome to be launched with --disable-web-security
//
// The synthetic block is appended after </html> so Go's html.Parse (used by
// goquery) absorbs it into the document body — all without mutating the live
// DOM visible to the page's own JS.
//
// Design notes:
//   - Uses try/catch everywhere so a single broken shadow root never aborts the walk.
//   - Deduplicates hrefs via a plain object hash (O(1) per link).
//   - Encodes attribute characters that could break the generated markup.
const shadowDOMExtractorJS = `(function(){
  'use strict';
  var links=[];
  var seen={};

  function esc(s){
    return (s||'').replace(/&/g,'&amp;').replace(/"/g,'&quot;');
  }
  function safeText(s){
    return (s||'').trim().replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
  }

  function add(href,text){
    if(!href||seen[href])return;
    seen[href]=true;
    links.push('<a href="'+esc(href)+'">'+safeText(text)+'</a>');
  }

  function walk(root){
    if(!root)return;

    // ── Section 1: anchor tags ────────────────────────────────────────────
    // Each section is independently wrapped so a failure in one does not
    // abort shadow-root or iframe extraction below.
    try{
      root.querySelectorAll('a[href]').forEach(function(a){
        // a.href is the resolved absolute URL (browser does this for free).
        add(a.href||a.getAttribute('href')||'', a.textContent||'');
      });
    }catch(e){}

    // ── Section 2: shadow roots ───────────────────────────────────────────
    try{
      root.querySelectorAll('*').forEach(function(el){
        if(el.shadowRoot) walk(el.shadowRoot);
      });
    }catch(e){}

    // ── Section 3: iframes ────────────────────────────────────────────────
    // With --disable-web-security cross-origin iframes are now accessible.
    // contentWindow.document is tried as a fallback for iframes that return
    // null from contentDocument (observed with sandboxed iframes and some CMPs).
    // Each iframe is individually wrapped so one broken frame never stops the rest.
    try{
      root.querySelectorAll('iframe').forEach(function(f){
        try{
          var d=f.contentDocument||(f.contentWindow&&f.contentWindow.document);
          if(d&&d.body) walk(d.body);
        }catch(e){}
      });
    }catch(e){}
  }

  walk(document.body);

  // Append the synthesized shadow links after the main HTML.
  // Go's html.Parse is lenient about content after </html>.
  return document.documentElement.outerHTML+
    '\n<div id="nabu-shadow-links" style="display:none">'+
    links.join('')+'</div>';
})()`

// bannerDOMExtractorJS is used by FetchBannerPage instead of shadowDOMExtractorJS.
// It appends the full innerHTML of every accessible iframe AND every shadow
// root so that goquery selectors (class names, IDs, buttons) from nested CMP
// iframes and Shadow DOM web components are visible in the merged document.
//
// Why iframe recursion: Sourcepoint-style CMPs nest the preferences panel
// (with Reject buttons) inside a second iframe under the initial banner frame.
//
// Why shadow-root traversal: custom in-house consent layers (e.g. bahn.de)
// ship as web components whose entire banner markup — heading, Accept/Reject
// buttons — lives inside a shadow root and is invisible to a plain
// document.outerHTML dump. Without walking it, rule-based detection and the
// AI fallback both see an empty page.
const bannerDOMExtractorJS = `(function(){
  'use strict';
  var parts=[document.documentElement.outerHTML];
  var visited=[];

  function alreadySeen(n){
    for(var i=0;i<visited.length;i++){if(visited[i]===n)return true;}
    return false;
  }

  // Walk shadow roots under root (a Document or ShadowRoot) and dump their
  // innerHTML so CSS selectors in the merged document can reach them.
  function dumpShadows(root,depth){
    if(!root||depth>4)return;
    try{
      root.querySelectorAll('*').forEach(function(el){
        if(el.shadowRoot){
          if(alreadySeen(el.shadowRoot))return;
          visited.push(el.shadowRoot);
          try{
            var html=el.shadowRoot.innerHTML;
            if(html) parts.push('<div class="nabu-shadow-dump" style="display:none">'+html+'</div>');
          }catch(e){}
          // Shadow roots can themselves host nested shadow roots / iframes.
          dumpShadows(el.shadowRoot,depth+1);
          try{
            el.shadowRoot.querySelectorAll('iframe').forEach(function(f){
              try{
                var nd=f.contentDocument||(f.contentWindow&&f.contentWindow.document);
                if(nd) dumpFrame(nd,depth+1);
              }catch(e){}
            });
          }catch(e){}
        }
      });
    }catch(e){}
  }

  function dumpFrame(d,depth){
    if(!d||!d.body||depth>4)return;
    if(alreadySeen(d))return;
    visited.push(d);
    try{
      var html=d.body.innerHTML;
      if(html) parts.push('<div class="nabu-iframe-dump" style="display:none">'+html+'</div>');
    }catch(e){}
    // Shadow roots inside this frame
    dumpShadows(d,depth+1);
    // Recurse into nested iframes (e.g. Sourcepoint preferences panel)
    try{
      d.querySelectorAll('iframe').forEach(function(f){
        try{
          var nd=f.contentDocument||(f.contentWindow&&f.contentWindow.document);
          if(nd) dumpFrame(nd,depth+1);
        }catch(e){}
      });
    }catch(e){}
  }

  // Shadow roots on the main document
  dumpShadows(document,1);

  // Iframes on the main document
  try{
    document.querySelectorAll('iframe').forEach(function(f){
      try{
        var d=f.contentDocument||(f.contentWindow&&f.contentWindow.document);
        if(d) dumpFrame(d,1);
      }catch(e){}
    });
  }catch(e){}

  return parts.join('\n');
})()`

// consentClickerJS finds the first visible button/link whose text matches a
// known consent-dismissal label and clicks it.  Used by FetchPage
// (compliance/SSL scanners) to dismiss the overlay so the scanner can reach
// the underlying page content.  Walks the main document and all accessible
// iframes so CMP banners loaded inside cross-origin frames are also dismissed.
const consentClickerJS = `(function(){
  var labels=['Schließen','Zustimmen','Accept','Akzeptieren','Weiter','Agree','Close','Continue'];

  function tryClickIn(root){
    if(!root)return false;
    var all=Array.from(root.querySelectorAll('button,a,[role="button"]'));
    for(var i=0;i<all.length;i++){
      var el=all[i];
      var lower=(el.innerText||el.textContent||'').trim().toLowerCase();
      for(var j=0;j<labels.length;j++){
        if(lower===labels[j].toLowerCase()){
          try{
            var r=el.getBoundingClientRect();
            if(r.width>0&&r.height>0){el.click();return true;}
          }catch(e){}
        }
      }
    }
    var frames=Array.from(root.querySelectorAll('iframe'));
    for(var k=0;k<frames.length;k++){
      try{
        var d=frames[k].contentDocument||(frames[k].contentWindow&&frames[k].contentWindow.document);
        if(d&&d.body&&tryClickIn(d.body))return true;
      }catch(e){}
    }
    return false;
  }

  return tryClickIn(document.body);
})()`

// prefsClickerJS opens the cookie-preferences panel on sites that use a
// two-layer consent flow (e.g. Sourcepoint): the initial overlay
// only shows "Accept" and "Preferences"; Reject options are only revealed after
// opening the preferences panel.  Used by FetchBannerPage (BannerScanner).
//
// Walks both the main document AND all accessible iframes (Sourcepoint,
// Usercentrics, and similar CMPs load their consent UI inside cross-origin
// iframes — --disable-web-security is required for contentDocument access).
const prefsClickerJS = `(function(){
  var labels=['preferences','settings','manage','customize','options','einstellungen',
              'datenschutzeinstellungen','privacy settings','more options',
              'manage preferences','cookie settings','gérer','paramètres',
              'meer opties','privacy center','manage cookies','customize cookies',
              'cookie preferences'];

  function tryClickIn(root){
    if(!root)return '';
    var all=Array.from(root.querySelectorAll('button,a,[role="button"]'));
    for(var i=0;i<all.length;i++){
      var el=all[i];
      var txt=(el.innerText||el.textContent||'').trim().toLowerCase();
      for(var j=0;j<labels.length;j++){
        if(txt===labels[j]||txt.includes(labels[j])){
          try{
            var r=el.getBoundingClientRect();
            if(r.width>0&&r.height>0){el.click();return txt;}
          }catch(e){}
        }
      }
    }
    // Recurse into iframes (Sourcepoint / Usercentrics load banner in iframes)
    var frames=Array.from(root.querySelectorAll('iframe'));
    for(var k=0;k<frames.length;k++){
      try{
        var d=frames[k].contentDocument||(frames[k].contentWindow&&frames[k].contentWindow.document);
        if(d&&d.body){var res=tryClickIn(d.body);if(res)return res;}
      }catch(e){}
    }
    return '';
  }

  return tryClickIn(document.body);
})()`

// blockedResourceTypes lists CDP resource types we abort before they are sent.
// Blocking images, fonts, stylesheets and media cuts page-load time by ~60-80 %
// while preserving all JS execution (which is what loads OneTrust banners).
var blockedResourceTypes = map[network.ResourceType]bool{
	network.ResourceTypeImage:      true,
	network.ResourceTypeStylesheet: true,
	network.ResourceTypeMedia:      true,
	network.ResourceTypeFont:       true,
}

// HeadlessAllocator manages a single shared Chrome process.
// Every headless page fetch opens a new tab (browser context) from that process,
// avoiding the heavy cost of spawning Chrome from scratch per request.
//
// When Chrome is not installed the allocator operates in no-op mode:
// Available() returns false and every FetchPage call returns an error.
// This lets the rest of the scanner gracefully fall back to static HTTP.
//
// Optional: if a FlareSolverrClient is configured (via NABU_FLARESOLVERR_URL),
// it is available as a last-resort tier for sites that completely block CDP.
type HeadlessAllocator struct {
	allocCtx    context.Context
	allocCancel context.CancelFunc
	available   bool
	chromePath  string              // absolute path resolved during init (for diagnostics)
	Flare       *FlareSolverrClient // non-nil when NABU_FLARESOLVERR_URL is set

	// Per-proxy allocator pool. Each distinct upstream proxy URL gets its
	// own chromedp allocator (backed by a separate Chrome process) because
	// --proxy-server is an allocator-level flag in chromedp. Populated
	// lazily and freed by Close(). A sync.Once per URL guarantees the
	// 10-second Chrome smoke-test runs only once per proxy and never
	// blocks scans of *other* proxies — we hold proxyMu only long enough
	// to look up/create the Once.
	proxyMu     sync.RWMutex
	proxyAllocs map[string]*proxyAlloc
	closed      bool
}

type proxyAlloc struct {
	once     sync.Once
	ctx      context.Context
	cancel   context.CancelFunc
	username string
	password string
}

// NewHeadlessAllocator discovers the Chrome/Chromium binary and starts a shared
// Chrome process using headless flags appropriate for the current OS.
//
// Discovery priority (first match wins):
//  1. NABU_CHROME_PATH environment variable — explicit host/CI override
//  2. exec.LookPath — searches $PATH (handles Homebrew, apt, custom installs)
//  3. Well-known OS absolute paths — fallback for locked-down environments
//     where the binary directory is not on $PATH
//
// If no binary is found, or if Chrome fails the startup smoke-test (e.g. a
// missing shared library on a minimal Ubuntu install), the allocator is
// returned in no-op mode so FetchPage falls back to static HTTP scanning.
// Call Close() on worker shutdown to terminate the Chrome process.
func NewHeadlessAllocator() *HeadlessAllocator {
	h := &HeadlessAllocator{
		proxyAllocs: make(map[string]*proxyAlloc),
	}

	// Wire in FlareSolverr if configured — independent of Chrome availability.
	if flareURL := os.Getenv("NABU_FLARESOLVERR_URL"); flareURL != "" {
		h.Flare = NewFlareSolverrClient(flareURL)
		slog.Info("FlareSolverr client configured", "url", flareURL)
	}

	slog.Info("Headless allocator: probing for Chrome/Chromium", "os", runtime.GOOS)

	path, method, err := findChromeBinary()
	if err != nil {
		slog.Warn("Headless mode disabled — Chrome binary not found",
			"os", runtime.GOOS,
			"reason", err.Error(),
			"hint", "Install Chromium or set the NABU_CHROME_PATH environment variable",
		)
		return h
	}

	slog.Info("Chrome binary resolved", "path", path, "method", method, "os", runtime.GOOS)
	h.chromePath = path

	opts := buildAllocatorOpts(path, "")

	allocCtx, allocCancel := chromedp.NewExecAllocator(context.Background(), opts...)

	// Smoke-test: open a throwaway context to confirm Chrome actually starts.
	// Catches missing shared libraries, SUID sandbox issues, etc. before any
	// real scan request hits the allocator.
	smokeCtx, smokeCancel := chromedp.NewContext(allocCtx)
	defer smokeCancel()
	smokeCtx, smokeTimeout := context.WithTimeout(smokeCtx, 10*time.Second)
	defer smokeTimeout()
	if err := chromedp.Run(smokeCtx); err != nil {
		allocCancel()
		slog.Warn("Headless mode disabled — Chrome failed to start",
			"path", path,
			"os", runtime.GOOS,
			"error", err.Error(),
			"hint", "On Ubuntu: sudo apt-get install -y libnss3 libatk1.0-0 libatk-bridge2.0-0 libcups2 libdrm2 libxkbcommon0 libxcomposite1 libxdamage1 libxfixes3 libxrandr2 libgbm1 libasound2",
		)
		return h
	}

	h.allocCtx = allocCtx
	h.allocCancel = allocCancel
	h.available = true

	slog.Info("Headless Chrome allocator ready", "path", path, "os", runtime.GOOS)
	return h
}

// allocatorFor returns the chromedp allocator context for the given
// upstream proxy URL, falling back to the default (direct-egress) allocator
// when the URL is empty, the scanner is unavailable, or proxy start-up
// fails. The first call for a new URL pays the ~10-second Chrome smoke-test
// cost; subsequent calls are O(1). Concurrent callers for the *same* URL
// wait on one sync.Once; concurrent callers for *different* URLs don't
// block each other.
func (h *HeadlessAllocator) allocatorFor(proxyURL string) context.Context {
	if proxyURL == "" || !h.available {
		return h.allocCtx
	}

	// Fast path: cached entry.
	h.proxyMu.RLock()
	pa := h.proxyAllocs[proxyURL]
	closed := h.closed
	h.proxyMu.RUnlock()
	if closed {
		return h.allocCtx
	}

	// Slow path: register a new entry under a short write-lock window,
	// then initialize outside the lock so other URLs aren't blocked.
	if pa == nil {
		h.proxyMu.Lock()
		if h.closed {
			h.proxyMu.Unlock()
			return h.allocCtx
		}
		pa = h.proxyAllocs[proxyURL]
		if pa == nil {
			pa = &proxyAlloc{}
			h.proxyAllocs[proxyURL] = pa
		}
		h.proxyMu.Unlock()
	}

	pa.once.Do(func() {
		// Extract proxy credentials for CDP Fetch.authRequired handling.
		// Chrome's --proxy-server flag does not accept inline user:pass, so we
		// cache them here and supply them via ContinueWithAuth in the tab listener.
		if u, err := url.Parse(proxyURL); err == nil && u.User != nil {
			pa.username = u.User.Username()
			pa.password, _ = u.User.Password()
		}
		opts := buildAllocatorOpts(h.chromePath, proxyURL)
		ctx, cancel := chromedp.NewExecAllocator(context.Background(), opts...)
		smokeCtx, smokeCancel := chromedp.NewContext(ctx)
		smokeTimeoutCtx, smokeTimeoutCancel := context.WithTimeout(smokeCtx, 10*time.Second)
		smokeErr := chromedp.Run(smokeTimeoutCtx)
		smokeTimeoutCancel()
		smokeCancel()
		if smokeErr != nil {
			cancel()
			slog.Warn("Proxy allocator start-up failed — falling back to direct egress",
				"proxy", redactProxy(proxyURL), "error", smokeErr)
			return
		}
		pa.ctx = ctx
		pa.cancel = cancel
		slog.Info("Proxy allocator ready", "proxy", redactProxy(proxyURL))
	})

	if pa.ctx == nil {
		return h.allocCtx
	}
	return pa.ctx
}

// handleProxyAuth responds to a CDP Fetch.authRequired event by providing the
// cached proxy credentials. If no credentials are configured, the request is
// failed gracefully. Runs synchronously — callers must invoke from a goroutine
// to avoid blocking the CDP event dispatcher.
func (h *HeadlessAllocator) handleProxyAuth(tabCtx context.Context, requestID fetch.RequestID, proxyURL string) {
	fc := chromedp.FromContext(tabCtx)
	if fc == nil || fc.Target == nil {
		return // tab already torn down
	}
	executor := cdp.WithExecutor(tabCtx, fc.Target)

	username, password := h.proxyCredsFor(proxyURL)
	if username == "" {
		if err := fetch.FailRequest(requestID, network.ErrorReasonAccessDenied).Do(executor); err != nil &&
			!errors.Is(err, context.Canceled) && !errors.Is(err, context.DeadlineExceeded) {
			slog.Debug("proxy auth fail-request error", "proxy", redactProxy(proxyURL), "error", err)
		}
		return
	}
	cmd := fetch.ContinueWithAuth(requestID, &fetch.AuthChallengeResponse{
		Response: fetch.AuthChallengeResponseResponseProvideCredentials,
		Username: username,
		Password: password,
	})
	if err := cmd.Do(executor); err != nil &&
		!errors.Is(err, context.Canceled) && !errors.Is(err, context.DeadlineExceeded) {
		slog.Debug("proxy auth continue error", "proxy", redactProxy(proxyURL), "error", err)
	}
}

// redactProxy masks the password component of a proxy URL for safe logging.
// Input: "http://user:pass@host:port" → "http://user:***@host:port"
func redactProxy(proxyURL string) string {
	at := strings.LastIndex(proxyURL, "@")
	if at < 0 {
		return proxyURL
	}
	scheme := strings.Index(proxyURL, "://")
	if scheme < 0 || scheme+3 >= at {
		return proxyURL
	}
	userInfo := proxyURL[scheme+3 : at]
	colon := strings.Index(userInfo, ":")
	if colon < 0 {
		return proxyURL
	}
	return proxyURL[:scheme+3] + userInfo[:colon] + ":***" + proxyURL[at:]
}

// proxyCredsFor looks up the cached proxy credentials for the given URL.
// Returns empty strings if no proxy is configured or the URL has no credentials.
func (h *HeadlessAllocator) proxyCredsFor(proxyURL string) (username, password string) {
	if proxyURL == "" {
		return "", ""
	}
	h.proxyMu.RLock()
	pa := h.proxyAllocs[proxyURL]
	h.proxyMu.RUnlock()
	if pa == nil {
		return "", ""
	}
	return pa.username, pa.password
}

// geoOverrideActions returns the CDP actions that align the browser's
// geolocation API and Intl timezone with the region being scanned. Some
// CMPs cross-check these client-side signals and disable themselves on a
// mismatch; when the worker also has a regional proxy wired in, the
// client-side story matches the server-side IP and geo-gated banners
// render as they would for a local visitor.
func geoOverrideActions(loc LocalizationConfig) chromedp.Tasks {
	return chromedp.Tasks{
		emulation.SetGeolocationOverride().
			WithLatitude(loc.Latitude).
			WithLongitude(loc.Longitude).
			WithAccuracy(100),
		emulation.SetTimezoneOverride(loc.TimeZone),
	}
}

// buildAllocatorOpts constructs the chromedp exec-allocator option slice.
// Flags shared across all platforms come first; Linux-specific stability flags
// are appended only when GOOS == "linux" so macOS runs without unnecessary
// (and potentially security-reducing) overrides.
//
// Images are disabled at the Blink level (blink-settings) as a first-pass
// speed optimisation; the fetch-domain interception layer in FetchPage adds a
// second pass to block stylesheets, fonts and media per-tab.
func buildAllocatorOpts(chromePath string, proxyURL string) []chromedp.ExecAllocatorOption {
	opts := append(chromedp.DefaultExecAllocatorOptions[:],
		// Pin the binary — never rely on chromedp's own lookup after we
		// have already resolved the path.
		chromedp.ExecPath(chromePath),

		// ── Universal headless flags ──────────────────────────────────────
		chromedp.Flag("headless", true),
		chromedp.Flag("disable-gpu", true),
		chromedp.Flag("disable-extensions", true),
		// Images disabled at Blink level — fast pre-filter before fetch interception.
		chromedp.Flag("blink-settings", "imagesEnabled=false"),

		// Port 0 tells Chrome to auto-assign a free ephemeral port for the
		// DevTools WebSocket.  This prevents conflicts when multiple worker
		// processes run on the same host.
		chromedp.Flag("remote-debugging-port", "0"),

		// Suppress Chrome's own certificate error UI so SSL-expired sites
		// still render their legal pages (we record SSL status separately).
		chromedp.Flag("ignore-certificate-errors", true),

		// ── Cross-origin iframe piercing ──────────────────────────────────
		// CMP banners (Sourcepoint, OneTrust, Cookiebot) are loaded inside
		// cross-origin iframes.  Without these flags, contentDocument access
		// from our JS extractor throws a DOMException and we get no links
		// from the consent layer (observed with Sourcepoint).
		//
		// Security note: these flags are intentional for an automated scanner
		// that never browses user-owned sites — they must NOT be used in a
		// user-facing browser profile.
		chromedp.Flag("disable-web-security", true),
		chromedp.Flag("disable-site-isolation-trials", true),
		chromedp.Flag("disable-features", "IsolateOrigins,site-per-process"),
	)

	if runtime.GOOS == "linux" {
		opts = append(opts,
			// no-sandbox: mandatory when Chrome runs as root (the default in
			// Docker containers) or in kernels without user-namespace support.
			// Safe to omit on macOS where the OS sandbox is always available.
			chromedp.Flag("no-sandbox", true),

			// disable-dev-shm-usage: /dev/shm on many Linux servers and
			// containers is only 64 MB.  Chrome writes its renderer shared
			// memory there and crashes when it fills up.  This flag redirects
			// shared memory writes to /tmp which is typically much larger.
			chromedp.Flag("disable-dev-shm-usage", true),
		)
	}

	// Per-allocator upstream proxy — routes every fetch through the region's
	// residential/datacenter proxy so server-side geo-gated CMPs see the
	// expected origin IP. Chrome's --proxy-server flag does not support inline
	// credentials (user:pass@host:port) — those must be supplied via the CDP
	// Fetch.continueWithAuth response to Fetch.authRequired events instead.
	if proxyURL != "" {
		// Strip user:pass from the URL; pass only scheme://host:port to Chrome.
		if u, err := url.Parse(proxyURL); err == nil && u.User != nil {
			stripped := u.Scheme + "://" + u.Host
			opts = append(opts, chromedp.Flag("proxy-server", stripped))
		} else {
			opts = append(opts, chromedp.Flag("proxy-server", proxyURL))
		}
	}

	return opts
}

// Available reports whether a Chrome process is ready for headless fetches.
func (h *HeadlessAllocator) Available() bool { return h.available }

// FetchPage launches a new browser tab, navigates to rawURL, waits for async
// consent-management platforms (OneTrust, Cookiebot, etc.) to inject their
// banners, then extracts the fully-rendered DOM — including all Shadow DOM
// subtrees and accessible iframes — and returns it as a *goquery.Document.
//
// # WAF Evasion Stack (applied in this order)
//
//  1. Stealth JS (page.AddScriptToEvaluateOnNewDocument) — injected BEFORE
//     any inline script runs, so navigator.webdriver is already undefined when
//     the page's own bot-detection fires.  Also spoofs hardwareConcurrency,
//     deviceMemory, screen dimensions, and maxTouchPoints to match a consumer
//     iPhone rather than a headless server.
//
//  2. Network request interception (Fetch domain) — aborts Image, Stylesheet,
//     Font, and Media requests before they leave the browser, cutting load time
//     by ~70 % while preserving all JS (which is what loads cookie banners).
//
//  3. Mobile device emulation — iPhone 14 Pro viewport (390×844, DPR 3, touch).
//     Many Akamai / Cloudflare rule-sets have looser thresholds for mobile
//     traffic, so this is now the DEFAULT rather than a retry fallback.
//
//  4. Banner wait (3–5 s jitter) — gives CMPs time to finish their async fetch
//     and inject their shadow-DOM banners before we extract the DOM.
//
//  5. Human scroll — scrollBy(0, 500) + full scroll-to-bottom before capture
//     triggers lazy-loaded footers and satisfies scroll-depth bot signals.
//
//  6. Shadow DOM + iframe extraction — a JS payload pierces all shadow roots
//     and same-origin iframes to collect every <a href> the regular outerHTML
//     would have missed (e.g. OneTrust's "Cookie Settings" link).
//
//  7. Hard-reset retry — if the rendered HTML is shorter than
//     isWAFShellPage() (HTML < 10 KB or block-page markers), a second attempt
//     is made using a fresh browser context (no cookies), a random desktop UA,
//     and a 5-second pre-navigation pause to let Akamai's rate window reset.
//
// acceptLang controls the Accept-Language / navigator.language override.
// ua overrides the User-Agent; pass "" to use a random iPhone/Safari string.
// Each call gets its own isolated browser context (tab); no state leaks.
func (h *HeadlessAllocator) FetchPage(ctx context.Context, rawURL string, loc LocalizationConfig, ua string) (int, *goquery.Document, error) {
	return h.fetchPageImpl(ctx, rawURL, loc, ua, false, consentClickerJS, shadowDOMExtractorJS, nil)
}

// FetchPageHardReset fetches rawURL using a fresh browser context, a random
// desktop User-Agent, and a 5-second pre-navigation delay.  It is the final
// escalation tier for sites that block the mobile headless pass (e.g. Akamai
// WAFs that allow desktop traffic on a second attempt after the rate window resets).
func (h *HeadlessAllocator) FetchPageHardReset(ctx context.Context, rawURL string, loc LocalizationConfig) (int, *goquery.Document, error) {
	return h.fetchPageImpl(ctx, rawURL, loc, RandomUA(), true, consentClickerJS, shadowDOMExtractorJS, nil)
}

// FetchBannerPage is a variant of FetchPage designed for BannerScanner.
// Instead of clicking an accept/dismiss button (which would close the banner),
// it clicks the first visible preferences/settings button to open the granular
// controls panel. This reveals Reject options that are only present in the
// second layer of two-step consent flows (e.g. news publishers).
// The returned document merges the pre-click DOM (initial banner) and the
// post-click DOM (preferences panel) so button detection sees both layers.
func (h *HeadlessAllocator) FetchBannerPage(ctx context.Context, rawURL string, loc LocalizationConfig, ua string) (int, *goquery.Document, error) {
	return h.fetchPageImpl(ctx, rawURL, loc, ua, false, prefsClickerJS, bannerDOMExtractorJS, nil)
}

// FetchBannerPageWithScreenshot is identical to FetchBannerPage but also
// captures a JPEG screenshot after the post-click DOM extraction.
func (h *HeadlessAllocator) FetchBannerPageWithScreenshot(ctx context.Context, rawURL string, loc LocalizationConfig, ua string) (int, *goquery.Document, []byte, error) {
	var ssBuf []byte
	code, doc, err := h.fetchPageImpl(ctx, rawURL, loc, ua, false, prefsClickerJS, bannerDOMExtractorJS, &ssBuf)
	return code, doc, ssBuf, err
}

// fetchPageImpl is the internal implementation of FetchPage / FetchBannerPage.
// isRetry=true skips the block-page retry guard (preventing infinite recursion)
// and prepends a hardResetPreNavDelay before navigation.
// clickerJS is the JavaScript snippet executed between the two DOM extractions;
// pass consentClickerJS to dismiss accept overlays or prefsClickerJS to open
// the preferences panel for two-layer banner scanning.
// screenshotBuf, when non-nil, receives a JPEG screenshot captured after the
// post-click DOM extraction.  Pass nil to skip screenshot capture.
func (h *HeadlessAllocator) fetchPageImpl(ctx context.Context, rawURL string, loc LocalizationConfig, ua string, isRetry bool, clickerJS, extractorJS string, screenshotBuf *[]byte) (int, *goquery.Document, error) {
	if !h.available {
		return 0, nil, fmt.Errorf("headless mode not available")
	}
	acceptLang := loc.AcceptLanguage
	if acceptLang == "" {
		acceptLang = "en-US,en;q=0.9"
	}
	// Default to mobile UA — Akamai / Cloudflare WAFs are measurably more
	// permissive toward iPhone Safari traffic than desktop Chrome.
	if ua == "" {
		ua = MobileUA()
	}

	tabCtx, tabCancel := chromedp.NewContext(h.allocatorFor(loc.HTTPProxy))
	defer tabCancel()

	deadline := time.Now().Add(headlessFetchTimeout)
	if d, ok := ctx.Deadline(); ok && d.Before(deadline) {
		deadline = d
	}
	tabCtx, deadlineCancel := context.WithDeadline(tabCtx, deadline)
	defer deadlineCancel()

	// Propagate parent cancellation into the tab context.
	go func() {
		select {
		case <-ctx.Done():
			deadlineCancel()
		case <-tabCtx.Done():
		}
	}()

	primaryLang := acceptLang
	if idx := strings.IndexByte(acceptLang, ','); idx > 0 {
		primaryLang = acceptLang[:idx]
	}

	// ── Stealth JS — injected at document creation via AddScriptToEvaluateOnNewDocument ──
	//
	// IMPORTANT: chromedp.Evaluate() before Navigate() runs in the about:blank
	// context and is wiped when the page navigates.  AddScriptToEvaluateOnNewDocument
	// guarantees injection BEFORE any inline script on the actual page sees the DOM,
	// which is the only way to reliably hide navigator.webdriver from eager detectors.
	stealthJS := buildStealthJS(primaryLang)

	// ── Network request interception ─────────────────────────────────────────
	// Register the listener BEFORE chromedp.Run so that events fired during
	// fetch.Enable() (and subsequent navigation) are never dropped.
	// Each paused request must be explicitly continued or failed; otherwise the
	// browser tab stalls indefinitely waiting for a response to the pause.
	chromedp.ListenTarget(tabCtx, func(ev interface{}) {
		switch e := ev.(type) {
		case *fetch.EventRequestPaused:
			go func() {
				var action chromedp.Action
				blocked := blockedResourceTypes[e.ResourceType]
				// When capturing screenshots, allow stylesheets so the page renders
				// with proper layout and styling (otherwise it looks like raw HTML).
				if blocked && screenshotBuf != nil && e.ResourceType == network.ResourceTypeStylesheet {
					blocked = false
				}
				if blocked {
					// Abort blocked types immediately — no round-trip to the server.
					action = fetch.FailRequest(e.RequestID, network.ErrorReasonBlockedByClient)
				} else {
					// Allow Document, Script, XHR, Fetch, WebSocket, etc.
					action = fetch.ContinueRequest(e.RequestID)
				}
				if err := chromedp.Run(tabCtx, action); err != nil &&
					!errors.Is(err, context.Canceled) &&
					!errors.Is(err, context.DeadlineExceeded) {
					slog.Debug("fetch interception action error", "url", rawURL, "error", err)
				}
			}()
		case *fetch.EventAuthRequired:
			go h.handleProxyAuth(tabCtx, e.RequestID, loc.HTTPProxy)
		}
	})

	// Hard-reset pre-navigation delay: on retry attempts (isRetry=true) we
	// pause before touching the network at all.  This lets Akamai's short
	// per-IP rate-limit window (typically 3–10 s) expire so the next request
	// is treated as a fresh session rather than a burst continuation.
	if isRetry {
		slog.Info("FetchPage hard-reset: waiting before retry navigation",
			"url", rawURL,
			"wait", hardResetPreNavDelay,
		)
		select {
		case <-time.After(hardResetPreNavDelay):
		case <-tabCtx.Done():
			return 0, nil, fmt.Errorf("headless hard-reset cancelled: %w", tabCtx.Err())
		}
	}

	// Banner load jitter: 3–5 s to allow OneTrust / Cookiebot / CookiePro to
	// complete their async bootstraps and inject their shadow-DOM banners.
	// These CMPs typically take 1.5–3 s after DOMContentLoaded.
	bannerDelay := time.Duration(3000+rand.IntN(2001)) * time.Millisecond

	var htmlPart1, htmlPart2 string
	err := chromedp.Run(tabCtx,
		// ── (1) Inject stealth overrides at document creation ─────────────
		// Runs before any inline script on every navigation in this tab.
		// Wrapped in ActionFunc because the cdproto command returns a
		// ScriptIdentifier we don't need, making it incompatible with the
		// plain chromedp.Action interface (which requires Do → error only).
		chromedp.ActionFunc(func(ctx context.Context) error {
			_, err := page.AddScriptToEvaluateOnNewDocument(stealthJS).Do(ctx)
			return err
		}),

		// ── (2) Enable fetch-domain request interception ──────────────────
		// Intercepts all requests at the request stage so the listener above
		// can abort heavy resources (images, stylesheets, fonts, media).
		// handleAuthRequests=true is required for proxy 407 challenges to be
		// delivered as EventAuthRequired — without it Chrome never fires the
		// event and returns net::ERR_NO_SUPPORTED_PROXIES instead.
		fetch.Enable().WithHandleAuthRequests(true).WithPatterns([]*fetch.RequestPattern{
			{RequestStage: fetch.RequestStageRequest},
		}),

		// ── (3) Spoof UA + Accept-Language at the CDP emulation layer ──────
		// emulation.SetUserAgentOverride also controls the HTTP header sent
		// with each request, not just navigator.userAgent in JS.
		emulation.SetUserAgentOverride(ua).WithAcceptLanguage(acceptLang),
		emulation.SetLocaleOverride().WithLocale(primaryLang),

		// ── (4) Mobile device metrics — iPhone 14 Pro ─────────────────────
		// Sets viewport (390×844 CSS px), device pixel ratio (3×), and tells
		// Chrome to report isMobile=true to the page's own media queries.
		// This is the key signal that unlocks looser WAF rules on many Akamai
		// configurations — they whitelist mobile Safari more generously.
		emulation.SetDeviceMetricsOverride(390, 844, 3.0, true),

		// ── (4b) Region-specific geolocation override ────────────────────
		geoOverrideActions(loc),

		// ── (5) Navigate and wait for DOM ─────────────────────────────────
		network.Enable(),
		// Spoof a search-engine referral — WAFs (Akamai, Cloudflare) apply
		// looser bot-scoring rules to traffic that appears to originate from
		// a Google SERP rather than a direct / no-referrer hit.
		network.SetExtraHTTPHeaders(network.Headers{"Referer": "https://www.google.com/"}),
		chromedp.Navigate(rawURL),
		chromedp.WaitReady("body", chromedp.ByQuery),

		// ── (6) Banner delay — let async CMPs inject their shadow banners ──
		chromedp.Sleep(bannerDelay),

		// ── (7) Human scroll — triggers lazy footers + scroll-depth signals─
		// Two-phase scroll: first a mid-page scroll (mimics natural reading),
		// then a full scroll-to-bottom to force lazy-loaded footer links into
		// the DOM.  Akamai/Cloudflare-fronted retail sites render their legal
		// footer links only after the viewport reaches the bottom of the page.
		chromedp.Evaluate(`window.scrollBy(0, 500)`, nil),
		chromedp.Sleep(600*time.Millisecond),
		chromedp.Evaluate(`window.scrollTo(0, document.body.scrollHeight)`, nil),
		chromedp.Sleep(2*time.Second),

		// ── (8) Cross-origin iframe settle time ───────────────────────────
		// CMP iframes (Sourcepoint, Usercentrics, OneTrust) load their banner
		// content asynchronously from a third-party CDN.  Even with
		// --disable-web-security in place, contentDocument on an iframe is
		// only accessible once the iframe has *fully loaded* (readyState=complete).
		// The banner delay above (step 6) covers the CMP JS bootstrap; this
		// extra 2 s gives slow CDN-hosted iframes time to finish rendering
		// so our shadowDOMExtractorJS can actually read their link trees.
		chromedp.Sleep(2*time.Second),

		// ── (9) Phase 1: pre-click DOM extraction ─────────────────────────
		// Capture links before any consent interaction.  This snapshot always
		// contains the site's own footer/header links even if the clicker
		// below does nothing (e.g. no banner present).
		chromedp.Evaluate(extractorJS, &htmlPart1),

		// ── (9b) Optional screenshot capture — PRE-CLICK, FULL-PAGE ──────
		// Captured before the consent clicker runs so the banner overlay is
		// still visible. Full-page (captureBeyondViewport=true) because CMPs
		// place banners inconsistently — top, bottom, centred modals, or
		// Shadow-DOM-wrapped iframes whose host element has 0×0 size — so a
		// viewport crop can miss a rendered banner. Scroll back to the top
		// first so the image begins at the page header (step 7 left the
		// viewport at the footer).
		chromedp.ActionFunc(func(ctx context.Context) error {
			if screenshotBuf == nil {
				return nil
			}
			if err := chromedp.Evaluate(`window.scrollTo(0, 0)`, nil).Do(ctx); err != nil {
				slog.Warn("scroll-to-top before screenshot failed", "url", rawURL, "error", err)
			}
			// Inject a fixed-position watermark so screenshots are self-
			// identifying as nabu-watch evidence and carry their capture
			// timestamp. Uses the maximum safe z-index and pointer-events:
			// none so it never covers interactive page elements. Injected
			// AFTER CMP detection & DOM extraction have already run, so it
			// cannot pollute the banner-scanning DOM snapshot.
			watermarkJS := fmt.Sprintf(
				`(function(){var w=document.createElement('div');`+
					`w.id='__nabu_watermark__';`+
					`w.textContent='nabu-watch · %s UTC';`+
					`w.style.cssText='position:fixed;top:8px;right:8px;z-index:2147483647;'+`+
					`'background:rgba(0,0,0,0.72);color:#fff;padding:4px 8px;'+`+
					`'font:11px/1.2 -apple-system,BlinkMacSystemFont,Segoe UI,sans-serif;'+`+
					`'border-radius:4px;pointer-events:none;letter-spacing:0.2px;';`+
					`document.body.appendChild(w);})();`,
				time.Now().UTC().Format("2006-01-02 15:04"),
			)
			if err := chromedp.Evaluate(watermarkJS, nil).Do(ctx); err != nil {
				slog.Warn("watermark injection failed", "url", rawURL, "error", err)
			}
			if err := chromedp.Sleep(150 * time.Millisecond).Do(ctx); err != nil {
				return nil
			}
			// Fetch full scrollable content size in one CDP round-trip,
			// then cap to defend against runaway infinite-scroll pages.
			// Fallbacks match the mobile viewport set in step (4).
			var dims [2]int64
			if err := chromedp.Evaluate(
				`[document.documentElement.scrollWidth,document.documentElement.scrollHeight]`,
				&dims,
			).Do(ctx); err != nil {
				dims = [2]int64{390, 844}
			}
			width, height := dims[0], dims[1]
			if width <= 0 || width > 2000 {
				width = 2000
			}
			if height <= 0 || height > 15000 {
				height = 15000
			}
			buf, err := page.CaptureScreenshot().
				WithFormat(page.CaptureScreenshotFormatJpeg).
				WithQuality(75).
				WithCaptureBeyondViewport(true).
				WithClip(&page.Viewport{
					X:      0,
					Y:      0,
					Width:  float64(width),
					Height: float64(height),
					Scale:  1,
				}).
				Do(ctx)
			if err != nil {
				slog.Warn("screenshot capture failed", "url", rawURL, "error", err)
				return nil
			}
			*screenshotBuf = buf
			return nil
		}),

		// ── (10) Phase 2: heuristic consent clicker ───────────────────────
		// Click the first visible element matching clickerJS labels.
		// For FetchPage (compliance/SSL) this dismisses the accept overlay.
		// For FetchBannerPage (BannerScanner) this opens the preferences panel.
		// We ignore the return value — a miss is not an error.
		chromedp.Evaluate(clickerJS, nil),

		// ── (11) Phase 3: wait for overlay teardown + post-click extract ──
		// 3 s is sufficient for most single-layer consent dismissals
		// (OneTrust / Cookiebot fade-out + DOM removal).
		chromedp.Sleep(3*time.Second),
		chromedp.Evaluate(extractorJS, &htmlPart2),
	)
	if err != nil {
		return 0, nil, fmt.Errorf("headless navigate %s: %w", rawURL, err)
	}

	// ── Phase 4: merge pre- and post-click extracts ───────────────────────────
	// Concatenating both snapshots ensures goquery sees every link that was
	// present in either phase.  goquery/html.Parse is lenient about duplicate
	// nodes — compliance checking deduplicates by href later.
	renderedHTML := htmlPart1 + "\n" + htmlPart2

	doc, err := goquery.NewDocumentFromReader(strings.NewReader(renderedHTML))
	if err != nil {
		return 0, nil, fmt.Errorf("parse headless HTML from %s: %w", rawURL, err)
	}

	// ── Block-page hard-reset (first attempt only) ────────────────────────────
	// If the rendered HTML is suspiciously short OR the visible text matches
	// known WAF / CAPTCHA patterns, the headless browser received a block page
	// (e.g. Akamai "Access Denied", Cloudflare challenge) instead of the real
	// site.  We do ONE hard reset:
	//   • Fresh browser context    — clears all cookies / session state
	//   • Random desktop UA        — switches fingerprint away from mobile
	//   • 5 s pre-navigation sleep — lets the rate-limit window expire
	// The isRetry guard prevents infinite recursion.
	if !isRetry {
		renderedBodyText := extractBodyText(doc)
		if isWAFShellPage(renderedHTML, renderedBodyText) {
			slog.Warn("FetchPage: WAF block page detected — attempting hard reset",
				"url", rawURL,
				"rendered_html_bytes", len(renderedHTML),
				"is_block_page", isBlockPage(renderedBodyText),
			)
			retryStatus, retryDoc, retryErr := h.fetchPageImpl(ctx, rawURL, loc, RandomUA(), true, clickerJS, extractorJS, screenshotBuf)
			if retryErr != nil {
				slog.Warn("FetchPage hard-reset retry failed — returning error",
					"url", rawURL,
					"error", retryErr,
				)
				return 0, nil, fmt.Errorf("WAF block page detected and hard-reset failed for %s: %w", rawURL, retryErr)
			}
			retryHTML, _ := retryDoc.Find("html").Html()
			retryBodyText := extractBodyText(retryDoc)
			if isWAFShellPage(retryHTML, retryBodyText) {
				slog.Warn("FetchPage hard-reset retry still blocked — returning error",
					"url", rawURL,
					"rendered_html_bytes_retry", len(retryHTML),
					"is_block_page", isBlockPage(retryBodyText),
				)
				return 0, nil, fmt.Errorf("WAF block page detected and hard-reset still blocked for %s", rawURL)
			}
			slog.Info("FetchPage hard-reset succeeded",
				"url", rawURL,
				"rendered_html_bytes_retry", len(retryHTML),
			)
			return retryStatus, retryDoc, nil
		}
	}

	return 200, doc, nil
}

// Close shuts down the shared Chrome process and releases all resources.
// Safe to call even when the allocator is in no-op mode.
func (h *HeadlessAllocator) Close() {
	if h.allocCancel != nil {
		h.allocCancel()
		slog.Info("Headless Chrome allocator stopped", "path", h.chromePath)
	}
	h.proxyMu.Lock()
	defer h.proxyMu.Unlock()
	if h.closed {
		return
	}
	h.closed = true
	for url, pa := range h.proxyAllocs {
		if pa.cancel != nil {
			pa.cancel()
		}
		slog.Info("Proxy allocator stopped", "proxy", redactProxy(url))
	}
}

// ── Browser discovery ─────────────────────────────────────────────────────────

// findChromeBinary resolves the Chrome/Chromium executable using a three-tier
// priority strategy.  Returns the absolute path, the discovery method label
// ("env", "path", "well-known"), and any error.
//
// Priority order:
//  1. NABU_CHROME_PATH env var  — explicit override for any host or CI system
//  2. exec.LookPath             — handles Homebrew, apt, snap, and custom installs
//  3. Well-known absolute paths — fallback when binary dir is not on $PATH
func findChromeBinary() (path, method string, err error) {
	// ── Priority 1: explicit environment override ─────────────────────────
	if p := os.Getenv("NABU_CHROME_PATH"); p != "" {
		if isExecutable(p) {
			return p, "env", nil
		}
		// Misconfigured but deliberately set — warn and fall through rather
		// than silently ignoring a probably-typo'd path.
		slog.Warn("NABU_CHROME_PATH is set but file is not executable — falling back to auto-discovery",
			"NABU_CHROME_PATH", p,
		)
	}

	// ── Priority 2: exec.LookPath ($PATH search) ──────────────────────────
	// This catches: Homebrew on macOS (arm/intel), apt packages on Debian/Ubuntu,
	// snap-managed binaries exposed via /snap/bin, and any user-local installs.
	for _, name := range []string{
		"google-chrome",
		"google-chrome-stable",
		"chromium-browser",
		"chromium",
		"chrome",
	} {
		if p, lerr := exec.LookPath(name); lerr == nil {
			return p, "path", nil
		}
	}

	// ── Priority 3: well-known absolute paths ─────────────────────────────
	// Tried last because if a binary is not on $PATH the environment is
	// probably non-standard, so we verify the file is actually executable
	// before trusting it.
	for _, p := range osCandidatePaths() {
		if isExecutable(p) {
			return p, "well-known", nil
		}
	}

	return "", "", fmt.Errorf(
		"Chrome binary not found on %s. "+
			"Install Chromium (`apt-get install chromium-browser` on Ubuntu, "+
			"`brew install --cask google-chrome` on macOS) "+
			"or set the NABU_CHROME_PATH environment variable",
		runtime.GOOS,
	)
}

// osCandidatePaths returns OS-specific absolute paths tried as the last
// discovery resort when neither NABU_CHROME_PATH nor $PATH contains a match.
func osCandidatePaths() []string {
	switch runtime.GOOS {
	case "darwin":
		return []string{
			"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
			"/Applications/Chromium.app/Contents/MacOS/Chromium",
		}
	case "linux":
		// Order matters: prefer the non-snap binaries. On Ubuntu the snap
		// chromium (and the chromium-browser/chromium apt shims that redirect
		// to it) launches the browser in its OWN systemd scope under
		// user@0.service, detached from the worker's cgroup — so it can't be
		// reaped on restart and orphans to PID 1 on any ungraceful death. A
		// .deb google-chrome runs as a direct child and is reaped cleanly, so
		// it's tried first; snap is the last resort. See setup_worker.sh.
		return []string{
			"/usr/bin/google-chrome", // Google Chrome .deb (preferred — non-snap)
			"/usr/bin/google-chrome-stable",
			"/usr/bin/chromium",         // Debian / Arch: real chromium package
			"/usr/bin/chromium-browser", // Ubuntu: usually a snap shim
			"/snap/bin/chromium",        // Ubuntu snap — cgroup-detaching, last resort
			"/var/lib/flatpak/exports/bin/org.chromium.Chromium",
		}
	default:
		return nil
	}
}

// isExecutable reports whether path exists and is a regular, executable file.
func isExecutable(path string) bool {
	info, err := os.Stat(path)
	if err != nil {
		return false
	}
	return info.Mode().IsRegular() && (info.Mode().Perm()&0o111 != 0)
}

// ── Network-capturing headless fetch (for TrackerScanner) ────────────────────

// CapturedRequest records a single outgoing network request observed during
// a headless page load.
type CapturedRequest struct {
	URL          string // full request URL
	Domain       string // hostname extracted from URL
	ResourceType string // CDP resource type: Document, Script, Image, etc.
	Initiator    string // "parser", "script", "preload", "other"
}

// maxCapturedRequests limits the number of network requests recorded to
// prevent unbounded memory growth on request-heavy pages.
const maxCapturedRequests = 500

// trackerSettleTime is the wait after DOMContentLoaded before stopping
// network capture.  Most third-party scripts finish loading within 5 s.
const trackerSettleTime = 5 * time.Second

// FetchPageWithNetworkCapture navigates to rawURL in a headless Chrome tab
// and records every outgoing network request.  Unlike FetchPage it does NOT
// block images, stylesheets, or fonts — the full network footprint is needed
// to detect tracking pixels, font-based fingerprinting, and advertising tags.
//
// Returns the HTTP status code, the rendered document, and a slice of all
// captured network requests (capped at maxCapturedRequests).
func (h *HeadlessAllocator) FetchPageWithNetworkCapture(
	ctx context.Context, rawURL string, loc LocalizationConfig,
) (int, *goquery.Document, []CapturedRequest, error) {
	if !h.available {
		return 0, nil, nil, fmt.Errorf("headless mode not available")
	}
	acceptLang := loc.AcceptLanguage
	if acceptLang == "" {
		acceptLang = "en-US,en;q=0.9"
	}
	ua := MobileUA()

	tabCtx, tabCancel := chromedp.NewContext(h.allocatorFor(loc.HTTPProxy))
	defer tabCancel()

	deadline := time.Now().Add(headlessFetchTimeout)
	if d, ok := ctx.Deadline(); ok && d.Before(deadline) {
		deadline = d
	}
	tabCtx, deadlineCancel := context.WithDeadline(tabCtx, deadline)
	defer deadlineCancel()

	// Propagate parent cancellation.
	go func() {
		select {
		case <-ctx.Done():
			deadlineCancel()
		case <-tabCtx.Done():
		}
	}()

	primaryLang := acceptLang
	if idx := strings.IndexByte(acceptLang, ','); idx > 0 {
		primaryLang = acceptLang[:idx]
	}

	// Stealth JS — same as fetchPageImpl.
	stealthJS := buildStealthJS(primaryLang)

	// ── Network event capture ────────────────────────────────────────────────
	// Use network.EventRequestWillBeSent (passive observation, no blocking)
	// instead of fetch.EventRequestPaused so every resource type loads
	// naturally — including tracking pixels disguised as 1×1 images.
	var captured []CapturedRequest
	var capMu = make(chan struct{}, 1) // lightweight mutex
	capMu <- struct{}{}

	// needFetchAuth is true when we're routing through an authenticated proxy.
	// fetch.Enable is required for CDP auth handling but also pauses every
	// request — so we must continue them explicitly in the listener below.
	needFetchAuth := loc.HTTPProxy != ""

	chromedp.ListenTarget(tabCtx, func(ev interface{}) {
		switch e := ev.(type) {
		case *network.EventRequestWillBeSent:
			<-capMu
			defer func() { capMu <- struct{}{} }()

			if len(captured) >= maxCapturedRequests {
				return
			}
			domain := extractDomain(e.Request.URL)
			initiator := "other"
			if e.Initiator != nil {
				switch e.Initiator.Type {
				case network.InitiatorTypeParser:
					initiator = "parser"
				case network.InitiatorTypeScript:
					initiator = "script"
				case network.InitiatorTypePreload:
					initiator = "preload"
				}
			}
			captured = append(captured, CapturedRequest{
				URL:          e.Request.URL,
				Domain:       domain,
				ResourceType: e.Type.String(),
				Initiator:    initiator,
			})
		case *fetch.EventRequestPaused:
			if !needFetchAuth {
				break // fetch.Enable not called — event should never fire, but guard anyway
			}
			// fetch.Enable pauses every request; continue them or the tab stalls.
			go func() {
				if err := chromedp.Run(tabCtx, fetch.ContinueRequest(e.RequestID)); err != nil &&
					!strings.Contains(err.Error(), "invalid context") &&
					!strings.Contains(err.Error(), "No resource with given identifier") {
					slog.Debug("tracker fetch continue error", "error", err)
				}
			}()
		case *fetch.EventAuthRequired:
			if !needFetchAuth {
				break
			}
			go h.handleProxyAuth(tabCtx, e.RequestID, loc.HTTPProxy)
		}
	})

	// ── Navigate and extract ─────────────────────────────────────────────────
	var statusCode int
	var html string

	// Build the action list. fetch.Enable is only needed when routing through
	// an authenticated proxy — without it, requests flow without pausing.
	actions := chromedp.Tasks{
		// Stealth overrides
		chromedp.ActionFunc(func(ctx context.Context) error {
			_, err := page.AddScriptToEvaluateOnNewDocument(stealthJS).Do(ctx)
			return err
		}),
		// Enable network events (no blocking — we want the full footprint)
		network.Enable(),
	}
	if needFetchAuth {
		actions = append(actions, fetch.Enable().WithHandleAuthRequests(true))
	}
	actions = append(actions,
		// Browser identity
		network.SetExtraHTTPHeaders(network.Headers(map[string]interface{}{
			"Accept-Language": acceptLang,
		})),
		emulation.SetUserAgentOverride(ua).WithAcceptLanguage(acceptLang),
		geoOverrideActions(loc),
		// Navigate
		chromedp.Navigate(rawURL),
		chromedp.ActionFunc(func(ctx context.Context) error {
			// Wait for DOMContentLoaded + settle time for async trackers.
			select {
			case <-time.After(trackerSettleTime):
			case <-ctx.Done():
			}
			return nil
		}),
		// Capture HTTP status from the main frame navigation.
		chromedp.ActionFunc(func(ctx context.Context) error {
			// Best-effort: extract from network response via JS.
			return nil
		}),
		// Extract rendered HTML.
		chromedp.Evaluate(shadowDOMExtractorJS, &html),
	)
	err := chromedp.Run(tabCtx, actions...)
	if err != nil {
		return 0, nil, captured, fmt.Errorf("headless tracker fetch: %w", err)
	}

	doc, err := goquery.NewDocumentFromReader(strings.NewReader(html))
	if err != nil {
		return 0, nil, captured, fmt.Errorf("headless tracker: parse html: %w", err)
	}

	slog.Info("FetchPageWithNetworkCapture complete",
		"url", rawURL,
		"captured_requests", len(captured),
		"html_bytes", len(html),
	)

	return statusCode, doc, captured, nil
}
