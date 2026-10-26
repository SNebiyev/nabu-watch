package watch.nabu.infrastructure.storage;

/**
 * Unchecked exception for file storage operation failures.
 */
public class FileStorageException extends RuntimeException {

    public FileStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
