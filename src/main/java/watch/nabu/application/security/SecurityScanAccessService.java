package watch.nabu.application.security;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import watch.nabu.domain.model.SecurityScan;
import watch.nabu.domain.model.Website;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides which {@link SecurityScan} entries are available for a given
 * website at a given moment, based on whether the scan is shipped and on
 * domain-ownership verification state.
 *
 * <p>Used both by the controller that renders the "Security Audit"
 * feature list for the UI and by every scan endpoint as the last-line
 * authorisation gate.
 */
@Service
@RequiredArgsConstructor
public class SecurityScanAccessService {

    public record ScanStatus(
            SecurityScan scan,
            boolean enabled,
            String lockReason) { }

    public enum LockReason { VERIFIED_REQUIRED, NOT_YET_AVAILABLE }

    public List<ScanStatus> list(Website website) {
        boolean verified = website.getVerifiedAt() != null;

        List<ScanStatus> out = new ArrayList<>(SecurityScan.values().length);
        for (SecurityScan scan : SecurityScan.values()) {
            if (!scan.shipped()) {
                out.add(new ScanStatus(scan, false, LockReason.NOT_YET_AVAILABLE.name()));
                continue;
            }
            if (!verified) {
                out.add(new ScanStatus(scan, false, LockReason.VERIFIED_REQUIRED.name()));
                continue;
            }
            out.add(new ScanStatus(scan, true, null));
        }
        return out;
    }

    public boolean isAllowed(Website website, SecurityScan scan) {
        return scan.shipped() && website.getVerifiedAt() != null;
    }
}
