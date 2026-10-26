package watch.nabu.application.webhook;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watch.nabu.domain.model.WebhookConfig;
import watch.nabu.domain.model.WebhookDelivery;
import watch.nabu.domain.model.WebhookDelivery.DeliveryStatus;
import watch.nabu.infrastructure.persistence.WebhookConfigRepository;
import watch.nabu.infrastructure.persistence.WebhookDeliveryRepository;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookService {

    private static final int MAX_ATTEMPTS = WebhookDelivery.MAX_ATTEMPTS;
    private static final int[] RETRY_DELAYS = {1, 5, 15};  // minutes between attempts
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    private final WebhookConfigRepository webhookConfigRepository;
    private final WebhookDeliveryRepository webhookDeliveryRepository;
    private final WebhookPayloadBuilder payloadBuilder;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT)
            .build();

    // ── CRUD ──────────────────────────────────────────────────────────────────

    private static String sign(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return "sha256=" + HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC signing failed", e);
        }
    }

    @Transactional(readOnly = true)
    public List<WebhookConfig> listForOrg(UUID organizationId) {
        return webhookConfigRepository.findByOrganizationId(organizationId);
    }

    /**
     * Validates that the webhook URL is safe to call (no SSRF).
     * Blocks private IPs, loopback, link-local, and cloud metadata endpoints.
     * DNS resolution failures are allowed (the host may not be reachable at
     * registration time but could be valid later); delivery will simply fail.
     */
    private static final Set<String> BLOCKED_METADATA_HOSTS = Set.of(
            "metadata.google.internal", "metadata.goog", "metadata.azure.com",
            "169.254.169.254", "100.100.100.200", "192.0.0.192");

    static void assertSafeUrl(String url) {
        URI uri = URI.create(url);
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equals("http") && !scheme.equals("https"))) {
            throw new IllegalArgumentException("Webhook URL must use http or https scheme");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Webhook URL must have a valid host");
        }
        if (BLOCKED_METADATA_HOSTS.contains(host.toLowerCase())) {
            throw new IllegalArgumentException(
                    "Webhook URL must not target cloud metadata services");
        }
        try {
            requireAllPublic(host);
        } catch (UnknownHostException e) {
            // Allow unresolvable hosts at registration; delivery re-checks.
            log.debug("Could not resolve webhook host '{}': {}", host, e.getMessage());
        }
    }

    /**
     * Resolves every A/AAAA for {@code host} and rejects if any address is
     * private, loopback, link-local, CGNAT, ULA, or a known metadata endpoint.
     * Called again right before delivery to narrow the DNS-rebinding window.
     */
    private static void requireAllPublic(String host) throws UnknownHostException {
        InetAddress[] addrs = InetAddress.getAllByName(host);
        for (InetAddress addr : addrs) {
            if (isBlockedAddress(addr)) {
                throw new IllegalArgumentException(
                        "Webhook URL resolves to a blocked address: " + addr.getHostAddress());
            }
        }
    }

    private static boolean isBlockedAddress(InetAddress addr) {
        if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress() || addr.isAnyLocalAddress()
                || addr.isMulticastAddress()) {
            return true;
        }
        String ip = addr.getHostAddress();
        if (BLOCKED_METADATA_HOSTS.contains(ip)) return true;
        if (addr instanceof Inet4Address) {
            byte[] b = addr.getAddress();
            int first = b[0] & 0xFF;
            int second = b[1] & 0xFF;
            // 169.254/16 link-local (also caught above) + 100.64.0.0/10 CGNAT
            if (first == 169 && second == 254) return true;
            if (first == 100 && (second >= 64 && second <= 127)) return true;
        } else if (addr instanceof Inet6Address) {
            byte[] b = addr.getAddress();
            int first = b[0] & 0xFF;
            // fc00::/7 ULA
            if ((first & 0xFE) == 0xFC) return true;
        }
        return false;
    }

    @Transactional
    public WebhookConfig create(UUID organizationId, String name, String url,
                                String secret, WebhookConfig.WebhookType type) {
        assertSafeUrl(url);
        WebhookConfig config = WebhookConfig.builder()
                .organizationId(organizationId)
                .name(name)
                .url(url)
                .secret(secret)
                .type(type)
                .build();
        return webhookConfigRepository.save(config);
    }

    // ── Delivery ──────────────────────────────────────────────────────────────

    @Transactional
    public void delete(UUID configId, UUID organizationId) {
        if (!webhookConfigRepository.existsByIdAndOrganizationId(configId, organizationId)) {
            throw new WebhookNotFoundException(configId);
        }
        webhookConfigRepository.deleteById(configId);
    }

    /**
     * Enqueues and immediately attempts delivery to all enabled webhooks for the
     * given organization.  Called from the event listener after transaction commit.
     */
    @Transactional
    public void dispatchScanEvent(UUID organizationId, ScanWebhookEvent event) {
        List<WebhookConfig> configs =
                webhookConfigRepository.findByOrganizationIdAndEnabledTrue(organizationId);
        for (WebhookConfig config : configs) {
            Map<String, Object> payload = payloadBuilder.build(config.getType(), event);
            WebhookDelivery delivery = webhookDeliveryRepository.save(
                    WebhookDelivery.builder()
                            .webhookConfigId(config.getId())
                            .eventType("SCAN_RESULT")
                            .payload(payload)
                            .build());
            attempt(config, delivery, payload);
        }
    }

    // ── Retry scheduler ───────────────────────────────────────────────────────

    /**
     * Sends a test ping to a single webhook config.
     */
    @Transactional
    public void sendTest(UUID configId, UUID organizationId) {
        WebhookConfig config = webhookConfigRepository
                .findByIdAndOrganizationId(configId, organizationId)
                .orElseThrow(() -> new WebhookNotFoundException(configId));
        Map<String, Object> payload = payloadBuilder.buildTest(config.getType());
        WebhookDelivery delivery = webhookDeliveryRepository.save(
                WebhookDelivery.builder()
                        .webhookConfigId(config.getId())
                        .eventType("TEST")
                        .payload(payload)
                        .build());
        attempt(config, delivery, payload);
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT2M")
    @Transactional
    public void retryFailed() {
        List<WebhookDelivery> due = webhookDeliveryRepository.findPendingRetries(
                DeliveryStatus.FAILED, MAX_ATTEMPTS, Instant.now());
        if (due.isEmpty()) return;
        log.info("Retrying {} failed webhook deliveries", due.size());
        for (WebhookDelivery delivery : due) {
            webhookConfigRepository.findById(delivery.getWebhookConfigId())
                    .filter(WebhookConfig::isEnabled)
                    .ifPresent(config -> attempt(config, delivery, delivery.getPayload()));
        }
    }

    private void attempt(WebhookConfig config, WebhookDelivery delivery,
                         Map<String, Object> payload) {
        delivery.setAttemptCount(delivery.getAttemptCount() + 1);
        delivery.setLastAttemptAt(Instant.now());
        delivery.setStatus(DeliveryStatus.PENDING);

        try {
            URI uri = URI.create(config.getUrl());
            // Re-validate DNS right before sending to defeat DNS-rebinding TOCTOU.
            requireAllPublic(uri.getHost());
            String body = payloadBuilder.toJson(payload);
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(HTTP_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "nabu-watch/1.0")
                    .POST(HttpRequest.BodyPublishers.ofString(body));

            if (config.getSecret() != null && !config.getSecret().isBlank()) {
                reqBuilder.header("X-Nabu-Signature", sign(body, config.getSecret()));
            }

            HttpResponse<Void> response = httpClient.send(
                    reqBuilder.build(), HttpResponse.BodyHandlers.discarding());

            delivery.setResponseStatus(response.statusCode());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                delivery.setStatus(DeliveryStatus.DELIVERED);
                delivery.setNextRetryAt(null);
                log.info("Webhook delivered: configId={} deliveryId={} status={}",
                        config.getId(), delivery.getId(), response.statusCode());
            } else {
                markFailed(delivery, "HTTP " + response.statusCode(), null);
            }
        } catch (Exception e) {
            markFailed(delivery, null, e.getMessage());
        }
        webhookDeliveryRepository.save(delivery);
    }

    @Transactional
    public WebhookConfig update(UUID configId, UUID organizationId,
                                String name, String url,
                                String secret, WebhookConfig.WebhookType type,
                                boolean enabled) {
        WebhookConfig config = webhookConfigRepository
                .findByIdAndOrganizationId(configId, organizationId)
                .orElseThrow(() -> new WebhookNotFoundException(configId));
        assertSafeUrl(url);
        config.setName(name);
        config.setUrl(url);
        if (secret != null) config.setSecret(secret.isBlank() ? null : secret);
        config.setType(type);
        config.setEnabled(enabled);
        return config;
    }

    private void markFailed(WebhookDelivery delivery, String responseError, String exception) {
        delivery.setStatus(DeliveryStatus.FAILED);
        String msg = responseError != null ? responseError : exception;
        if (msg != null && msg.length() > 500) msg = msg.substring(0, 500);
        delivery.setErrorMessage(msg);

        if (delivery.getAttemptCount() < MAX_ATTEMPTS) {
            int delayMinutes = RETRY_DELAYS[Math.min(delivery.getAttemptCount() - 1,
                    RETRY_DELAYS.length - 1)];
            delivery.setNextRetryAt(Instant.now().plus(Duration.ofMinutes(delayMinutes)));
            log.warn("Webhook delivery failed (attempt {}/{}), retry in {}m: configId={} error={}",
                    delivery.getAttemptCount(), MAX_ATTEMPTS, delayMinutes,
                    delivery.getWebhookConfigId(), msg);
        } else {
            delivery.setNextRetryAt(null);
            log.error("Webhook delivery permanently failed after {} attempts: configId={} error={}",
                    MAX_ATTEMPTS, delivery.getWebhookConfigId(), msg);
        }
    }
}
