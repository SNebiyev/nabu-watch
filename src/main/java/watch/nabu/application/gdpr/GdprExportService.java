package watch.nabu.application.gdpr;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watch.nabu.domain.model.*;
import watch.nabu.infrastructure.persistence.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * GDPR Article 20 — data portability export.
 *
 * <p>Builds a structured map of all personal data held for a user,
 * suitable for JSON serialisation. Secrets (password hash, TOTP secret,
 * webhook signing keys) are deliberately excluded.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GdprExportService {

    private final UserRepository userRepository;
    private final OrganizationMemberRepository orgMemberRepository;
    private final WebsiteRepository websiteRepository;
    private final MonitorRepository monitorRepository;
    private final NotificationRepository notificationRepository;
    private final InternalMessageRepository internalMessageRepository;
    private final WebhookConfigRepository webhookConfigRepository;

    /**
     * Exports all personal data held for the given user.
     *
     * @param userId the user's UUID
     * @param email  the user's email (used for message lookup)
     * @return a structured map ready for JSON serialisation
     */
    public Map<String, Object> exportUserData(UUID userId, String email) {
        Map<String, Object> export = new LinkedHashMap<>();

        // 1. User profile (no passwordHash, no totpSecret)
        User user = userRepository.findById(userId).orElseThrow();
        export.put("profile", buildProfile(user));

        // 2. Organization memberships
        List<OrganizationMember> memberships = orgMemberRepository.findAllByUserIdWithOrganization(userId);
        List<Map<String, Object>> orgData = new ArrayList<>();
        for (OrganizationMember m : memberships) {
            Map<String, Object> orgEntry = new LinkedHashMap<>();
            Organization org = m.getOrganization();
            orgEntry.put("organizationId", org.getId());
            orgEntry.put("organizationName", org.getName());
            orgEntry.put("role", m.getRole().name());
            orgEntry.put("joinedAt", m.getJoinedAt());

            // Websites for this org
            List<Website> websites = websiteRepository.findAllByOrganizationId(org.getId());
            List<UUID> websiteIds = websites.stream().map(Website::getId).toList();

            List<Monitor> monitors = websiteIds.isEmpty()
                    ? List.of()
                    : monitorRepository.findByWebsiteIdIn(websiteIds);
            Map<UUID, List<Monitor>> monitorsByWebsite = monitors.stream()
                    .collect(Collectors.groupingBy(mon -> mon.getWebsite().getId()));

            List<Map<String, Object>> websiteList = websites.stream().map(w -> {
                Map<String, Object> ws = new LinkedHashMap<>();
                ws.put("websiteId", w.getId());
                ws.put("url", w.getUrl());
                ws.put("regionCode", w.getRegionCode());
                ws.put("status", w.getStatus().name());
                ws.put("complianceGrade", w.getComplianceGrade());
                ws.put("createdAt", w.getCreatedAt());

                List<Monitor> wMonitors = monitorsByWebsite.getOrDefault(w.getId(), List.of());
                ws.put("monitorCount", wMonitors.size());
                ws.put("monitors", wMonitors.stream().map(mon -> {
                    Map<String, Object> mInfo = new LinkedHashMap<>();
                    mInfo.put("type", mon.getType().name());
                    mInfo.put("subType", mon.getSubType().name());
                    mInfo.put("active", mon.isActive());
                    return mInfo;
                }).toList());

                return ws;
            }).toList();
            orgEntry.put("websites", websiteList);

            // Webhook configs (no secret)
            List<WebhookConfig> webhooks = webhookConfigRepository.findByOrganizationId(org.getId());
            orgEntry.put("webhookConfigs", webhooks.stream().map(wh -> {
                Map<String, Object> whInfo = new LinkedHashMap<>();
                whInfo.put("name", wh.getName());
                whInfo.put("url", wh.getUrl());
                whInfo.put("type", wh.getType().name());
                whInfo.put("enabled", wh.isEnabled());
                whInfo.put("createdAt", wh.getCreatedAt());
                return whInfo;
            }).toList());

            orgData.add(orgEntry);
        }
        export.put("organizations", orgData);

        // 4. Notifications (last 1000)
        var notifPage = notificationRepository.findByUserIdOrderByCreatedAtDesc(
                userId, PageRequest.of(0, 1000));
        export.put("notifications", notifPage.getContent().stream().map(n -> {
            Map<String, Object> ni = new LinkedHashMap<>();
            ni.put("title", n.getTitle());
            ni.put("message", n.getMessage());
            ni.put("type", n.getType().name());
            ni.put("read", n.isRead());
            ni.put("createdAt", n.getCreatedAt());
            return ni;
        }).toList());

        // 5. Messages sent/received
        var sent = internalMessageRepository.findBySenderIdOrderByCreatedAtDesc(
                email, PageRequest.of(0, 1000));
        export.put("messagesSent", sent.getContent().stream().map(msg -> {
            Map<String, Object> mi = new LinkedHashMap<>();
            mi.put("receiverId", msg.getReceiverId());
            mi.put("content", msg.getContent());
            mi.put("createdAt", msg.getCreatedAt());
            return mi;
        }).toList());

        var received = internalMessageRepository.findByReceiverIdOrderByCreatedAtDesc(
                email, PageRequest.of(0, 1000));
        export.put("messagesReceived", received.getContent().stream().map(msg -> {
            Map<String, Object> mi = new LinkedHashMap<>();
            mi.put("senderId", msg.getSenderId());
            mi.put("content", msg.getContent());
            mi.put("createdAt", msg.getCreatedAt());
            return mi;
        }).toList());

        // 6. Export timestamp
        export.put("exportedAt", Instant.now());

        log.info("[gdpr] Data export generated for userId={}", userId);
        return export;
    }

    private Map<String, Object> buildProfile(User user) {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("userId", user.getId());
        profile.put("email", user.getEmail());
        profile.put("fullName", user.getFullName());
        profile.put("role", user.getRole().name());
        profile.put("status", user.getStatus().name());
        profile.put("oauthProvider", user.getOauthProvider());
        profile.put("emailAlertsEnabled", user.isEmailAlertsEnabled());
        profile.put("weeklyDigestEnabled", user.isWeeklyDigestEnabled());
        profile.put("totpEnabled", user.isTotpEnabled());
        profile.put("emailVerifiedAt", user.getEmailVerifiedAt());
        profile.put("lastLoginAt", user.getLastLoginAt());
        profile.put("createdAt", user.getCreatedAt());
        return profile;
    }
}
