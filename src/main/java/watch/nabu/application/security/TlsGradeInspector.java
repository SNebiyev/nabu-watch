package watch.nabu.application.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watch.nabu.application.tools.ToolUrlValidator;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Locale;

/**
 * Connects to the website's :443 endpoint and reports the negotiated TLS
 * version, cipher suite and certificate-chain basics. Sync, in-process,
 * verified-only manual scan — no headless, no external service. Like
 * {@link SecurityHeadersInspector} we connect by the validator-resolved
 * IP with an SNI-pinned hostname so a verified-but-malicious owner cannot
 * DNS-rebind to an internal target.
 *
 * <p>The grade is a simplified Mozilla/SSL Labs-style letter derived
 * purely from the protocol version and cipher family — we intentionally
 * do not attempt a full SSL Labs audit (would require HTTP/2 probe,
 * session tickets, OCSP stapling, cipher-suite enumeration).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TlsGradeInspector {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int HANDSHAKE_TIMEOUT_MS = 5_000;

    private final ToolUrlValidator urlValidator;

    public Result inspect(String websiteUrl) {
        ToolUrlValidator.Resolved resolved;
        try {
            resolved = urlValidator.validate(websiteUrl);
        } catch (RuntimeException e) {
            return Result.failed(null, "URL not scannable: " + e.getMessage());
        }

        URI uri = resolved.uri();
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return Result.failed(uri.getHost(), "Site is not served over HTTPS");
        }
        int port = uri.getPort() != -1 ? uri.getPort() : 443;

        try (SSLSocket socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket()) {
            socket.connect(new InetSocketAddress(resolved.address(), port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);

            SSLParameters params = socket.getSSLParameters();
            params.setServerNames(List.of(new SNIHostName(uri.getHost())));
            params.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(params);
            socket.startHandshake();

            String protocol = socket.getSession().getProtocol();
            String cipher = socket.getSession().getCipherSuite();
            Certificate[] chain = socket.getSession().getPeerCertificates();
            CertInfo cert = chain.length == 0 ? null : describe((X509Certificate) chain[0]);

            int score = gradeScore(protocol, cipher);
            String grade = toLetter(score);
            return new Result(true, uri.getHost(), protocol, cipher, chain.length,
                    cert, grade, score, null);
        } catch (Exception e) {
            log.info("[tls-grade] handshake failed host={} : {}", uri.getHost(), e.getMessage());
            return Result.failed(uri.getHost(), "TLS handshake failed: " + e.getMessage());
        }
    }

    private static int gradeScore(String protocol, String cipher) {
        String p = protocol == null ? "" : protocol.toUpperCase(Locale.ROOT);
        String c = cipher == null ? "" : cipher.toUpperCase(Locale.ROOT);
        int score = 50;
        if (p.startsWith("TLSV1.3")) score = 95;
        else if (p.startsWith("TLSV1.2")) score = 80;
        else if (p.startsWith("TLSV1.1") || p.startsWith("TLSV1.0")) score = 35;
        else if (p.startsWith("SSL")) score = 10;

        // AEAD ciphers (GCM / CHACHA20) are the modern-safe family; CBC
        // is still present but weaker; RC4/NULL/EXPORT are disqualifying.
        if (c.contains("GCM") || c.contains("CHACHA20")) score = Math.min(100, score + 5);
        else if (c.contains("CBC")) score = Math.max(0, score - 10);
        if (c.contains("RC4") || c.contains("NULL") || c.contains("EXPORT") || c.contains("_DES_")) {
            score = Math.min(score, 15);
        }
        return Math.max(0, Math.min(100, score));
    }

    private static String toLetter(int score) {
        if (score >= 90) return "A";
        if (score >= 75) return "B";
        if (score >= 55) return "C";
        if (score >= 35) return "D";
        return "F";
    }

    private static CertInfo describe(X509Certificate cert) {
        String subject = cert.getSubjectX500Principal().getName();
        String issuer = cert.getIssuerX500Principal().getName();
        long daysUntilExpiry = (cert.getNotAfter().getTime() - System.currentTimeMillis()) / 86_400_000L;
        return new CertInfo(subject, issuer, cert.getNotBefore().toString(),
                cert.getNotAfter().toString(), daysUntilExpiry,
                cert.getSigAlgName());
    }

    public record Result(
            boolean success,
            String host,
            String protocol,
            String cipher,
            int chainLength,
            CertInfo cert,
            String grade,
            int score,
            String error) {

        public static Result failed(String host, String error) {
            return new Result(false, host, null, null, 0, null, null, 0, error);
        }
    }

    public record CertInfo(
            String subject,
            String issuer,
            String notBefore,
            String notAfter,
            long daysUntilExpiry,
            String signatureAlgorithm) { }
}
