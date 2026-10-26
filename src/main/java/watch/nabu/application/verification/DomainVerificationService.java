package watch.nabu.application.verification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watch.nabu.application.exception.WebsiteNotFoundException;
import watch.nabu.application.notification.NotificationService;
import watch.nabu.application.notification.SiteLifecycleEmailService;
import watch.nabu.domain.model.Notification;
import watch.nabu.domain.model.User;
import watch.nabu.domain.model.Website;
import watch.nabu.domain.model.WebsiteVerification;
import watch.nabu.domain.model.WebsiteVerification.Status;
import watch.nabu.infrastructure.persistence.WebsiteRepository;
import watch.nabu.infrastructure.persistence.WebsiteVerificationRepository;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Hashtable;
import java.util.List;
import java.util.UUID;

/**
 * DNS TXT-based domain ownership verification.
 *
 * <p>The user provisions a record at {@code _nabu-challenge.<domain>} with the
 * token assigned at website creation.  The service compares the DNS answer
 * against the stored token; a match transitions the row (and the mirrored
 * {@link Website#getVerificationStatus()}) to {@code VERIFIED}.
 *
 * <p>The underscore-prefixed subdomain is used intentionally so the challenge
 * doesn't collide with other TXT records the owner already maintains
 * (SPF/DKIM/DMARC sit on the apex or standard subdomains).  The record name
 * also cannot clash with a legitimate hostname — standards reserve the
 * underscore prefix for non-host DNS uses (RFC 8552).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DomainVerificationService {

    /**
     * Grace period between row creation and automatic {@code EXPIRED}
     * transition. Aligns with industry norm (Google Search Console, Cloudflare
     * give 7+ days) and product-team decision on 2026-04-13.
     */
    static final Duration VERIFICATION_WINDOW = Duration.ofDays(7);

    /**
     * Conservative per-domain timeout for a single TXT lookup.  Tuned against
     * the scheduler's 100-row batch: at 3 s each worst case the batch still
     * finishes well under the 6-hour scheduler cadence.
     */
    private static final int DNS_TIMEOUT_MS = 3_000;

    private static final String CHALLENGE_PREFIX = "_nabu-challenge.";
    private static final String TOKEN_PREFIX = "nabu-verify=";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final WebsiteVerificationRepository verificationRepository;
    private final WebsiteRepository websiteRepository;
    /**
     * Lazy provider — UserVerificationService listens for events published by
     * this service, so a direct field would create a circular bean reference.
     */
    private final ObjectProvider<UserVerificationService> userVerificationProvider;
    private final NotificationService notificationService;
    private final SiteLifecycleEmailService siteLifecycleEmailService;

    /**
     * Creates a fresh PENDING verification row for a newly-created website.
     * Called synchronously from {@link watch.nabu.application.website.WebsiteService#create}
     * inside the same transaction so the row is always present when the
     * detail page asks for it.
     */
    @Transactional
    public WebsiteVerification provision(Website website) {
        String token = generateToken();
        Instant now = Instant.now();
        WebsiteVerification verification = WebsiteVerification.builder()
                .website(website)
                .token(token)
                .status(Status.PENDING)
                .expiresAt(now.plus(VERIFICATION_WINDOW))
                .build();
        verificationRepository.save(verification);
        website.setVerificationStatus(Website.VerificationStatus.PENDING);
        log.info("[verify] Provisioned: websiteId={} expiresAt={}",
                website.getId(), verification.getExpiresAt());
        return verification;
    }

    /** Read-only snapshot for the Verify Domain UI card. */
    @Transactional(readOnly = true)
    public WebsiteVerification loadOrFail(UUID websiteId) {
        return verificationRepository.findByWebsiteId(websiteId)
                .orElseThrow(() -> new WebsiteNotFoundException(websiteId));
    }

    /**
     * Performs a single DNS lookup and records the outcome.  Callable from
     * both the manual "Check now" endpoint and the background scheduler.
     *
     * @return the updated {@link WebsiteVerification} row — callers can check
     *         {@code .getStatus()} to determine whether verification succeeded.
     */
    @Transactional
    public WebsiteVerification check(UUID websiteId) {
        WebsiteVerification verification = verificationRepository.findByWebsiteId(websiteId)
                .orElseThrow(() -> new WebsiteNotFoundException(websiteId));

        // VERIFIED is terminal — short-circuit rather than risk regressing state
        // if the user removes the DNS record after a successful verification.
        if (verification.getStatus() == Status.VERIFIED) {
            return verification;
        }

        verification.setLastCheckedAt(Instant.now());
        verification.setCheckCount(verification.getCheckCount() + 1);

        // Expired rows can still be re-checked (gives the user one last chance
        // via "Check now"), but we record the failure and do not transition
        // away from EXPIRED unless a VERIFIED match is found.
        Website website = verification.getWebsite();
        String host = extractHost(website.getUrl());
        if (host == null) {
            verification.setFailureReason("invalid website URL");
            return verification;
        }

        LookupResult result = lookupTxt(CHALLENGE_PREFIX + host);
        if (!result.ok) {
            verification.setFailureReason(truncate(result.error, 200));
            log.info("[verify] DNS lookup failed: websiteId={} host={} reason={}",
                    websiteId, host, result.error);
            return verification;
        }

        String expected = TOKEN_PREFIX + verification.getToken().substring(TOKEN_PREFIX.length());
        boolean match = result.values.stream().anyMatch(v -> v.trim().equalsIgnoreCase(expected));
        if (!match) {
            verification.setFailureReason("TXT record present but does not match expected token");
            log.info("[verify] TXT mismatch: websiteId={} host={} found={}",
                    websiteId, host, result.values);
            return verification;
        }

        Instant now = Instant.now();
        verification.setStatus(Status.VERIFIED);
        verification.setVerifiedAt(now);
        verification.setFailureReason(null);
        website.setVerificationStatus(Website.VerificationStatus.VERIFIED);
        website.setVerifiedAt(now);
        log.info("[verify] VERIFIED: websiteId={} host={}", websiteId, host);
        userVerificationProvider.getObject().requestRecompute(website.getAddedBy());
        return verification;
    }

    /** Apex host the user must place the TXT record on ({@code null} for malformed URLs). */
    public static String hostOf(Website website) {
        return extractHost(website.getUrl());
    }

    /**
     * Warning grace window: how long before {@code expires_at} we email the
     * owner a nudge to complete the challenge.
     */
    static final Duration EXPIRY_WARNING_LEAD_TIME = Duration.ofHours(24);

    /**
     * Sends the 24h-before-expiry warning email for PENDING rows that are
     * still actionable but close to lapsing. Idempotent: each row's
     * {@code expiry_warning_sent_at} is stamped so a row is warned at most
     * once per challenge window.
     */
    @Transactional
    public int sweepExpiryWarnings() {
        Instant now = Instant.now();
        Instant warnBefore = now.plus(EXPIRY_WARNING_LEAD_TIME);
        List<WebsiteVerification> soon = verificationRepository
                .findExpiringWithoutWarning(now, warnBefore);
        for (WebsiteVerification v : soon) {
            Website site = v.getWebsite();
            User owner = site.getAddedBy();
            if (owner != null) {
                notificationService.sendSiteLifecycleNotification(
                        owner.getId(), site.getId(), site.getUrl(),
                        site.getOrganization() != null ? site.getOrganization().getId() : null,
                        Notification.NotificationType.VERIFICATION_EXPIRING_SOON,
                        "DNS verification expires soon",
                        "Your DNS TXT verification for " + site.getUrl()
                                + " expires in less than 24 hours. Complete the challenge to keep your verified badge.");
                siteLifecycleEmailService.sendVerificationGraceWarning(owner, site);
            }
            v.setExpiryWarningSentAt(now);
        }
        if (!soon.isEmpty()) {
            log.info("[verify] Sent expiry warnings: count={}", soon.size());
        }
        return soon.size();
    }

    /**
     * Transitions all PENDING rows past their expiry to {@code EXPIRED}.
     * Called by the scheduler; idempotent.
     */
    @Transactional
    public int sweepExpired() {
        List<WebsiteVerification> expired = verificationRepository.findExpired(Instant.now());
        UserVerificationService userVerification = userVerificationProvider.getObject();
        for (WebsiteVerification v : expired) {
            v.setStatus(Status.EXPIRED);
            Website site = v.getWebsite();
            site.setVerificationStatus(Website.VerificationStatus.EXPIRED);
            userVerification.requestRecompute(site.getAddedBy());
            User owner = site.getAddedBy();
            if (owner != null) {
                notificationService.sendSiteLifecycleNotification(
                        owner.getId(), site.getId(), site.getUrl(),
                        site.getOrganization() != null ? site.getOrganization().getId() : null,
                        Notification.NotificationType.VERIFICATION_EXPIRED,
                        "Domain verification expired",
                        "The 7-day DNS TXT verification window for " + site.getUrl()
                                + " expired without a successful check. Re-add the website to generate a fresh challenge.");
                siteLifecycleEmailService.sendVerificationExpired(owner, site);
            }
        }
        if (!expired.isEmpty()) {
            log.info("[verify] Swept expired: count={}", expired.size());
        }
        return expired.size();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * 32 base32 characters plus the {@code nabu-verify=} prefix.  Base32 is
     * safe to copy-paste into DNS TXT values (no quoting, no case issues).
     */
    static String generateToken() {
        byte[] buf = new byte[20]; // 20 bytes → 32 base32 chars
        RANDOM.nextBytes(buf);
        return TOKEN_PREFIX + base32(buf);
    }

    private static String base32(byte[] data) {
        final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
        StringBuilder out = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(ALPHABET[(buffer >> (bits - 5)) & 0x1f]);
                bits -= 5;
            }
        }
        if (bits > 0) out.append(ALPHABET[(buffer << (5 - bits)) & 0x1f]);
        return out.toString();
    }

    static String extractHost(String websiteUrl) {
        return watch.nabu.infrastructure.web.UrlUtils.host(websiteUrl);
    }

    LookupResult lookupTxt(String fqdn) {
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
        env.put("com.sun.jndi.dns.timeout.initial", String.valueOf(DNS_TIMEOUT_MS));
        env.put("com.sun.jndi.dns.timeout.retries", "2");
        DirContext ctx = null;
        try {
            ctx = new InitialDirContext(env);
            Attributes attrs = ctx.getAttributes(fqdn, new String[]{"TXT"});
            Attribute txt = attrs.get("TXT");
            if (txt == null) {
                return LookupResult.failure("no TXT record");
            }
            java.util.List<String> values = new java.util.ArrayList<>();
            NamingEnumeration<?> e = txt.getAll();
            while (e.hasMore()) {
                Object v = e.next();
                if (v != null) {
                    // JNDI returns TXT values quoted; unwrap one layer of quotes.
                    String s = v.toString();
                    if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
                        s = s.substring(1, s.length() - 1);
                    }
                    values.add(s);
                }
            }
            return LookupResult.success(values);
        } catch (NamingException e) {
            return LookupResult.failure(e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (ctx != null) {
                try { ctx.close(); } catch (NamingException ignored) { }
            }
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    record LookupResult(boolean ok, java.util.List<String> values, String error) {
        static LookupResult success(java.util.List<String> values) {
            return new LookupResult(true, values, null);
        }
        static LookupResult failure(String error) {
            return new LookupResult(false, java.util.List.of(), error);
        }
    }
}
