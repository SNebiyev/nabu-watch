/* Nabu Watch landing page: fade-ins, scan demo loop, mobile menu, scroll-to-top. Page is fully usable without this file. */
(function () {
  "use strict";
  var reduce = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  var GH = "https://github.com/SNebiyev/nabu-watch";
  var SCAN_CASES = [
 {
  "domain": "shop.example.de",
  "items": [
   [
    "SSL Certificate",
    "pass",
    "Valid · 142 days remaining"
   ],
   [
    "Privacy Policy",
    "pass",
    "Found in footer · Datenschutzerklärung"
   ],
   [
    "Terms of Service",
    "pass",
    "Found · AGB link verified"
   ],
   [
    "Impressum",
    "fail",
    "Not found on homepage"
   ],
   [
    "Cookie Policy",
    "pass",
    "Cookie-Richtlinie found"
   ],
   [
    "GDPR Request",
    "pass",
    "Datenschutzanfrage form found"
   ],
   [
    "Cookie Banner",
    "warn",
    "Banner detected · reject button missing"
   ],
   [
    "Tracker Detection",
    "warn",
    "2 trackers: Google Analytics, Meta Pixel"
   ]
  ],
  "ai": [
   [
    "c-a",
    "Cookie banner lacks a visible reject/decline button. Users must be able to reject non-essential cookies as easily as accepting them (GDPR Art. 7)."
   ],
   [
    "c-r",
    "No Impressum page found. It is legally required for commercial websites in DE/AT/CH under TMG §5 and must include company name, address, and contact info."
   ],
   [
    "c-a",
    "2 third-party trackers detected (Google Analytics, Meta Pixel) but not disclosed in the Privacy Policy. GDPR Art. 13 requires listing all data recipients."
   ],
   [
    "c-g",
    "Content quality score: 82/100. Privacy Policy and Terms of Service are well-structured and cover the required GDPR disclosure elements."
   ]
  ],
  "score": "B",
  "sc": "c-a",
  "issues": 3
 },
 {
  "domain": "acme.example",
  "items": [
   [
    "SSL Certificate",
    "pass",
    "Valid · 364 days remaining"
   ],
   [
    "Privacy Policy",
    "pass",
    "Found · Privacy Notice"
   ],
   [
    "Terms of Service",
    "pass",
    "Found · Terms and Conditions"
   ],
   [
    "Impressum",
    "pass",
    "Found · Legal Notice"
   ],
   [
    "Cookie Policy",
    "pass",
    "Dedicated cookie policy page"
   ],
   [
    "GDPR Request",
    "pass",
    "DSAR form found"
   ],
   [
    "Cookie Banner",
    "pass",
    "OneTrust CMP · accept & reject present"
   ],
   [
    "Tracker Detection",
    "pass",
    "No third-party trackers detected"
   ]
  ],
  "ai": [
   [
    "c-g",
    "All 8 compliance checks passed. Legal pages are accessible from the homepage footer. The cookie consent banner implements proper accept/reject controls with granular preferences."
   ],
   [
    "c-g",
    "Content quality score: 94/100. Comprehensive data processing disclosures, clear legal basis documentation, and complete contact information."
   ],
   [
    "c-g",
    "No undisclosed third-party trackers found. The site respects user privacy by default."
   ]
  ],
  "score": "A+",
  "sc": "c-g",
  "issues": 0
 },
 {
  "domain": "startup.example",
  "items": [
   [
    "SSL Certificate",
    "warn",
    "Expiring in 12 days!"
   ],
   [
    "Privacy Policy",
    "fail",
    "Not found on homepage"
   ],
   [
    "Terms of Service",
    "fail",
    "Not found on homepage"
   ],
   [
    "Impressum",
    "fail",
    "Not found"
   ],
   [
    "Cookie Policy",
    "fail",
    "Not found"
   ],
   [
    "GDPR Request",
    "fail",
    "No data request page"
   ],
   [
    "Cookie Banner",
    "fail",
    "No consent banner detected"
   ],
   [
    "Tracker Detection",
    "fail",
    "4 trackers found · undisclosed"
   ]
  ],
  "ai": [
   [
    "c-r",
    "Critical: no Privacy Policy found. This is a mandatory requirement under GDPR Art. 13. The website must disclose what personal data is collected and how it is processed."
   ],
   [
    "c-r",
    "No cookie consent banner detected, yet 4 third-party trackers are active (Google Analytics, Facebook Pixel, Hotjar, Intercom) without any user consent mechanism."
   ],
   [
    "c-a",
    "SSL certificate expires in 12 days. Renew it to avoid browser security warnings that will block visitors."
   ],
   [
    "c-r",
    "Content quality score: 0/100. No legal documents found, so this site is at high risk of GDPR enforcement action."
   ]
  ],
  "score": "F",
  "sc": "c-r",
  "issues": 7
 },
 {
  "domain": "legal-firm.example",
  "items": [
   [
    "SSL Certificate",
    "pass",
    "Valid · 89 days remaining"
   ],
   [
    "Privacy Policy",
    "pass",
    "Politique de Confidentialité"
   ],
   [
    "Terms of Service",
    "pass",
    "Conditions Générales d'Utilisation"
   ],
   [
    "Impressum",
    "pass",
    "Mentions Légales found"
   ],
   [
    "Cookie Policy",
    "pass",
    "Politique de Cookies"
   ],
   [
    "GDPR Request",
    "warn",
    "Contact form only · no dedicated page"
   ],
   [
    "Cookie Banner",
    "pass",
    "Cookiebot CMP · fully compliant"
   ],
   [
    "Tracker Detection",
    "pass",
    "1 tracker: Matomo (self-hosted)"
   ]
  ],
  "ai": [
   [
    "c-g",
    "Excellent compliance posture. All core legal documents found in both French and English. The cookie consent banner uses Cookiebot with proper granular controls."
   ],
   [
    "c-a",
    "GDPR data subject request: only a generic contact form was found. Consider adding a dedicated DSAR page with clear instructions on exercising data rights (access, erasure, portability)."
   ],
   [
    "c-g",
    "Matomo analytics is self-hosted, so no data leaves your servers. Content quality score: 91/100."
   ]
  ],
  "score": "A-",
  "sc": "c-g",
  "issues": 1
 }
];

  // Mobile menu
  var btn = document.querySelector(".menu-btn");
  var menu = document.getElementById("mobile-nav");
  if (btn && menu) {
    var setOpen = function (open) {
      menu.classList.toggle("open", open);
      btn.setAttribute("aria-expanded", open ? "true" : "false");
      btn.setAttribute("aria-label", open ? "Close menu" : "Open menu");
    };
    btn.addEventListener("click", function () { setOpen(!menu.classList.contains("open")); });
    menu.addEventListener("click", function (e) { if (e.target.tagName === "A") setOpen(false); });
    document.addEventListener("keydown", function (e) { if (e.key === "Escape") setOpen(false); });
  }

  // Fade-in on scroll
  var fades = document.querySelectorAll(".fade");
  if (reduce || !("IntersectionObserver" in window)) {
    fades.forEach(function (el) { el.classList.add("in"); });
  } else {
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (en) {
        if (en.isIntersecting) { en.target.classList.add("in"); io.unobserve(en.target); }
      });
    }, {threshold: 0.1, rootMargin: "0px 0px -50px 0px"});
    fades.forEach(function (el) { io.observe(el); });
  }

  // Scroll to top
  var top = document.querySelector(".totop");
  if (top) {
    var onScroll = function () { top.classList.toggle("show", window.scrollY > 400); };
    window.addEventListener("scroll", onScroll, {passive: true});
    onScroll();
    top.addEventListener("click", function () { window.scrollTo({top: 0, behavior: reduce ? "auto" : "smooth"}); });
  }

  // Scan demo. The static HTML already shows case 0 fully rendered; with reduced motion we leave it alone.
  var demo = document.getElementById("demo");
  if (!demo || reduce) return;

  var TAG = {pass: ["p", "PASS"], warn: ["w", "WARN"], fail: ["f", "FAIL"]};
  function esc(s) { return String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;"); }
  var BOT = '<svg class="i" aria-hidden="true"><use href="#i-bot"/></svg>';

  function build(c) {
    var items = c.items.map(function (it) {
      var t = TAG[it[1]];
      return '<div class="it hide"><span class="sd ' + t[0] + '"></span><span class="n">' + esc(it[0]) + '</span><span class="d">' + esc(it[2]) + '</span><span class="t ' + t[0] + '">' + t[1] + '</span></div>';
    }).join("");
    var ai = c.ai.map(function (a) { return '<p><span class="' + a[0] + '">→</span> ' + esc(a[1]) + '</p>'; }).join("");
    var tag = c.issues ? '<span class="tag a">' + c.issues + (c.issues === 1 ? " issue" : " issues") + '</span>' : '<span class="tag g">All clear</span>';
    demo.innerHTML =
      '<div class="d-head"><div><span class="sd live" data-d="dot"></span><span class="d-title" data-d="title"></span></div><span class="d-meta" data-d="meta"></span></div>' +
      '<div class="items" data-d="items">' + items + '</div>' +
      '<div class="ai hide" data-d="ai"><div class="ai-h"><span class="ai-i">' + BOT + '</span><b>AI Analysis (optional)</b><span class="ai-dots" data-d="dots"><i></i><i></i><i></i><span>analyzing</span></span></div><div class="ai-list" data-d="ailist" hidden>' + ai + '</div></div>' +
      '<div class="score hide" data-d="score"><div class="sc-row"><span>Compliance Score</span><div><span class="grade ' + c.sc + '">' + esc(c.score) + '</span>' + tag + '</div></div><a href="' + GH + '">Run it on your own sites: view the source on GitHub →</a></div>';
  }
  function q(k) { return demo.querySelector('[data-d="' + k + '"]'); }

  function render(c, phase) {
    var rows = demo.querySelectorAll(".it");
    for (var i = 0; i < rows.length; i++) rows[i].classList.toggle("hide", i >= phase);
    var done = phase >= 11;
    q("ai").classList.toggle("hide", phase < 8);
    q("dots").classList.toggle("on", phase === 9);
    q("ailist").hidden = phase < 10;
    q("score").classList.toggle("hide", !done);
    var dot = q("dot");
    dot.className = "sd " + (done ? (c.issues === 0 ? "" : "am") : "live");
    q("title").textContent = done ? "Scan complete" : "Scanning " + c.domain + "...";
    q("meta").textContent = done ? "8 checks · 2.1s" : "";
  }

  // phases 0-7: one item each, 8: AI header, 9: analyzing, 10: AI results, 11: score, 12: pause
  var DELAYS = {0: 600, 1: 500, 2: 500, 3: 500, 4: 500, 5: 500, 6: 600, 7: 600, 8: 400, 9: 2000, 10: 1000, 11: 800};
  var caseIdx = 0, phase = 0, timer = null, visible = true;

  function tick() {
    timer = null;
    if (!visible) return;
    if (phase >= 12) { caseIdx = (caseIdx + 1) % SCAN_CASES.length; phase = 0; build(SCAN_CASES[caseIdx]); }
    else phase++;
    render(SCAN_CASES[caseIdx], phase);
    schedule();
  }
  function schedule() {
    var d = phase >= 12 ? 10000 : (DELAYS[phase] != null ? DELAYS[phase] : 700);
    timer = setTimeout(tick, d);
  }

  build(SCAN_CASES[0]);
  render(SCAN_CASES[0], 0);
  schedule();

  // Pause the loop while the demo is off screen or the tab is hidden.
  function pauseIf(v) {
    visible = v;
    if (!v && timer) { clearTimeout(timer); timer = null; }
    else if (v && !timer) schedule();
  }
  if ("IntersectionObserver" in window) {
    new IntersectionObserver(function (e) { pauseIf(e[0].isIntersecting && !document.hidden); }).observe(demo);
  }
  document.addEventListener("visibilitychange", function () { pauseIf(!document.hidden); });
})();
