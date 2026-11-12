package me.roviczzz.marp.storage;

/** Unchecked wrapper used across CompletableFuture chains for storage failures. */
public final class StorageException extends RuntimeException {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
