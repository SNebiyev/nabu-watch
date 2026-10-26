package watch.nabu.application.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watch.nabu.application.tools.ToolUrlValidator;

import java.net.InetAddress;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TlsGradeInspectorTest {

    @Mock private ToolUrlValidator urlValidator;

    @InjectMocks private TlsGradeInspector inspector;

    @Test
    void returnsFailedWhenUrlInvalid() {
        when(urlValidator.validate("bad")).thenThrow(new RuntimeException("bad url"));

        TlsGradeInspector.Result r = inspector.inspect("bad");

        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("not scannable");
    }

    @Test
    void rejectsPlainHttp() throws Exception {
        URI base = new URI("http://example.com");
        InetAddress addr = InetAddress.getByAddress("example.com", new byte[]{1, 2, 3, 4});
        when(urlValidator.validate("http://example.com"))
                .thenReturn(new ToolUrlValidator.Resolved(base, addr));

        TlsGradeInspector.Result r = inspector.inspect("http://example.com");

        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("HTTPS");
    }
}
