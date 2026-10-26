package watch.nabu.application.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Produces a website-tailored remediation paragraph for a failing security
 * finding. The static per-finding guidance lives on the frontend; this
 * service only kicks in when the owner clicks "Ask AI to help me fix this"
 * — we contact Gemini, cache the response per (orgId, scan, finding) and
 * enforce a per-org daily cap via Redis.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SecurityRemediationService {

    private static final Duration CACHE_TTL = Duration.ofHours(24);
    private static final Duration QUOTA_TTL = Duration.ofHours(25);

    private final GeminiClient gemini;
    private final StringRedisTemplate redis;

    @Value("${nabu.ai.remediation.per-org-per-day:20}")
    private long perOrgPerDay;

    public record Request(UUID orgId, String websiteUrl, String scan,
                          String findingKey, String findingContext) { }

    public String getRemediation(Request req) {
        if (!gemini.isAvailable()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "AI remediation is not configured");
        }

        String cacheKey = cacheKey(req);
        String cached = redis.opsForValue().get(cacheKey);
        if (cached != null) {
            return cached;
        }

        // Enforce quota BEFORE calling the upstream so failed calls don't
        // consume the user's daily budget. Read-then-increment is racy but
        // the cap here is forgiveness-friendly (at most a couple of extra
        // calls under concurrent load) and the write is cheaper than a Lua
        // round-trip.
        String dayKey = dayKey(req.orgId());
        long used = currentCount(dayKey);
        if (used >= perOrgPerDay) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Daily AI remediation limit reached (" + perOrgPerDay + "). Try again tomorrow.");
        }

        String response = gemini.generate(buildPrompt(req));
        if (response == null || response.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "AI service did not return a usable response");
        }

        // Charge + cache only after a usable response.
        redis.opsForValue().increment(dayKey);
        redis.expire(dayKey, QUOTA_TTL.getSeconds(), TimeUnit.SECONDS);

        String trimmed = response.trim();
        redis.opsForValue().set(cacheKey, trimmed, CACHE_TTL.getSeconds(), TimeUnit.SECONDS);
        return trimmed;
    }

    private long currentCount(String dayKey) {
        String raw = redis.opsForValue().get(dayKey);
        if (raw == null) return 0L;
        try { return Long.parseLong(raw); } catch (NumberFormatException e) { return 0L; }
    }

    private static String dayKey(UUID orgId) {
        return "ai-remediation:org:" + orgId + ":" + LocalDate.now(ZoneOffset.UTC);
    }

    private static String cacheKey(Request r) {
        String raw = r.scan() + "|" + r.findingKey() + "|" + r.websiteUrl();
        return "ai-remediation:cache:" + r.orgId() + ":" + sha256Hex(raw);
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // Every JRE ships SHA-256; never reached.
            throw new IllegalStateException(e);
        }
    }

    private static String buildPrompt(Request r) {
        return """
You are a pragmatic web-security advisor. A website owner ran an automated
audit on %s and one of the checks failed:

Finding: %s
Scan type: %s

Write a short, actionable remediation (3–6 sentences, plain prose — no
markdown, no bullet lists, no code blocks). Focus on what the owner should
do today to fix this specific finding on this specific domain. Mention
trade-offs only when they materially affect the rollout order. Do not
recommend a specific product, CDN, or hosting brand unless the finding
explicitly calls one out; keep advice vendor-neutral ("your hosting
layer", "your reverse proxy", "your mail provider" etc.).
""".formatted(
                safe(r.websiteUrl()),
                safe(r.findingContext()),
                safe(r.scan())
        );
    }

    private static String safe(String s) {
        if (s == null) return "";
        // Trim and cap to keep the prompt bounded; the controller already
        // restricts the body size via Spring defaults but we also don't want
        // a pathological context blowing up token count.
        String t = s.trim();
        return t.length() > 600 ? t.substring(0, 600) + "…" : t;
    }
}
