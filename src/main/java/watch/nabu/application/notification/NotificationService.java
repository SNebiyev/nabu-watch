package watch.nabu.application.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watch.nabu.application.scan.ScanSummaryData;
import watch.nabu.domain.model.Monitor;
import watch.nabu.domain.model.Notification;
import watch.nabu.domain.model.Notification.NotificationType;
import watch.nabu.domain.model.OrganizationMember;
import watch.nabu.domain.model.ScanLog.Severity;
import watch.nabu.domain.model.ScanStatusChange;
import watch.nabu.domain.model.User;
import watch.nabu.infrastructure.persistence.NotificationRepository;
import watch.nabu.infrastructure.persistence.OrganizationMemberRepository;
import watch.nabu.infrastructure.persistence.OrganizationRepository;
import watch.nabu.infrastructure.persistence.UserRepository;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Persists scan-event notifications and delivers them in real time via
 * STOMP WebSocket.
 *
 * <h3>Delivery model</h3>
 * <p>Each notification is:
 * <ol>
 *   <li>Saved to the {@code notifications} table (for the bell-history panel).</li>
 *   <li>Pushed to {@code /user/{email}/queue/notifications} via
 *       {@link SimpMessagingTemplate} so connected browsers receive it
 *       immediately without polling.</li>
 * </ol>
 *
 * <p>If the user has no active WebSocket session the STOMP delivery silently
 * drops — the persisted row ensures nothing is lost.
 *
 * <h3>Severity → type mapping</h3>
 * <ul>
 *   <li>CRITICAL / HIGH → {@link NotificationType#CERTIFICATE_EXPIRY} (SSL)
 *       or {@link NotificationType#SCAN_FAILED} (failed scan)</li>
 *   <li>MEDIUM           → {@link NotificationType#COMPLIANCE_ISSUE}</li>
 *   <li>INFO             → {@link NotificationType#SCAN_COMPLETED}</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final String USER_NOTIFICATION_DESTINATION = "/queue/notifications";

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final OrganizationMemberRepository organizationMemberRepository;
    private final OrganizationRepository organizationRepository;
    private final SimpMessagingTemplate messagingTemplate;

    private static NotificationType resolveType(Severity severity, Monitor.SubType subType) {
        if (subType == Monitor.SubType.SSL) {
            return switch (severity) {
                case CRITICAL, HIGH -> NotificationType.CERTIFICATE_EXPIRY;
                default -> NotificationType.SCAN_COMPLETED;
            };
        }
        return switch (severity) {
            case CRITICAL, HIGH, MEDIUM -> NotificationType.COMPLIANCE_ISSUE;
            default -> NotificationType.SCAN_COMPLETED;
        };
    }

    /**
     * Formats a single monitor result as a badge segment for the line2 notification
     * payload. Omits the statusChange suffix for UNCHANGED/FIRST_SCAN so the UI
     * does not render an arrow on uneventful scans.
     */
    private static String formatBadgeSegment(Monitor.SubType subType, Severity severity,
                                             ScanStatusChange change) {
        String base = subType.shortLabel() + ":" + severity.name();
        if (change == null || change == ScanStatusChange.UNCHANGED || change == ScanStatusChange.FIRST_SCAN) {
            return base;
        }
        return base + ":" + change.name();
    }

    // ── Query methods (used by REST controller) ───────────────────────────────

    private static String resolveTitle(NotificationType type, Severity severity) {
        return switch (type) {
            case CERTIFICATE_EXPIRY -> severity == Severity.CRITICAL
                    ? "SSL Certificate Expiring Imminently"
                    : "SSL Certificate Expiring Soon";
            case COMPLIANCE_ISSUE -> severity == Severity.HIGH
                    ? "Critical Compliance Issue Detected"
                    : "Compliance Issue Detected";
            default -> "Scan Completed";
        };
    }

    private static String resolveMessage(NotificationType type, String websiteUrl, Severity severity,
                                         Monitor.SubType subType) {
        String badge = subType != null ? subType.shortLabel() + ":" + severity.name() : "UNKNOWN";
        return switch (type) {
            case CERTIFICATE_EXPIRY -> severity == Severity.CRITICAL
                    ? websiteUrl + "\n" + badge + "\nSSL certificate expires in < 7 days — renew immediately."
                    : websiteUrl + "\n" + badge + "\nSSL certificate expires in < 14 days — renew soon.";
            case COMPLIANCE_ISSUE ->
                    websiteUrl + "\n" + badge + "\nOne or more compliance keywords are missing or broken.";
            default -> websiteUrl + "\n" + badge + "\nScan completed successfully. No issues found.";
        };
    }

    /**
     * Persists and delivers a scan-completed notification for the given user.
     *
     * <p>Called by {@code ScanResultProcessor} after each successfully processed
     * scan.  The {@code severity} determines the notification type and message
     * wording.
     *
     * @param userId     owner of the scan
     * @param websiteId  website that was scanned (stored as {@code relatedEntityId})
     * @param websiteUrl human-readable website URL used in the message body
     * @param severity   scan result severity; drives type and title selection
     */
    @Transactional
    public void sendScanCompletedNotification(
            UUID userId, UUID websiteId, String websiteUrl, Severity severity,
            Monitor.SubType subType) {
        sendScanCompletedNotification(userId, websiteId, websiteUrl, severity, subType, null);
    }

    /**
     * Generic site-lifecycle notification. Used by abuse-report admin actions
     * and DNS verification expiry to inform the website owner of a state
     * change on a site they registered.
     */
    @Transactional
    public void sendSiteLifecycleNotification(
            UUID userId, UUID websiteId, String websiteUrl, UUID organizationId,
            NotificationType type, String title, String message) {
        if (userId == null) return;
        persistAndDeliver(userId, type, title, message, websiteId,
                organizationId, resolveOrgName(organizationId), websiteUrl);
    }

    /**
     * Overload that accepts an explicit organizationId for badge display.
     */
    @Transactional
    public void sendScanCompletedNotification(
            UUID userId, UUID websiteId, String websiteUrl, Severity severity,
            Monitor.SubType subType, UUID organizationId) {

        NotificationType type = resolveType(severity, subType);
        String title = resolveTitle(type, severity);
        String message = resolveMessage(type, websiteUrl, severity, subType);
        String orgName = resolveOrgName(organizationId);

        persistAndDeliver(userId, type, title, message, websiteId,
                organizationId, orgName, websiteUrl);
    }

    /**
     * Sends a scan-completed notification to every member of the organization.
     * Used for scheduled (non-manual) scans.
     */
    @Transactional
    public void sendScanCompletedToOrg(
            UUID orgId, UUID websiteId, String websiteUrl, Severity severity,
            Monitor.SubType subType) {

        NotificationType type = resolveType(severity, subType);
        String title = resolveTitle(type, severity);
        String message = resolveMessage(type, websiteUrl, severity, subType);

        sendToOrgMembers(orgId, type, title, message, websiteId, websiteUrl, m -> true);
    }

    /**
     * Returns a paginated slice of all notifications for the user, newest-first.
     * The caller controls page index and page size via {@link Pageable}.
     * The returned {@link Page} carries the {@code last} flag the frontend uses
     * to decide whether to render the "Load more" button.
     */
    @Transactional(readOnly = true)
    public Page<Notification> getNotificationPage(UUID userId, Pageable pageable) {
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    /**
     * Returns up to {@code limit} unread notifications for the user,
     * ordered newest-first.
     *
     * @deprecated Prefer {@link #getNotificationPage} for paginated access.
     * Retained for the {@code /unread} legacy endpoint.
     */
    @Deprecated
    @Transactional(readOnly = true)
    public List<Notification> getUnreadNotifications(UUID userId, int limit) {
        return notificationRepository
                .findByUserIdOrderByCreatedAtDesc(userId, org.springframework.data.domain.PageRequest.of(0, limit))
                .getContent()
                .stream()
                .filter(n -> !n.isRead())
                .toList();
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * Returns the count of unread notifications for the user.
     */
    @Transactional(readOnly = true)
    public long getUnreadCount(UUID userId) {
        return notificationRepository.countByUserIdAndReadFalse(userId);
    }

    /**
     * Marks a single notification as read.
     * Silently ignores IDs that do not belong to this user.
     */
    @Transactional
    public void markAsRead(UUID notificationId, UUID userId) {
        notificationRepository.findById(notificationId).ifPresent(n -> {
            if (userId.equals(n.getUserId()) && !n.isRead()) {
                n.setRead(true);
                notificationRepository.save(n);
                log.debug("[notify] marked read: id={} userId={}", notificationId, userId);
            }
        });
    }

    /**
     * Marks all unread notifications for the user as read.
     */
    @Transactional
    public void markAllAsRead(UUID userId) {
        int updated = notificationRepository.markAllReadByUserId(userId);
        log.debug("[notify] marked all read: userId={} count={}", userId, updated);
    }

    // ── org-aware helpers ───────────────────────────────────────────────────────

    /**
     * Resolves the organization name for notification badge display.
     * Returns null when the org is not found (e.g. deleted).
     */
    private String resolveOrgName(UUID orgId) {
        if (orgId == null) return null;
        return organizationRepository.findById(orgId)
                .map(org -> org.getName())
                .orElse(null);
    }

    /**
     * Persists a notification and delivers it via STOMP to the given user.
     */
    private void persistAndDeliver(UUID userId, NotificationType type, String title,
                                   String message, UUID relatedEntityId,
                                   UUID organizationId, String organizationName,
                                   String websiteUrl) {
        Notification notification = Notification.builder()
                .userId(userId)
                .title(title)
                .message(message)
                .type(type)
                .relatedEntityId(relatedEntityId)
                .organizationId(organizationId)
                .organizationName(organizationName)
                .build();
        notificationRepository.saveAndFlush(notification);

        userRepository.findById(userId).ifPresentOrElse(
                user -> {
                    NotificationPayload payload = new NotificationPayload(
                            notification.getId(), title, message,
                            type.name(), relatedEntityId, websiteUrl,
                            notification.getCreatedAt().toString(),
                            organizationName
                    );
                    messagingTemplate.convertAndSendToUser(
                            user.getEmail(), USER_NOTIFICATION_DESTINATION, payload);
                    log.debug("[notify] delivered: userId={} type={}", userId, type);
                },
                () -> log.warn("[notify] user not found for userId={} — persisted but not delivered", userId)
        );
    }

    /**
     * Sends a notification to all (or filtered) members of an organization.
     *
     * @param orgId           organization whose members will receive the notification
     * @param type            notification type
     * @param title           notification title
     * @param message         notification body
     * @param relatedEntityId related entity (e.g. websiteId)
     * @param websiteUrl      optional website URL for the WS payload
     * @param filter          predicate to select which members receive the notification
     */
    private void sendToOrgMembers(UUID orgId, NotificationType type, String title,
                                  String message, UUID relatedEntityId,
                                  String websiteUrl, Predicate<OrganizationMember> filter) {
        String orgName = resolveOrgName(orgId);
        List<OrganizationMember> members = organizationMemberRepository
                .findActiveByOrganizationIdWithUser(orgId);

        members.stream()
                .filter(filter)
                .forEach(member -> persistAndDeliver(
                        member.getUser().getId(), type, title, message,
                        relatedEntityId, orgId, orgName, websiteUrl));
    }

    /**
     * Persists a {@link NotificationType#SECURITY_ALERT} notification for every
     * admin user and delivers it in real time via STOMP WebSocket.
     *
     * <p>Called by {@code InternalMessageService} after
     * {@code MessageSecurityValidator} rejects a malicious payload.  The
     * {@code admins} list is supplied by the caller (already loaded from DB) so
     * this method stays free of additional repository calls.
     *
     * @param senderEmail e-mail of the user who attempted the attack
     * @param attemptTime timestamp of the blocked attempt
     * @param admins      all admin users to be notified
     */
    @Transactional
    public void sendSecurityAlertToAdmins(
            String senderEmail,
            java.time.Instant attemptTime,
            java.util.List<watch.nabu.domain.model.User> admins) {

        String title = "Security Alert: Malicious Payload Blocked";
        String message = "User " + senderEmail
                + " attempted to send a malicious payload at "
                + attemptTime + ".";

        for (User admin : admins) {
            Notification notification = Notification.builder()
                    .userId(admin.getId())
                    .title(title)
                    .message(message)
                    .type(NotificationType.SECURITY_ALERT)
                    .build();

            notificationRepository.saveAndFlush(notification);

            NotificationPayload payload = new NotificationPayload(
                    notification.getId(),
                    title,
                    message,
                    NotificationType.SECURITY_ALERT.name(),
                    null,
                    null,
                    notification.getCreatedAt().toString(),
                    null
            );
            messagingTemplate.convertAndSendToUser(
                    admin.getEmail(),
                    USER_NOTIFICATION_DESTINATION,
                    payload
            );
            log.warn("[security-alert] delivered to admin: adminId={} email={} sender={}",
                    admin.getId(), admin.getEmail(), senderEmail);
        }
    }

    /**
     * Notifies all admin users that a user has reported a monitor result.
     * Called by {@code ScanLogReportService} immediately after saving the report.
     *
     * @param reporterEmail email of the user who submitted the report
     * @param websiteUrl    URL of the website whose scan was reported
     * @param scanLogId     ID of the reported scan log (used as relatedEntityId)
     * @param admins        all admin users to notify
     */
    @Transactional
    public void sendMonitorReportToAdmins(
            String reporterEmail,
            String websiteUrl,
            UUID scanLogId,
            List<User> admins) {

        String title = "New Monitor Report";
        String message = reporterEmail + " reported a scan result for " + websiteUrl
                + ". Review it in the admin reports panel.";

        for (User admin : admins) {
            persistAndDeliver(
                    admin.getId(),
                    NotificationType.MONITOR_REPORT,
                    title, message,
                    scanLogId,
                    null, null,
                    websiteUrl
            );
        }
        log.info("[report-notify] notified {} admins about scan report scanLogId={}", admins.size(), scanLogId);
    }

    /**
     * Persists a {@link NotificationType#NEW_MESSAGE} notification for the
     * recipient and delivers it in real time via STOMP WebSocket.
     *
     * <p>Called by {@code InternalMessageService} after a safe message has been
     * saved to the database.
     *
     * @param receiverId  e-mail of the message recipient (STOMP principal name)
     * @param senderEmail e-mail of the message sender (shown in the notification body)
     * @param messageId   UUID of the persisted {@code InternalMessage}
     */
    @Transactional
    public void sendNewMessageNotification(
            String receiverId,
            String senderEmail,
            UUID messageId) {

        // Mask admin identity: regular users see "Support Team", not the admin's email.
        boolean senderIsAdmin = userRepository.findByEmail(senderEmail)
                .map(u -> u.getRole() == watch.nabu.domain.model.User.Role.ADMIN)
                .orElse(false);
        String displayName = senderIsAdmin ? "Support Team" : senderEmail;

        userRepository.findByEmail(receiverId).ifPresentOrElse(
                receiver -> {
                    String title = "New message from " + displayName;
                    String message = "You have a new message from " + displayName + ".";

                    Notification notification = Notification.builder()
                            .userId(receiver.getId())
                            .title(title)
                            .message(message)
                            .type(NotificationType.NEW_MESSAGE)
                            .relatedEntityId(messageId)
                            .build();

                    notificationRepository.saveAndFlush(notification);

                    NotificationPayload payload = new NotificationPayload(
                            notification.getId(),
                            title,
                            message,
                            NotificationType.NEW_MESSAGE.name(),
                            messageId,
                            null,
                            notification.getCreatedAt().toString(),
                            null
                    );
                    messagingTemplate.convertAndSendToUser(
                            receiverId,
                            USER_NOTIFICATION_DESTINATION,
                            payload
                    );
                    log.debug("[notify] new-message delivered: receiver={} sender={} messageId={}",
                            receiverId, senderEmail, messageId);
                },
                () -> log.warn("[notify] receiver not found for email={} — new-message notification dropped",
                        receiverId)
        );
    }


    private static String extractDomain(String rawUrl) {
        try {
            java.net.URI uri = new java.net.URI(rawUrl);
            String host = uri.getHost();
            if (host == null) return rawUrl;
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (Exception e) {
            return rawUrl;
        }
    }

    /**
     * Persists and delivers a {@link NotificationType#SCAN_SUMMARY} notification
     * once all monitors in a manual-rescan batch have reported their results.
     *
     * <p>Replaces the individual per-monitor scan notifications so the user sees
     * one concise summary instead of N separate bell entries after a single
     * "Re-scan" click.
     *
     * @param summary aggregated batch results produced by {@code ScanBatchAggregatorPort}
     */
    @Transactional
    public void sendScanSummaryNotification(ScanSummaryData summary) {
        UUID userId = summary.userId();
        UUID websiteId = summary.websiteId();
        String url = summary.websiteUrl();

        Map<Monitor.SubType, Severity> results = summary.results();
        long total = results.size();
        long issues = results.values().stream()
                .filter(s -> s != Severity.INFO)
                .count();

        boolean isManual = "MANUAL".equalsIgnoreCase(summary.triggerType());
        String domain = extractDomain(url);

        final String title = (isManual ? "[Manual] " : "") + "Scan Complete · " + domain;

        // Three-line structured message parsed by the frontend notification renderer:
        //   Line 1 — full website URL (monospace chip)
        //   Line 2 — comma-separated "ABBR:SEVERITY" pairs for every monitor result
        //            e.g. "SSL:INFO,PRIV:HIGH,IMPR:CRITICAL"
        //            Frontend renders each as a colored badge (green=INFO, amber=MEDIUM, red=CRITICAL/HIGH)
        //   Line 3 — plain summary: "N of M checks passed" or "All N checks passed"
        Map<Monitor.SubType, ScanStatusChange> changes = summary.statusChanges();
        final String line2 = results.entrySet().stream()
                .map(e -> formatBadgeSegment(e.getKey(), e.getValue(),
                        changes != null ? changes.get(e.getKey()) : null))
                .collect(Collectors.joining(","));
        final String line3;
        if (summary.aborted()) {
            line3 = "⚠ Scan blocked by firewall — site not accessible.";
        } else if (issues == 0) {
            line3 = "All " + total + " checks passed.";
        } else {
            line3 = (total - issues) + " of " + total + " checks passed.";
        }

        final String message = url + "\n" + line2 + "\n" + line3;

        if (isManual) {
            // Manual rescan: notify only the user who triggered the scan.
            // Resolve org from the user's current organization for badge display.
            UUID orgId = userRepository.findById(userId)
                    .map(u -> u.getCurrentOrganizationId())
                    .orElse(null);
            String orgName = resolveOrgName(orgId);

            persistAndDeliver(userId, NotificationType.SCAN_SUMMARY, title, message,
                    websiteId, orgId, orgName, url);
            log.info("[notify] scan-summary(manual): userId={} issues={}/{} websiteUrl={}",
                    userId, issues, total, url);
        } else {
            // Scheduled scan: userId is actually the organizationId — send to all members.
            UUID orgId = userId;
            sendToOrgMembers(orgId, NotificationType.SCAN_SUMMARY, title, message,
                    websiteId, url, m -> true);
            log.info("[notify] scan-summary(scheduled): orgId={} issues={}/{} websiteUrl={}",
                    orgId, issues, total, url);
        }
    }

    /**
     * Immutable DTO sent over the WebSocket connection.
     * Declared as a record so Jackson serialises it without extra config.
     *
     * <p>{@code createdAt} is serialised as an ISO-8601 string so the frontend
     * can compute an accurate relative timestamp ("2 hours ago") without
     * relying on the moment the WS frame is received.
     */
    public record NotificationPayload(
            UUID id,
            String title,
            String message,
            String type,
            UUID relatedEntityId,
            String websiteUrl,
            String createdAt,
            String organizationName
    ) {
    }
}
