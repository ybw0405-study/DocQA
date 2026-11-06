package com.docqa.document.storage;

public class ObjectStorageException extends RuntimeException {

    private final String code;
    private final boolean clientError;

    public ObjectStorageException(String code, String message, boolean clientError, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.clientError = clientError;
    }

    public String getCode() {
        return code;
    }

    public boolean isClientError() {
        return clientError;
    }
}
