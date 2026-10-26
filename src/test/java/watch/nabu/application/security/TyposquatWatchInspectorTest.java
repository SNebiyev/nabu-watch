package watch.nabu.application.security;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TyposquatWatchInspectorTest {

    private final TyposquatWatchInspector inspector = new TyposquatWatchInspector(new DnsLookup());

    @Test
    void rejectsUrlWithoutTld() {
        TyposquatWatchInspector.Result r = inspector.inspect("http://localhost");
        assertThat(r.success()).isFalse();
    }

    @Test
    void rejectsUrlWithNoHost() {
        TyposquatWatchInspector.Result r = inspector.inspect("not-a-url");
        assertThat(r.success()).isFalse();
    }

    @Test
    void permutationGeneratorCoversEveryFamily() {
        Set<String> perms = TyposquatWatchInspector.generatePermutations("nabu", "watch");
        // TLD swap
        assertThat(perms).contains("nabu.com");
        // Character omission
        assertThat(perms).contains("abu.watch");
        // Adjacent swap
        assertThat(perms).contains("anbu.watch");
        // Character repetition
        assertThat(perms).contains("nnabu.watch");
        // Hyphenation
        assertThat(perms).contains("n-abu.watch");
    }
}
