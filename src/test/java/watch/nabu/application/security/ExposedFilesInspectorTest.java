package watch.nabu.application.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watch.nabu.application.tools.HtmlFetcher;
import watch.nabu.application.tools.ToolUrlValidator;

import java.net.InetAddress;
import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExposedFilesInspectorTest {

    @Mock private HtmlFetcher fetcher;
    @Mock private ToolUrlValidator urlValidator;

    @InjectMocks private ExposedFilesInspector inspector;

    private static HtmlFetcher.Result miss() {
        return new HtmlFetcher.Result(true, 404, Map.of(), "", null);
    }

    private static HtmlFetcher.Result okPlain(String body) {
        return new HtmlFetcher.Result(true, 200,
                Map.of("content-type", "text/plain"), body, null);
    }

    private static HtmlFetcher.Result okHtml(String body) {
        return new HtmlFetcher.Result(true, 200,
                Map.of("content-type", "text/html; charset=utf-8"), body, null);
    }

    @Test
    void flagsEnvWhenNonHtmlAnd200() throws Exception {
        URI base = new URI("https://example.com");
        InetAddress addr = InetAddress.getByAddress("example.com", new byte[]{1, 2, 3, 4});
        when(urlValidator.validate("https://example.com"))
                .thenReturn(new ToolUrlValidator.Resolved(base, addr));
        when(fetcher.fetch(any(), any())).thenReturn(miss());
        when(fetcher.fetch(argThat(u -> u != null && "/.env".equals(u.getPath())), any()))
                .thenReturn(okPlain("DB_PASSWORD=secret\nAPI_KEY=abc"));

        ExposedFilesInspector.Result r = inspector.inspect("https://example.com");

        assertThat(r.success()).isTrue();
        assertThat(r.findings()).hasSize(1);
        assertThat(r.findings().get(0).path()).isEqualTo("/.env");
        assertThat(r.score()).isEqualTo(75);
    }

    @Test
    void suppressesHtmlCatchAllFallback() throws Exception {
        URI base = new URI("https://example.com");
        InetAddress addr = InetAddress.getByAddress("example.com", new byte[]{1, 2, 3, 4});
        when(urlValidator.validate("https://example.com"))
                .thenReturn(new ToolUrlValidator.Resolved(base, addr));
        when(fetcher.fetch(any(), any()))
                .thenReturn(okHtml("<html><body>SPA fallback</body></html>"));

        ExposedFilesInspector.Result r = inspector.inspect("https://example.com");

        assertThat(r.success()).isTrue();
        assertThat(r.findings()).isEmpty();
        assertThat(r.score()).isEqualTo(100);
        assertThat(r.verdict()).isEqualTo("No exposed paths");
    }

    @Test
    void requiresFingerprintMatchEvenOn200() throws Exception {
        URI base = new URI("https://example.com");
        InetAddress addr = InetAddress.getByAddress("example.com", new byte[]{1, 2, 3, 4});
        when(urlValidator.validate("https://example.com"))
                .thenReturn(new ToolUrlValidator.Resolved(base, addr));
        when(fetcher.fetch(any(), any())).thenReturn(miss());
        when(fetcher.fetch(argThat(u -> u != null && "/.git/config".equals(u.getPath())), any()))
                .thenReturn(okPlain("not a git config"));

        ExposedFilesInspector.Result r = inspector.inspect("https://example.com");

        assertThat(r.findings()).isEmpty();
    }

    @Test
    void returnsFailedWhenUrlInvalid() {
        when(urlValidator.validate("bad"))
                .thenThrow(new RuntimeException("bad url"));

        ExposedFilesInspector.Result r = inspector.inspect("bad");

        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("not scannable");
    }
}
