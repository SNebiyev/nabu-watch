package watch.nabu.application.website;

/**
 * Thrown when a website owner attempts to self-delete a website that has an
 * unresolved abuse report (PENDING or UPHELD). The hard-delete cascade would
 * wipe the abuse_reports row and let the owner evade triage, so the operation
 * is rejected — admins can still remove the site through the OTP-step-up path.
 */
public class WebsiteHasOpenAbuseReportException extends RuntimeException {

    public WebsiteHasOpenAbuseReportException() {
        super("Website cannot be deleted while an abuse report is open.");
    }
}
