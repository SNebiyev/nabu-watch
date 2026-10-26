package watch.nabu.infrastructure.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class UrlUtilsTest {

    @ParameterizedTest
    @CsvSource({
            // www. prefix stripped
            "https://www.bbc.co.uk,        https://bbc.co.uk",
            "http://www.example.com,        http://example.com",
            "www.example.com,               https://example.com",

            // trailing slashes removed
            "https://example.com///,        https://example.com",

            // host lowercased
            "https://WWW.Example.COM,       https://example.com",
            "https://Example.COM/Path,      https://example.com/Path",

            // scheme prepended
            "example.com,                   https://example.com",

            // no www. → unchanged host
            "https://bbc.co.uk,             https://bbc.co.uk",

            // preserves path and query
            "https://www.example.com/a?b=1, https://example.com/a?b=1",
    })
    void normalize(String input, String expected) {
        assertEquals(expected, UrlUtils.normalize(input));
    }

    @Test
    void normalize_null() {
        assertNull(UrlUtils.normalize(null));
    }
}
