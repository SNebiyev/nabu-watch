package watch.nabu.application.messaging;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watch.nabu.application.notification.HtmlEscape;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renders email templates using simple {@code {{key}}} placeholder substitution.
 *
 * <p>Templates live under {@code classpath:templates/email/{name}/{name}_{locale}.html}.
 * Variables are HTML-escaped before substitution to prevent injection.
 */
@Slf4j
@Service
public class EmailTemplateService {

    private final Map<String, Optional<String>> cache = new ConcurrentHashMap<>();

    public RenderedEmail render(String templateName, String locale, Map<String, Object> variables) {
        String html = loadTemplate(templateName, locale);
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            String value = entry.getValue() != null ? entry.getValue().toString() : "";
            html = html.replace("{{" + entry.getKey() + "}}", HtmlEscape.escape(value));
        }
        String text = html.replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim();
        return new RenderedEmail(html, text);
    }

    private String loadTemplate(String name, String locale) {
        if (locale != null && !locale.isEmpty() && !"en".equals(locale)) {
            String localized = "templates/email/" + name + "/" + name + "_" + locale + ".html";
            Optional<String> cached = cache.computeIfAbsent(localized, this::readClasspath);
            if (cached.isPresent()) return cached.get();
        }
        String fallback = "templates/email/" + name + "/" + name + "_en.html";
        return cache.computeIfAbsent(fallback, this::readClasspath)
                .orElseThrow(() -> new IllegalStateException("Email template not found: " + fallback));
    }

    private Optional<String> readClasspath(String path) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) return Optional.empty();
            return Optional.of(new String(is.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("[email-template] Failed to read template: {}", path, e);
            return Optional.empty();
        }
    }

    public record RenderedEmail(String html, String text) {
    }
}
