package watch.nabu.application.messaging;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watch.nabu.application.exception.MaliciousPayloadException;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Zero-Trust content validator for internal messages.
 *
 * <h3>Policy</h3>
 * <p>Any content that matches a known XSS vector or SQL-injection pattern is
 * <em>rejected outright</em> — it is never sanitised and never reaches the
 * persistence layer.  A {@link MaliciousPayloadException} is thrown so the
 * service layer can fire an admin security alert and return HTTP 400 to the
 * sender.
 *
 * <h3>Detected attack classes</h3>
 * <ul>
 *   <li>XSS — {@code <script>}, {@code javascript:}, inline event handlers
 *       ({@code onerror=}, {@code onload=}, …), {@code <iframe>}, {@code eval()},
 *       {@code <object>}, {@code <embed>}, {@code data:} URI scheme</li>
 *   <li>SQLi — {@code ' OR / AND} tautologies, {@code UNION SELECT}, DDL
 *       statements ({@code DROP}, {@code ALTER}, …), comment sequences
 *       ({@code --}, {@code /*})</li>
 * </ul>
 *
 * <p>Patterns are compiled once at class-load time (static final) to avoid
 * repeated compilation overhead on every invocation.
 */
@Slf4j
@Component
public class MessageSecurityValidator {

    // ── XSS patterns ──────────────────────────────────────────────────────────

    private static final List<Pattern> XSS_PATTERNS = List.of(
            // <script …> or </script>
            Pattern.compile("<\\s*script[\\s>]", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<\\s*/\\s*script\\s*>", Pattern.CASE_INSENSITIVE),
            // javascript: URI
            Pattern.compile("javascript\\s*:", Pattern.CASE_INSENSITIVE),
            // Inline DOM event handlers: onerror=, onload=, onclick=, etc.
            Pattern.compile("on\\w+\\s*=", Pattern.CASE_INSENSITIVE),
            // <iframe …>
            Pattern.compile("<\\s*iframe[\\s>]", Pattern.CASE_INSENSITIVE),
            // eval(
            Pattern.compile("eval\\s*\\(", Pattern.CASE_INSENSITIVE),
            // <object and <embed — common plugin-based XSS vectors
            Pattern.compile("<\\s*object[\\s>]", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<\\s*embed[\\s>]", Pattern.CASE_INSENSITIVE),
            // data: URI (often used for base64-encoded payloads)
            Pattern.compile("data\\s*:", Pattern.CASE_INSENSITIVE),
            // document.cookie / document.write
            Pattern.compile("document\\s*\\.\\s*(cookie|write|location)", Pattern.CASE_INSENSITIVE),
            // window.location redirect
            Pattern.compile("window\\s*\\.\\s*location", Pattern.CASE_INSENSITIVE)
    );

    // ── SQLi patterns ─────────────────────────────────────────────────────────

    private static final List<Pattern> SQLI_PATTERNS = List.of(
            // Classic tautology: ' OR 1=1 / ' AND 1=1
            Pattern.compile("'\\s*(OR|AND)\\s+\\S", Pattern.CASE_INSENSITIVE),
            // UNION-based injection
            Pattern.compile("UNION\\s+(ALL\\s+)?SELECT", Pattern.CASE_INSENSITIVE),
            // DDL statements that could truncate/destroy data
            Pattern.compile(";\\s*(DROP|ALTER|DELETE|INSERT|UPDATE|TRUNCATE|CREATE|EXEC)\\s+",
                    Pattern.CASE_INSENSITIVE),
            // SQL line comment (--)
            Pattern.compile("--\\s"),
            // SQL block comment start
            Pattern.compile("/\\*"),
            // Hex / char encoding bypass: 0x41, CHAR(65)
            Pattern.compile("0x[0-9a-fA-F]+"),
            Pattern.compile("\\bCHAR\\s*\\(", Pattern.CASE_INSENSITIVE)
    );

    /**
     * Caps the logged snippet to 80 chars to avoid polluting log lines.
     */
    private static String truncate(String s) {
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }

    /**
     * Validates {@code content} against all XSS and SQLi pattern lists.
     *
     * @param content the raw message body supplied by the sender
     * @throws MaliciousPayloadException if any pattern matches — payload is
     *                                   rejected, never sanitised
     */
    public void validate(String content) {
        if (content == null || content.isBlank()) {
            return; // Bean validation handles empty content separately
        }

        for (Pattern pattern : XSS_PATTERNS) {
            if (pattern.matcher(content).find()) {
                log.warn("[security] XSS pattern detected: pattern='{}' snippet='{}'",
                        pattern.pattern(), truncate(content));
                throw new MaliciousPayloadException("Security violation detected");
            }
        }

        for (Pattern pattern : SQLI_PATTERNS) {
            if (pattern.matcher(content).find()) {
                log.warn("[security] SQLi pattern detected: pattern='{}' snippet='{}'",
                        pattern.pattern(), truncate(content));
                throw new MaliciousPayloadException("Security violation detected");
            }
        }
    }
}
