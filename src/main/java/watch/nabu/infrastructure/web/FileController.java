package watch.nabu.infrastructure.web;

import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import watch.nabu.application.security.SecurityContextService;
import watch.nabu.application.storage.FileStorageService;
import watch.nabu.domain.model.StoredFile;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Serves files stored in external object storage (MinIO / S3).
 *
 * <p>Authorization is enforced by matching the authenticated user's
 * organization against the file's {@code organization_id}.
 */
@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
public class FileController {

    private final FileStorageService fileStorageService;
    private final SecurityContextService securityContext;

    @GetMapping("/{fileId}")
    public ResponseEntity<byte[]> getFile(
            @PathVariable UUID fileId,
            @AuthenticationPrincipal UserDetails principal) {

        UUID orgId = securityContext.resolveOrgId(principal.getUsername());

        StoredFile file = fileStorageService.findById(fileId)
                .orElse(null);

        if (file == null || !file.getOrganizationId().equals(orgId)) {
            return ResponseEntity.notFound().build();
        }

        byte[] data = fileStorageService.download(fileId)
                .orElse(null);

        if (data == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.getContentType()))
                .cacheControl(CacheControl.maxAge(30, TimeUnit.DAYS).cachePublic())
                .header("Content-Disposition",
                        "inline; filename=\"" + file.getOriginalName().replaceAll("[^a-zA-Z0-9._-]", "_") + "\"")
                .body(data);
    }
}
