package watch.nabu.application.website;

/**
 * Thrown when a user attempts to add a website URL that already exists
 * within the same organization.
 */
public class WebsiteDuplicateException extends RuntimeException {

    private final String url;

    public WebsiteDuplicateException(String url) {
        super("Website already exists in this organization: " + url);
        this.url = url;
    }

    public String getUrl() {
        return url;
    }
}
