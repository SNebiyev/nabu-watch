package watch.nabu.application.scanlog;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import watch.nabu.application.notification.NotificationService;
import watch.nabu.domain.model.ScanLog;
import watch.nabu.domain.model.User;
import watch.nabu.infrastructure.persistence.ScanLogRepository;
import watch.nabu.infrastructure.persistence.UserRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScanLogReportService {

    private final ScanLogRepository scanLogRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    /**
     * Marks a ScanLog as reported with mandatory notes.
     * Enforces that the scan belongs to the caller's organisation.
     */
    @Transactional
    public void report(UUID scanLogId, UUID orgId, User reporter, String notes) {
        ScanLog scanLog = scanLogRepository.findByIdWithDetails(scanLogId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Scan log not found: " + scanLogId));

        UUID scanOrgId = scanLog.getMonitor().getWebsite().getOrganization().getId();
        if (!scanOrgId.equals(orgId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Scan log does not belong to your organisation");
        }

        if (scanLog.isReported()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Scan log has already been reported");
        }

        scanLog.setReported(true);
        scanLog.setReportNotes(notes.strip());
        scanLog.setReportedBy(reporter);
        scanLog.setReportedAt(Instant.now());
        scanLogRepository.saveAndFlush(scanLog);
        log.info("[report] ScanLog {} reported by {} (org {})", scanLogId, reporter.getEmail(), orgId);

        String websiteUrl = scanLog.getMonitor().getWebsite().getUrl();
        List<User> admins = userRepository.findByRole(User.Role.ADMIN);
        if (!admins.isEmpty()) {
            notificationService.sendMonitorReportToAdmins(reporter.getEmail(), websiteUrl, scanLogId, admins);
        }
    }

    /**
     * Admin-only: resolves a report by clearing the reported flag and all related fields.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public void resolve(UUID scanLogId) {
        ScanLog scanLog = scanLogRepository.findById(scanLogId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Scan log not found: " + scanLogId));

        scanLog.setReported(false);
        scanLog.setReportNotes(null);
        scanLog.setReportedBy(null);
        scanLog.setReportedAt(null);
        scanLogRepository.save(scanLog);
        log.info("[report] Admin resolved report on ScanLog {}", scanLogId);
    }

    /**
     * Admin-only: paginated list of all reported scan logs, newest first.
     */
    @PreAuthorize("hasRole('ADMIN')")
    public Page<ScanLog> listReported(int page, int size) {
        int safeSize = Math.clamp(size, 1, 100);
        return scanLogRepository.findAllReported(PageRequest.of(page, safeSize));
    }
}
