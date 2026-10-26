package scanner

import "os"

// LocalizationConfig holds the region-specific stealth parameters used to
// make the crawler appear as a legitimate local user. Geolocation fields
// feed CDP emulation so that CMPs which gate banner rendering on visitor
// geography (Didomi, OneTrust, Sourcepoint, Usercentrics) behave as they
// would for a real local visitor — client-side signal only; server-side
// IP-based gating still requires a regional proxy (see HTTPProxy).
type LocalizationConfig struct {
	// AcceptLanguage is sent as the Accept-Language HTTP header.
	AcceptLanguage string
	// Referer is spoofed to match the dominant regional search engine, which
	// many sites use to decide which language/locale page to serve.
	Referer string
	// LangCookie is an optional Cookie header value (e.g. "lang=de") to prevent
	// sites from redirecting to a generic locale based on server-side geo-IP.
	LangCookie string
	// Latitude / Longitude are the coordinates reported via the Geolocation
	// API and CDP emulation layer. Pinned to a major city in the region.
	Latitude  float64
	Longitude float64
	// TimeZone is the IANA zone name (e.g. "Europe/Berlin") applied via
	// Emulation.setTimezoneOverride. Some CMPs cross-check the browser's
	// Intl timezone against the geolocation signal and disable themselves
	// on a mismatch.
	TimeZone string
	// HTTPProxy is an optional upstream proxy URL ("http://user:pass@host:port")
	// routing this region's headless fetches through a regional egress. Needed
	// because enterprise CMPs check IP geolocation server-side, which
	// client-side emulation cannot bypass. Populated from NABU_PROXY_<REGIONCODE>
	// (e.g. NABU_PROXY_DE) at GetLocalization() time; empty = direct egress.
	HTTPProxy string
}

// localizationRegistry maps ISO 3166-1 alpha-2 region codes to their
// LocalizationConfig. Coordinates use a major city per region — only the
// jurisdiction (GDPR / CCPA / PIPEDA) has to match, not a precise address.
var localizationRegistry = map[string]LocalizationConfig{
	"DE": {
		AcceptLanguage: "de-DE,de;q=0.9,en-US;q=0.8,en;q=0.7",
		Referer:        "https://www.google.de/",
		LangCookie:     "lang=de; locale=de_DE",
		Latitude:       52.5200, Longitude: 13.4050, TimeZone: "Europe/Berlin",
	},
	"AT": {
		AcceptLanguage: "de-AT,de;q=0.9,en-US;q=0.8,en;q=0.7",
		Referer:        "https://www.google.at/",
		LangCookie:     "lang=de; locale=de_AT",
		Latitude:       48.2082, Longitude: 16.3738, TimeZone: "Europe/Vienna",
	},
	"CH": {
		AcceptLanguage: "de-CH,de;q=0.9,fr-CH;q=0.8,fr;q=0.7,en;q=0.6",
		Referer:        "https://www.google.ch/",
		LangCookie:     "lang=de; locale=de_CH",
		Latitude:       47.3769, Longitude: 8.5417, TimeZone: "Europe/Zurich",
	},
	"FR": {
		AcceptLanguage: "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7",
		Referer:        "https://www.google.fr/",
		LangCookie:     "lang=fr; locale=fr_FR",
		Latitude:       48.8566, Longitude: 2.3522, TimeZone: "Europe/Paris",
	},
	"GB": {
		AcceptLanguage: "en-GB,en;q=0.9,en-US;q=0.8",
		Referer:        "https://www.google.co.uk/",
		Latitude:       51.5074, Longitude: -0.1278, TimeZone: "Europe/London",
	},
	"US": {
		AcceptLanguage: "en-US,en;q=0.9",
		Referer:        "https://www.google.com/",
		Latitude:       40.7128, Longitude: -74.0060, TimeZone: "America/New_York",
	},
	"ES": {
		AcceptLanguage: "es-ES,es;q=0.9,en-US;q=0.8,en;q=0.7",
		Referer:        "https://www.google.es/",
		LangCookie:     "lang=es; locale=es_ES",
		Latitude:       40.4168, Longitude: -3.7038, TimeZone: "Europe/Madrid",
	},
	"IT": {
		AcceptLanguage: "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",
		Referer:        "https://www.google.it/",
		LangCookie:     "lang=it; locale=it_IT",
		Latitude:       41.9028, Longitude: 12.4964, TimeZone: "Europe/Rome",
	},
	"NL": {
		AcceptLanguage: "nl-NL,nl;q=0.9,en-US;q=0.8,en;q=0.7",
		Referer:        "https://www.google.nl/",
		LangCookie:     "lang=nl; locale=nl_NL",
		Latitude:       52.3676, Longitude: 4.9041, TimeZone: "Europe/Amsterdam",
	},
	"PL": {
		AcceptLanguage: "pl-PL,pl;q=0.9,en-US;q=0.8,en;q=0.7",
		Referer:        "https://www.google.pl/",
		LangCookie:     "lang=pl; locale=pl_PL",
		Latitude:       52.2297, Longitude: 21.0122, TimeZone: "Europe/Warsaw",
	},
	"CA": {
		AcceptLanguage: "en-CA,en;q=0.9,fr-CA;q=0.8,fr;q=0.7",
		Referer:        "https://www.google.ca/",
		Latitude:       43.6532, Longitude: -79.3832, TimeZone: "America/Toronto",
	},
	"JP": {
		AcceptLanguage: "ja-JP,ja;q=0.9,en-US;q=0.8,en;q=0.7",
		Referer:        "https://www.google.co.jp/",
		LangCookie:     "lang=ja; locale=ja_JP",
		Latitude:       35.6762, Longitude: 139.6503, TimeZone: "Asia/Tokyo",
	},
	"AU": {
		AcceptLanguage: "en-AU,en;q=0.9,en-US;q=0.8",
		Referer:        "https://www.google.com.au/",
		Latitude:       -33.8688, Longitude: 151.2093, TimeZone: "Australia/Sydney",
	},
}

// GetLocalization returns the LocalizationConfig for the given region code.
// Falls back to a generic English/US config if the region is unknown.
// HTTPProxy is populated from the NABU_PROXY_<REGIONCODE> env variable if
// set — unset means scans continue through the worker's own egress IP.
func GetLocalization(regionCode string) LocalizationConfig {
	cfg, ok := localizationRegistry[regionCode]
	if !ok {
		cfg = LocalizationConfig{
			AcceptLanguage: "en-US,en;q=0.9",
			Referer:        "https://www.google.com/",
			Latitude:       40.7128, Longitude: -74.0060, TimeZone: "America/New_York",
		}
		regionCode = "US"
	}
	if proxy := os.Getenv("NABU_PROXY_" + regionCode); proxy != "" {
		cfg.HTTPProxy = proxy
	}
	return cfg
}
