package watch.nabu.application.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class DnsHygieneInspectorTest {

    private final DnsHygieneInspector inspector = new DnsHygieneInspector(new DnsLookup());

    @Test
    void rejectsUrlWithoutHost() {
        DnsHygieneInspector.Result r = inspector.inspect("not-a-url");
        assertThat(r.success()).isFalse();
        assertThat(r.error()).isNotNull();
    }
}
