package watch.nabu.application.storage;

import watch.nabu.domain.model.StoredFile;

import java.time.Instant;
import java.util.UUID;

/**
 * Command object for storing a file with metadata.
 */
public record StoreFileCommand(
        UUID orgId,
        StoredFile.EntityType entityType,
        UUID entityId,
        String originalName,
        String folder,
        byte[] data,
        String contentType,
        boolean temporary,
        Instant expiresAt
) {
    public StoreFileCommand(UUID orgId, StoredFile.EntityType entityType, UUID entityId,
                            String originalName, String folder, byte[] data, String contentType) {
        this(orgId, entityType, entityId, originalName, folder, data, contentType, false, null);
    }
}
