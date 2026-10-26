package watch.nabu.application.notification;

/**
 * HTML entity escaping for email templates.
 * Escapes all five characters that have special meaning in HTML:
 * {@code &}, {@code <}, {@code >}, {@code "}, {@code '}.
 */
public final class HtmlEscape {

    private HtmlEscape() {
    }

    public static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#x27;");
    }
}
