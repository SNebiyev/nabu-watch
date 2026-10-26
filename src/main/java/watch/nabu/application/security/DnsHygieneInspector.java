package watch.nabu.application.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watch.nabu.infrastructure.web.UrlUtils;

import javax.naming.directory.DirContext;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * DNS hygiene audit — probes SPF, DMARC, DKIM and CAA records and scores
 * the result. SPF / DMARC / CAA / DKIM-selector probes run in parallel
 * against a short-lived pool so a cold miss (e.g. a domain with no DKIM
 * at any known selector) completes in seconds instead of minutes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DnsHygieneInspector {

    private static final int OVERALL_BUDGET_MS = 6_000;
    private static final int PARALLELISM = 8;

    private final DnsLookup dns;

    public Result inspect(String websiteUrl) {
        String apex = UrlUtils.host(websiteUrl);
        if (apex == null || apex.isBlank()) {
            return Result.failed(null, "URL has no host");
        }

        DirContext ctx = dns.newContext();
        if (ctx == null) {
            return Result.failed(apex, "DNS resolver unavailable");
        }
        var pool = Executors.newFixedThreadPool(PARALLELISM);
        try {
            CompletableFuture<Check> spf = CompletableFuture.supplyAsync(() -> checkSpf(ctx, apex), pool);
            CompletableFuture<Check> dmarc = CompletableFuture.supplyAsync(() -> checkDmarc(ctx, apex), pool);
            CompletableFuture<Check> dkim = CompletableFuture.supplyAsync(() -> checkDkimHint(ctx, apex, pool), pool);
            CompletableFuture<Check> caa = CompletableFuture.supplyAsync(() -> checkCaa(ctx, apex), pool);

            try {
                CompletableFuture.allOf(spf, dmarc, dkim, caa)
                        .orTimeout(OVERALL_BUDGET_MS, TimeUnit.MILLISECONDS)
                        .join();
            } catch (Exception ignored) { }

            Check spfR = resultOrWarn(spf, "SPF", "SPF check timed out", 25);
            Check dmarcR = resultOrWarn(dmarc, "DMARC", "DMARC check timed out", 30);
            Check dkimR = resultOrWarn(dkim, "DKIM", "DKIM check timed out", 20);
            Check caaR = resultOrWarn(caa, "CAA", "CAA check timed out", 25);

            int score = spfR.weight + dmarcR.weight + dkimR.weight + caaR.weight;
            String verdict = score >= 80 ? "Strong"
                    : score >= 50 ? "Partial"
                    : "Weak";
            return new Result(true, apex, score, verdict,
                    List.of(spfR, dmarcR, dkimR, caaR), null);
        } finally {
            pool.shutdownNow();
            dns.close(ctx);
        }
    }

    private static Check resultOrWarn(CompletableFuture<Check> f, String label, String message, int max) {
        Check done = f.getNow(null);
        return done != null ? done : Check.warn(label, message, 0, max);
    }

    private Check checkSpf(DirContext ctx, String apex) {
        List<String> txt = dns.lookup(ctx, apex, "TXT");
        String record = txt.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith("v=spf1"))
                .findFirst().orElse(null);
        if (record == null) {
            return Check.fail("SPF", "No v=spf1 record", 0, 25);
        }
        String lower = record.toLowerCase(Locale.ROOT);
        if (lower.contains(" +all")) {
            return Check.fail("SPF", "SPF uses +all (permits any sender)", 0, 25);
        }
        if (lower.contains(" ?all")) {
            return Check.warn("SPF", "SPF uses ?all (neutral)", 10, 25);
        }
        if (lower.contains(" ~all") || lower.contains(" -all")) {
            return Check.pass("SPF", "Record present with a restrictive all-qualifier", 25, 25);
        }
        return Check.warn("SPF", "SPF record present but no all-qualifier", 15, 25);
    }

    private Check checkDmarc(DirContext ctx, String apex) {
        List<String> txt = dns.lookup(ctx, "_dmarc." + apex, "TXT");
        String record = txt.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith("v=dmarc1"))
                .findFirst().orElse(null);
        if (record == null) {
            return Check.fail("DMARC", "No DMARC policy at _dmarc." + apex, 0, 30);
        }
        String lower = record.toLowerCase(Locale.ROOT);
        if (lower.contains("p=reject")) {
            return Check.pass("DMARC", "p=reject (strict)", 30, 30);
        }
        if (lower.contains("p=quarantine")) {
            return Check.pass("DMARC", "p=quarantine", 22, 30);
        }
        if (lower.contains("p=none")) {
            return Check.warn("DMARC", "p=none (monitoring-only)", 10, 30);
        }
        return Check.warn("DMARC", "Policy present but policy tag not found", 15, 30);
    }

    /**
     * DKIM selectors are mail-provider-specific. We probe a union of the
     * well-known selectors used by major transactional/SMTP providers so
     * we don't penalise domains that sign mail with a less-common name.
     */
    private static final List<String> DKIM_SELECTORS = List.of(
            "default", "mail", "dkim",
            "resend", "pm", "k1", "mx", "s1", "s2", "amazonses",
            "google", "selector1", "selector2",
            "fm1", "fm2", "fm3",
            "protonmail", "protonmail2", "protonmail3",
            "zoho", "scph0522", "mailjet"
    );

    private Check checkDkimHint(DirContext ctx, String apex, java.util.concurrent.Executor executor) {
        List<CompletableFuture<String>> probes = DKIM_SELECTORS.stream()
                .map(selector -> CompletableFuture.supplyAsync(() -> {
                    String name = selector + "._domainkey." + apex;
                    List<String> txt = dns.lookup(ctx, name, "TXT");
                    boolean looksDkim = txt.stream().anyMatch(s -> {
                        String lower = s.toLowerCase(Locale.ROOT);
                        return lower.contains("v=dkim1") || lower.contains("p=");
                    });
                    return looksDkim ? selector : null;
                }, executor))
                .toList();

        try {
            CompletableFuture.allOf(probes.toArray(new CompletableFuture[0]))
                    .orTimeout(OVERALL_BUDGET_MS, TimeUnit.MILLISECONDS)
                    .exceptionally(e -> null)
                    .join();
        } catch (Exception ignored) { }

        for (CompletableFuture<String> p : probes) {
            String hit = p.getNow(null);
            if (hit != null) {
                return Check.pass("DKIM", "DKIM selector '" + hit + "' present", 20, 20);
            }
        }
        return Check.warn("DKIM",
                "No known DKIM selector found — add a TXT record at <selector>._domainkey." + apex,
                5, 20);
    }

    private Check checkCaa(DirContext ctx, String apex) {
        // CAA is DNS type 257. com.sun.jndi.dns only exposes named types;
        // the raw numeric "257" works and returns byte[] RDATA — existence
        // is all we need.
        List<String> caa = dns.lookup(ctx, apex, "257");
        if (caa.isEmpty()) {
            return Check.warn("CAA", "No CAA record — any CA may issue certificates for this domain", 5, 25);
        }
        return Check.pass("CAA", "CAA record present (" + caa.size() + " entr" + (caa.size() == 1 ? "y" : "ies") + ")", 25, 25);
    }

    public record Result(
            boolean success,
            String apex,
            int score,
            String verdict,
            List<Check> checks,
            String error) {

        public static Result failed(String apex, String error) {
            return new Result(false, apex, 0, null, List.of(), error);
        }
    }

    public record Check(String label, String status, String message, int weight, int maxWeight) {
        static Check pass(String label, String message, int weight, int max) {
            return new Check(label, "pass", message, weight, max);
        }
        static Check warn(String label, String message, int weight, int max) {
            return new Check(label, "warn", message, weight, max);
        }
        static Check fail(String label, String message, int weight, int max) {
            return new Check(label, "fail", message, weight, max);
        }
    }
}
