package watch.nabu.application.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watch.nabu.application.tools.ToolUrlValidator;

import static org.assertj.core.api.Assertions.assertThat;
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
}
