package watch.nabu.application.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watch.nabu.application.tools.ToolUrlValidator;

import java.net.InetAddress;
import java.net.URI;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenPortsInspectorTest {

    @Mock private ToolUrlValidator urlValidator;

    @InjectMocks private OpenPortsInspector inspector;

    @Test
    void returnsFailedWhenUrlInvalid() {
        when(urlValidator.validate("bad")).thenThrow(new RuntimeException("bad url"));

        OpenPortsInspector.Result r = inspector.inspect("bad");

        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("not scannable");
    }

    @Test
    void recognisesCloudflareIpv4AndIpv6() throws Exception {
        assertThat(CloudflareRanges.contains(InetAddress.getByName("104.21.5.9"))).isTrue();
        assertThat(CloudflareRanges.contains(InetAddress.getByName("172.67.1.1"))).isTrue();
        assertThat(CloudflareRanges.contains(InetAddress.getByName("2606:4700:3030::6815:1"))).isTrue();
        assertThat(CloudflareRanges.contains(InetAddress.getByName("2a06:98c0::1"))).isTrue();
    }

    @Test
    void doesNotRecogniseOtherAddresses() throws Exception {
        assertThat(CloudflareRanges.contains(InetAddress.getByName("93.184.216.34"))).isFalse();
        assertThat(CloudflareRanges.contains(InetAddress.getByName("104.32.0.1"))).isFalse();
        assertThat(CloudflareRanges.contains(InetAddress.getByName("2001:db8::1"))).isFalse();
    }

    @Test
    void ignoresEdgePortsBehindCloudflare() throws Exception {
        OpenPortsInspector.Result r = inspectWithOpenPorts("104.21.5.9", Set.of(8080));

        assertThat(r.success()).isTrue();
        assertThat(r.findings()).isEmpty();
        assertThat(r.score()).isEqualTo(100);
        assertThat(r.cdn()).isEqualTo("Cloudflare");
        assertThat(r.note()).isEqualTo(OpenPortsInspector.CDN_NOTE);
    }

    @Test
    void stillReportsNonEdgePortsBehindCloudflare() throws Exception {
        OpenPortsInspector.Result r = inspectWithOpenPorts("2606:4700::1", Set.of(8080, 22));

        assertThat(r.findings()).extracting(OpenPortsInspector.Finding::port).containsExactly(22);
        assertThat(r.score()).isEqualTo(80);
        assertThat(r.cdn()).isEqualTo("Cloudflare");
    }

    @Test
    void reportsEveryOpenPortWithoutCdn() throws Exception {
        OpenPortsInspector.Result r = inspectWithOpenPorts("93.184.216.34", Set.of(8080, 22));

        assertThat(r.findings()).extracting(OpenPortsInspector.Finding::port)
                .containsExactlyInAnyOrder(22, 8080);
        assertThat(r.score()).isEqualTo(60);
        assertThat(r.cdn()).isNull();
        assertThat(r.note()).isNull();
    }

    private OpenPortsInspector.Result inspectWithOpenPorts(String ip, Set<Integer> open) throws Exception {
        InetAddress address = InetAddress.getByName(ip);
        when(urlValidator.validate("https://example.com"))
                .thenReturn(new ToolUrlValidator.Resolved(URI.create("https://example.com"), address));
        OpenPortsInspector probe = spy(inspector);
        doAnswer(inv -> open.contains((int) inv.getArgument(1))).when(probe).isOpen(any(), anyInt());
        return probe.inspect("https://example.com");
    }
}
