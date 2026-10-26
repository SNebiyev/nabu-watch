package watch.nabu.domain.model;

/**
 * Catalogue of verified-only, manual-only security-audit monitors.
 *
 * <p>Each value declares:
 * <ul>
 *   <li>{@code key}  — stable URL/API identifier (kebab-case).</li>
 *   <li>{@code label} — human-readable name shown in the UI.</li>
 *   <li>{@code shipped} — {@code false} for roadmap placeholders the UI
 *       should show as "coming soon" without hitting a backend endpoint.</li>
 * </ul>
 */
public enum SecurityScan {
    SECURITY_HEADERS ("security-headers", "Security Headers",  true),
    EXPOSED_FILES    ("exposed-files",    "Exposed Files",     true),
    TLS_GRADE        ("tls-grade",        "TLS Grade",         true),
    DNS_HYGIENE      ("dns-hygiene",      "DNS Hygiene",       true),
    OPEN_PORTS       ("open-ports",       "Open Ports",        true),
    JS_LIBRARY_CVE   ("js-library-cve",   "JS Library CVE",    true),
    TYPOSQUAT_WATCH  ("typosquat-watch",  "Typosquat Watch",   true);

    private final String key;
    private final String label;
    private final boolean shipped;

    SecurityScan(String key, String label, boolean shipped) {
        this.key = key;
        this.label = label;
        this.shipped = shipped;
    }

    public String key()      { return key; }
    public String label()    { return label; }
    public boolean shipped() { return shipped; }

    public static SecurityScan fromKey(String key) {
        for (SecurityScan s : values()) {
            if (s.key.equals(key)) return s;
        }
        return null;
    }
}
