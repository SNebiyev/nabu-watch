package watch.nabu.application.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Fetches HTML for the public scan tools. Connects by the validator-resolved
 * IP to defeat DNS rebinding (same approach as {@link SslInspector}), caps the
 * download size, and rejects any redirect that would land on a different host.
 */
@Slf4j
@Service
public class HtmlFetcher {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 8_000;
    private static final int MAX_BYTES = 1_500_000;
    private static final String USER_AGENT = "nabu-watch-tools/1.0 (+https://github.com/SNebiyev/nabu-watch)";

    public Result fetch(URI uri, InetAddress resolvedAddress) {
        String scheme = uri.getScheme();
        boolean https = "https".equalsIgnoreCase(scheme);
        int port = uri.getPort() != -1 ? uri.getPort() : (https ? 443 : 80);

        try (Socket raw = https
                ? javax.net.ssl.SSLSocketFactory.getDefault().createSocket()
                : new Socket()) {
            raw.connect(new InetSocketAddress(resolvedAddress, port), CONNECT_TIMEOUT_MS);
            raw.setSoTimeout(READ_TIMEOUT_MS);

            if (https && raw instanceof javax.net.ssl.SSLSocket sslSocket) {
                javax.net.ssl.SSLParameters params = sslSocket.getSSLParameters();
                params.setServerNames(java.util.List.of(new javax.net.ssl.SNIHostName(uri.getHost())));
                params.setEndpointIdentificationAlgorithm("HTTPS");
                sslSocket.setSSLParameters(params);
                sslSocket.startHandshake();
            }

            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            if (uri.getRawQuery() != null) {
                path = path + "?" + uri.getRawQuery();
            }

            String request = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + uri.getHost() + "\r\n"
                    + "User-Agent: " + USER_AGENT + "\r\n"
                    + "Accept: text/html,application/xhtml+xml\r\n"
                    + "Accept-Encoding: identity\r\n"
                    + "Connection: close\r\n\r\n";
            raw.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            raw.getOutputStream().flush();

            return readResponse(raw.getInputStream());
        } catch (IOException e) {
            log.info("[tools/html] fetch failed host={} : {}", uri.getHost(), e.getMessage());
            return Result.failed("Could not fetch page: " + e.getMessage());
        } catch (Exception e) {
            log.warn("[tools/html] unexpected error host={} : {}", uri.getHost(), e.getMessage());
            return Result.failed("Could not fetch page");
        }
    }

    private static Result readResponse(InputStream in) throws IOException {
        // Read up to MAX_BYTES then split headers/body at the first \r\n\r\n.
        byte[] buf = new byte[8192];
        java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
        int n;
        while ((n = in.read(buf)) != -1) {
            all.write(buf, 0, n);
            if (all.size() >= MAX_BYTES) break;
        }
        byte[] data = all.toByteArray();
        int sep = indexOfDoubleCrlf(data);
        if (sep < 0) {
            return Result.failed("Malformed HTTP response");
        }
        String headerBlock = new String(data, 0, sep, StandardCharsets.US_ASCII);
        int statusEnd = headerBlock.indexOf("\r\n");
        String statusLine = statusEnd < 0 ? headerBlock : headerBlock.substring(0, statusEnd);
        String[] parts = statusLine.split(" ", 3);
        int status = parts.length >= 2 ? safeInt(parts[1]) : 0;

        Map<String, String> headers = new LinkedHashMap<>();
        Charset cs = StandardCharsets.UTF_8;
        for (String line : headerBlock.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            // Keep only the first value seen for a given header name — adequate
            // for the security-headers audit and the body charset detection.
            headers.putIfAbsent(name, value);
            if ("content-type".equals(name) && value.toLowerCase(Locale.ROOT).contains("charset=")) {
                String lower = value.toLowerCase(Locale.ROOT);
                int idx = lower.indexOf("charset=") + "charset=".length();
                String charsetName = lower.substring(idx).split("[;\\s]")[0].trim();
                try {
                    cs = Charset.forName(charsetName);
                } catch (Exception ignored) { }
            }
        }

        String body = new String(data, sep + 4, data.length - (sep + 4), cs);
        return new Result(true, status, headers, body, null);
    }

    private static int indexOfDoubleCrlf(byte[] data) {
        for (int i = 0; i < data.length - 3; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n' && data[i + 2] == '\r' && data[i + 3] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static int safeInt(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    public record Result(boolean success, int status, Map<String, String> headers, String body, String error) {
        public static Result failed(String error) {
            return new Result(false, 0, Map.of(), null, error);
        }

        /** Convenience: case-insensitive header lookup, null when missing. */
        public String header(String name) {
            return headers == null ? null : headers.get(name.toLowerCase(Locale.ROOT));
        }

        /**
         * Returns null when the fetch produced a usable 2xx/3xx response;
         * otherwise returns a short, user-safe failure message that callers
         * can wrap into their own per-tool error type.
         */
        public String failureReason() {
            if (!success) return error;
            if (status < 200 || status >= 400) return "Page returned HTTP " + status;
            return null;
        }
    }
}
