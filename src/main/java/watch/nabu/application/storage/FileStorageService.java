package watch.nabu.application.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watch.nabu.domain.model.StoredFile;
import watch.nabu.infrastructure.persistence.StoredFileRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Application service for file storage operations.
 *
 * <p>Coordinates between the external storage backend ({@link FileStoragePort})
 * and the database metadata table ({@code stored_files}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class FileStorageService {

    private final FileStoragePort storagePort;
    private final StoredFileRepository storedFileRepository;

    @Value("${nabu.storage.bucket}")
    private String defaultBucket;

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot) : "";
    }

    /**
     * Upload a file to object storage and persist its metadata.
     *
     * @return the saved {@link StoredFile} entity with generated ID
     */
    public StoredFile store(StoreFileCommand cmd) {
        String storedName = UUID.randomUUID() + extensionOf(cmd.originalName());
        String storagePath = cmd.folder() + "/" + storedName;

        storagePort.upload(defaultBucket, storagePath, cmd.data(), cmd.contentType());

        StoredFile file = StoredFile.builder()
                .organizationId(cmd.orgId())
                .entityType(cmd.entityType())
                .entityId(cmd.entityId())
                .originalName(cmd.originalName())
                .storedName(storedName)
                .storagePath(storagePath)
                .contentType(cmd.contentType())
                .fileSize(cmd.data().length)
                .storageProvider(storagePort.providerName())
                .bucketName(defaultBucket)
                .temporary(cmd.temporary())
                .expiresAt(cmd.expiresAt())
                .build();

        StoredFile saved = storedFileRepository.save(file);
        log.info("Stored file: {} -> {} ({} bytes)", cmd.originalName(), storagePath, cmd.data().length);
        return saved;
    }

    /**
     * Download file content by its metadata ID.
     */
    @Transactional(readOnly = true)
    public Optional<byte[]> download(UUID fileId) {
        return storedFileRepository.findById(fileId)
                .map(f -> {
                    byte[] data = storagePort.download(f.getBucketName(), f.getStoragePath());
                    return data.length > 0 ? data : null;
                });
    }

    /**
     * Find all files associated with a given entity.
     */
    @Transactional(readOnly = true)
    public List<StoredFile> findByEntity(StoredFile.EntityType entityType, UUID entityId) {
        return storedFileRepository.findByEntityTypeAndEntityId(entityType, entityId);
    }

    /**
     * Find a specific file by ID.
     */
    @Transactional(readOnly = true)
    public Optional<StoredFile> findById(UUID fileId) {
        return storedFileRepository.findById(fileId);
    }

    /**
     * Register metadata for a file that was already uploaded externally (e.g. by
     * the Go worker).  No upload is performed — only the DB record is created.
     *
     * @param orgId        owning organization
     * @param entityType   what kind of entity this file belongs to
     * @param entityId     the owning entity's UUID
     * @param originalName human-readable filename (e.g. "cookie_banner_screenshot.jpg")
     * @param storagePath  the object key already used in the storage backend
     * @param contentType  MIME type
     * @return the persisted {@link StoredFile}
     */
    public StoredFile registerExternal(UUID orgId, StoredFile.EntityType entityType, UUID entityId,
                                       String originalName, String storagePath, String contentType) {
        String storedName = storagePath.contains("/")
                ? storagePath.substring(storagePath.lastIndexOf('/') + 1)
                : storagePath;

        StoredFile file = StoredFile.builder()
                .organizationId(orgId)
                .entityType(entityType)
                .entityId(entityId)
                .originalName(originalName)
                .storedName(storedName)
                .storagePath(storagePath)
                .contentType(contentType)
                .fileSize(0) // actual size unknown — Go worker didn't report it
                .storageProvider(storagePort.providerName())
                .bucketName(defaultBucket)
                .build();

        StoredFile saved = storedFileRepository.save(file);
        log.info("Registered external file: {} -> {} (entity={}:{})",
                originalName, storagePath, entityType, entityId);
        return saved;
    }

    /**
     * Delete a file from both storage and database.
     */
    public void delete(UUID fileId) {
        storedFileRepository.findById(fileId).ifPresent(f -> {
            storagePort.delete(f.getBucketName(), f.getStoragePath());
            storedFileRepository.delete(f);
            log.info("Deleted file: {} ({})", f.getStoragePath(), f.getOriginalName());
        });
    }
}
