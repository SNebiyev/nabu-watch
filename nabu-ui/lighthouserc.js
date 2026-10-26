module.exports = {
  ci: {
    collect: {
      startServerCommand: "npm run start",
      startServerReadyPattern: "Ready in|started server on",
      startServerReadyTimeout: 60000,
      url: [
        "http://localhost:3000/",
        "http://localhost:3000/tools",
        "http://localhost:3000/tools/ssl-check",
        "http://localhost:3000/tools/cookie-banner-checker",
        "http://localhost:3000/tools/impressum-checker",
        "http://localhost:3000/tools/gdpr-scan",
        "http://localhost:3000/tools/privacy-policy-check",
      ],
      numberOfRuns: 3,
      settings: {
        preset: "desktop",
        chromeFlags: "--no-sandbox --headless=new",
      },
    },
    assert: {
      assertions: {
        "categories:performance": ["warn", {minScore: 0.8}],
        "categories:accessibility": ["error", {minScore: 0.9}],
        // 0.8 floor: Sentry browser SDK uses a deprecated `unload` listener and
        // we upload source maps privately to Sentry rather than serving `.js.map`
        // files publicly. Both drop the audit by ~0.1 but are not our code.
        "categories:best-practices": ["warn", {minScore: 0.8}],
        "categories:seo": ["error", {minScore: 0.9}],

        "largest-contentful-paint": ["warn", {maxNumericValue: 2500}],
        "cumulative-layout-shift": ["error", {maxNumericValue: 0.1}],
        "total-blocking-time": ["warn", {maxNumericValue: 300}],

        "uses-text-compression": "off",
        "unused-javascript": "off",
        "csp-xss": "off",
        "deprecations": "off",
        "valid-source-maps": "off",
      },
    },
    upload: {
      target: "temporary-public-storage",
    },
  },
};
