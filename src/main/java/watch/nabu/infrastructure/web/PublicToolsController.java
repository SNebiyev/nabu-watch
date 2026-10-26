package watch.nabu.infrastructure.web;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import watch.nabu.application.tools.CookieBannerInspector;
import watch.nabu.application.tools.GdprComposite;
import watch.nabu.application.tools.LegalLinkInspector;
import watch.nabu.application.tools.SslInspector;
import watch.nabu.application.tools.ToolRateLimitService;
import watch.nabu.application.tools.ToolScanCache;
import watch.nabu.application.tools.ToolUrlValidator;
import watch.nabu.application.tools.TurnstileService;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Anonymous, public-facing scan tools. Each endpoint runs the same pipeline:
 * Turnstile verify → URL validate → cache lookup → rate-limit → scan → cache put.
 * The shared logic lives in {@link #runTool}; per-endpoint methods only supply
 * the tool name, result type, and the actual scan invocation.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/tools")
@RequiredArgsConstructor
public class PublicToolsController {

    private static final String TOOL_SSL = "ssl-check";
    private static final String TOOL_IMPRESSUM = "impressum-checker";
    private static final String TOOL_PRIVACY = "privacy-policy-check";
    private static final String TOOL_COOKIE = "cookie-banner-checker";
    private static final String TOOL_GDPR = "gdpr-scan";

    private final TurnstileService turnstile;
    private final ToolUrlValidator urlValidator;
    private final ToolRateLimitService rateLimit;
    private final ToolScanCache cache;
    private final SslInspector sslInspector;
    private final LegalLinkInspector legalLinkInspector;
    private final CookieBannerInspector cookieBannerInspector;
    private final GdprComposite gdprComposite;
    private final MeterRegistry meters;

    @PostMapping("/ssl-check")
    public SslInspector.Result sslCheck(HttpServletRequest req, @RequestBody @Valid ToolScanRequest body) {
        return runTool(TOOL_SSL, SslInspector.Result.class, SslInspector.Result::success, req, body,
                r -> sslInspector.inspect(r.uri(), r.address()));
    }

    @PostMapping("/impressum-checker")
    public LegalLinkInspector.Result impressumCheck(HttpServletRequest req, @RequestBody @Valid ToolScanRequest body) {
        return runTool(TOOL_IMPRESSUM, LegalLinkInspector.Result.class, LegalLinkInspector.Result::success, req, body,
                r -> legalLinkInspector.inspect(r.uri(), r.address(), LegalLinkInspector.Persona.IMPRESSUM));
    }

    @PostMapping("/privacy-policy-check")
    public LegalLinkInspector.Result privacyPolicyCheck(HttpServletRequest req, @RequestBody @Valid ToolScanRequest body) {
        return runTool(TOOL_PRIVACY, LegalLinkInspector.Result.class, LegalLinkInspector.Result::success, req, body,
                r -> legalLinkInspector.inspect(r.uri(), r.address(), LegalLinkInspector.Persona.PRIVACY_POLICY));
    }

    @PostMapping("/cookie-banner-checker")
    public CookieBannerInspector.Result cookieBannerCheck(HttpServletRequest req, @RequestBody @Valid ToolScanRequest body) {
        return runTool(TOOL_COOKIE, CookieBannerInspector.Result.class, CookieBannerInspector.Result::success, req, body,
                r -> cookieBannerInspector.inspect(r.uri(), r.address()));
    }

    @PostMapping("/gdpr-scan")
    public GdprComposite.Result gdprScan(HttpServletRequest req, @RequestBody @Valid ToolScanRequest body) {
        return runTool(TOOL_GDPR, GdprComposite.Result.class, GdprComposite.Result::success, req, body,
                r -> gdprComposite.inspect(r.uri(), r.address()));
    }

    private <T> T runTool(
            String toolName,
            Class<T> resultType,
            Predicate<T> isCacheable,
            HttpServletRequest req,
            ToolScanRequest body,
            Function<ToolUrlValidator.Resolved, T> scan) {

        meters.counter("nabu.tools.requests", "tool", toolName).increment();

        String ip = HttpRequestUtils.clientIp(req);
        if (!turnstile.verify(body.turnstileToken(), ip)) {
            meters.counter("nabu.tools.turnstile_rejected", "tool", toolName).increment();
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Challenge failed — please retry");
        }

        ToolUrlValidator.Resolved resolved = urlValidator.validate(body.url());
        String normalized = resolved.uri().toString();

        Optional<T> cached = cache.get(toolName, normalized, resultType);
        if (cached.isPresent()) {
            meters.counter("nabu.tools.cache_hits", "tool", toolName).increment();
            return cached.get();
        }

        rateLimit.checkAndIncrement(toolName, ip, normalized);

        T result = scan.apply(resolved);
        if (isCacheable.test(result)) {
            cache.put(toolName, normalized, result);
        }
        return result;
    }

    public record ToolScanRequest(
            @NotBlank @Size(max = 2048) String url,
            @Size(max = 4096) String turnstileToken) {
    }
}
