package com.docqa.document.domain;

public enum IdempotencyOperation {
    UPLOAD_INIT,
    UPLOAD_COMPLETE,
    DOCUMENT_RENAME,
    DOCUMENT_TAG_UPDATE,
    DOCUMENT_DELETE,
    DOCUMENT_RETRY,
    DOCUMENT_REINDEX
}
