package com.docqa.document.service;

import java.util.Optional;
import java.util.UUID;

public interface UploadSessionStore {

    void save(UploadSession session);

    Optional<UploadSession> find(UUID documentId);

    void delete(UUID documentId);
}
