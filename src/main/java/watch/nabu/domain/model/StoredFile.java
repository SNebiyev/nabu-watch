package watch.nabu.domain.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Metadata record for a file stored in external object storage (MinIO, S3, etc.).
 *
 * <p>The actual binary data lives in the storage backend; this entity tracks
 * the object key, original filename, content type, and entity association so
 * the API can serve the file and enforce authorization.
 *
 * <p>Polymorphic association via {@code entityType} + {@code entityId} allows
 * any domain entity (ScanLog, Message, Report, ...) to own files without
 * requiring a dedicated join table per entity.
 */
@Entity
@Table(name = "stored_files", indexes = {
        @Index(name = "idx_stored_files_entity", columnList = "entity_type, entity_id"),
        @Index(name = "idx_stored_files_org", columnList = "organization_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StoredFile extends BaseEntity {

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 50)
    private EntityType entityType;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @Column(name = "original_name", nullable = false, length = 500)
    private String originalName;

    @Column(name = "stored_name", nullable = false, length = 200, unique = true)
    private String storedName;

    @Column(name = "storage_path", nullable = false, length = 1000)
    private String storagePath;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Column(name = "storage_provider", nullable = false, length = 20)
    @Builder.Default
    private String storageProvider = "MINIO";

    @Column(name = "bucket_name", nullable = false, length = 100)
    private String bucketName;

    @Column(name = "temporary", nullable = false)
    @Builder.Default
    private boolean temporary = false;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    public enum EntityType {
        SCAN_LOG,
        MESSAGE,
        REPORT
    }
}
