package watch.nabu.application.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Performs a real TLS handshake against the supplied URL and extracts
 * certificate metadata. Used by the public SSL-check tool.
 *
 * <p>This is a sync, in-process inspection — independent of the Go worker
 * pipeline — because public-tool callers expect a sub-second response.
 */
@Slf4j
@Service
public class SslInspector {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 5_000;

    public Result inspect(URI uri, InetAddress resolvedAddress) {
        String host = uri.getHost();
        int port = uri.getPort() == -1 ? 443 : uri.getPort();

        try {
            SSLContext ctx = SSLContext.getDefault();
            SSLSocketFactory factory = ctx.getSocketFactory();
            try (SSLSocket socket = (SSLSocket) factory.createSocket()) {
                // Connect by IP, not hostname, to defeat DNS rebinding between the
                // validator's lookup and the socket's lookup. SNI + handshake still
                // use the original hostname so cert verification works correctly.
                socket.connect(new InetSocketAddress(resolvedAddress, port), CONNECT_TIMEOUT_MS);
                socket.setSoTimeout(READ_TIMEOUT_MS);

                SSLParameters params = socket.getSSLParameters();
                params.setServerNames(List.of(new SNIHostName(host)));
                params.setEndpointIdentificationAlgorithm("HTTPS");
                socket.setSSLParameters(params);

                socket.startHandshake();

                Certificate[] chain = socket.getSession().getPeerCertificates();
                if (chain.length == 0 || !(chain[0] instanceof X509Certificate leaf)) {
                    return Result.failed("No certificate returned");
                }

                Instant notAfter = leaf.getNotAfter().toInstant();
                long daysRemaining = Duration.between(Instant.now(), notAfter).toDays();
                String issuer = leaf.getIssuerX500Principal().getName("RFC1779");
                String subject = leaf.getSubjectX500Principal().getName("RFC1779");
                String tlsVersion = socket.getSession().getProtocol();

                String severity = daysRemaining < 0 ? "critical"
                        : daysRemaining < 7 ? "critical"
                        : daysRemaining < 30 ? "warning"
                        : "ok";

                return new Result(
                        true,
                        host,
                        port,
                        tlsVersion,
                        truncate(subject, 200),
                        truncate(issuer, 200),
                        notAfter.truncatedTo(ChronoUnit.SECONDS).toString(),
                        daysRemaining,
                        severity,
                        null);
            }
        } catch (IOException e) {
            log.info("[tools/ssl] handshake failed host={}: {}", host, e.getMessage());
            return Result.failed("TLS handshake failed: " + e.getMessage());
        } catch (Exception e) {
            log.warn("[tools/ssl] unexpected error host={}: {}", host, e.getMessage());
            return Result.failed("Could not inspect certificate");
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    public record Result(
            boolean success,
            String host,
            int port,
            String tlsVersion,
            String subject,
            String issuer,
            String notAfter,
            long daysRemaining,
            String severity,
            String error) {

        public static Result failed(String error) {
            return new Result(false, null, 0, null, null, null, null, 0, "critical", error);
        }
    }
}
