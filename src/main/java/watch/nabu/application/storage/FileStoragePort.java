package watch.nabu.application.storage;

/**
 * Port for external object storage (MinIO, AWS S3, GCS, etc.).
 *
 * <p>Application-layer services depend on this interface; the concrete
 * implementation lives in the infrastructure layer and is selected via
 * configuration ({@code nabu.storage.provider}).
 */
public interface FileStoragePort {

    /**
     * Upload binary data to the storage backend.
     *
     * @param bucket      target bucket name
     * @param objectKey   full object key (path within bucket)
     * @param data        file content
     * @param contentType MIME type (e.g. "image/jpeg")
     */
    void upload(String bucket, String objectKey, byte[] data, String contentType);

    /**
     * Download file content from the storage backend.
     *
     * @return raw bytes, or empty array if not found
     */
    byte[] download(String bucket, String objectKey);

    /**
     * Delete an object from the storage backend.
     */
    void delete(String bucket, String objectKey);

    /**
     * @return {@code true} if the storage backend is reachable and operational
     */
    boolean isAvailable();

    /**
     * @return provider identifier stored in {@code stored_files.storage_provider}
     * (e.g. "MINIO", "S3", "GCS")
     */
    String providerName();
}
