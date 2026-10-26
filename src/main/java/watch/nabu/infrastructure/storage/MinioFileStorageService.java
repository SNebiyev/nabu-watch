package watch.nabu.infrastructure.storage;

import io.minio.*;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Expiration;
import io.minio.messages.LifecycleConfiguration;
import io.minio.messages.LifecycleRule;
import io.minio.messages.RuleFilter;
import io.minio.messages.Status;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import watch.nabu.application.storage.FileStoragePort;

import java.io.ByteArrayInputStream;
import java.util.List;

/**
 * MinIO-backed implementation of {@link FileStoragePort}.
 *
 * <p>Selected when {@code nabu.storage.provider=minio} (or absent — this is
 * the default).  To switch to AWS S3, add an {@code S3FileStorageService}
 * with {@code havingValue = "s3"} and set the env var accordingly.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "nabu.storage.provider", havingValue = "minio", matchIfMissing = true)
public class MinioFileStorageService implements FileStoragePort {

    private final MinioClient client;
    private final String defaultBucket;
    private boolean available;

    public MinioFileStorageService(
            @Value("${nabu.storage.endpoint}") String endpoint,
            @Value("${nabu.storage.access-key}") String accessKey,
            @Value("${nabu.storage.secret-key}") String secretKey,
            @Value("${nabu.storage.bucket}") String bucket) {
        this.defaultBucket = bucket;
        this.client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }

    @PostConstruct
    void init() {
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(defaultBucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(defaultBucket).build());
                log.info("Created MinIO bucket: {}", defaultBucket);
            }
            applyScreenshotLifecycle();
            available = true;
            log.info("MinIO file storage available at bucket '{}'", defaultBucket);
        } catch (Exception e) {
            available = false;
            log.warn("MinIO file storage unavailable — file operations will be skipped: {}", e.getMessage());
        }
    }

    /**
     * Auto-expires objects under the {@code screenshots/} prefix after 90 days.
     * Screenshots are evidence captured during compliance scans; the latest scan
     * for a monitor is what matters operationally — historical screenshots beyond
     * the scan-history window grow unbounded storage cost without value.
     * Idempotent: re-applying the same rule on each boot is safe.
     */
    private void applyScreenshotLifecycle() {
        try {
            LifecycleRule rule = new LifecycleRule(
                    Status.ENABLED,
                    null,
                    new Expiration((java.time.ZonedDateTime) null, 90, null),
                    new RuleFilter("screenshots/"),
                    "expire-screenshots-90d",
                    null,
                    null,
                    null);
            client.setBucketLifecycle(SetBucketLifecycleArgs.builder()
                    .bucket(defaultBucket)
                    .config(new LifecycleConfiguration(List.of(rule)))
                    .build());
            log.info("Applied lifecycle rule on '{}': screenshots/ expires after 90 days", defaultBucket);
        } catch (Exception e) {
            log.warn("Failed to set lifecycle policy on '{}' — old screenshots will not auto-expire: {}",
                    defaultBucket, e.getMessage());
        }
    }

    @Override
    public void upload(String bucket, String objectKey, byte[] data, String contentType) {
        try {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(new ByteArrayInputStream(data), data.length, -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception e) {
            throw new FileStorageException("Failed to upload to MinIO: " + objectKey, e);
        }
    }

    @Override
    public byte[] download(String bucket, String objectKey) {
        try (var response = client.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .build())) {
            return response.readAllBytes();
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                return new byte[0];
            }
            throw new FileStorageException("Failed to download from MinIO: " + objectKey, e);
        } catch (Exception e) {
            throw new FileStorageException("Failed to download from MinIO: " + objectKey, e);
        }
    }

    @Override
    public void delete(String bucket, String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
        } catch (Exception e) {
            log.warn("Failed to delete from MinIO: {} — {}", objectKey, e.getMessage());
        }
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public String providerName() {
        return "MINIO";
    }
}
