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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JsLibraryCveInspectorTest {

    @Mock private ToolUrlValidator urlValidator;
    @Mock private HtmlFetcher fetcher;

    @InjectMocks private JsLibraryCveInspector inspector;

    @Test
    void flagsVulnerableJqueryVersion() throws Exception {
        URI uri = new URI("https://example.com");
        InetAddress addr = InetAddress.getByAddress("example.com", new byte[]{1, 2, 3, 4});
        when(urlValidator.validate("https://example.com"))
                .thenReturn(new ToolUrlValidator.Resolved(uri, addr));
        String html = "<html><head>"
                + "<script src=\"/assets/jquery-1.8.3.min.js\"></script>"
                + "</head></html>";
        when(fetcher.fetch(any(), any()))
                .thenReturn(new HtmlFetcher.Result(true, 200,
                        Map.of("content-type", "text/html"), html, null));

        JsLibraryCveInspector.Result r = inspector.inspect("https://example.com");

        assertThat(r.success()).isTrue();
        assertThat(r.findings()).isNotEmpty();
        assertThat(r.findings()).anySatisfy(f -> {
            assertThat(f.library()).isEqualTo("jQuery");
            assertThat(f.version()).isEqualTo("1.8.3");
        });
    }

    @Test
    void cleanPageScoresFullMarks() throws Exception {
        URI uri = new URI("https://example.com");
        InetAddress addr = InetAddress.getByAddress("example.com", new byte[]{1, 2, 3, 4});
        when(urlValidator.validate("https://example.com"))
                .thenReturn(new ToolUrlValidator.Resolved(uri, addr));
        when(fetcher.fetch(any(), any()))
                .thenReturn(new HtmlFetcher.Result(true, 200,
                        Map.of("content-type", "text/html"),
                        "<html><script src=\"/app.js\"></script></html>", null));

        JsLibraryCveInspector.Result r = inspector.inspect("https://example.com");

        assertThat(r.success()).isTrue();
        assertThat(r.findings()).isEmpty();
        assertThat(r.score()).isEqualTo(100);
    }

    @Test
    void scriptExtractorSkipsInlineAndBadlyQuotedTags() {
        String html = "<script>inline()</script>"
                + "<script src='/a.js'></script>"
                + "<script src=\"/b.js\"></script>"
                + "<SCRIPT src=/c.js></SCRIPT>";
        Set<String> urls = JsLibraryCveInspector.extractScriptUrls(html);
        assertThat(urls).containsExactly("/a.js", "/b.js");
    }

    @Test
    void versionRangePredicateRespectsComparator() {
        assertThat(JsLibraryCveInspector.VersionRange.test("<3.0.0", "1.8.3")).isTrue();
        assertThat(JsLibraryCveInspector.VersionRange.test("<3.0.0", "3.0.0")).isFalse();
        assertThat(JsLibraryCveInspector.VersionRange.test("<=3.0.0", "3.0.0")).isTrue();
        assertThat(JsLibraryCveInspector.VersionRange.test("<1.9.0", "1.10.0")).isFalse();
    }
}
