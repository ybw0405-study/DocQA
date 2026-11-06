package com.docqa.document.storage;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "docqa.minio.enabled", havingValue = "false")
public class DisabledObjectStorage implements ObjectStorage {

    @Override
    public String createMultipartUpload(String objectKey) {
        throw unavailable();
    }

    @Override
    public String presignPart(String objectKey, String uploadId, int partNumber, Duration expiry) {
        throw unavailable();
    }

    @Override
    public void completeMultipartUpload(String objectKey, String uploadId, List<UploadPart> parts) {
        throw unavailable();
    }

    @Override
    public void abortMultipartUpload(String objectKey, String uploadId) {
        throw unavailable();
    }

    @Override
    public long size(String objectKey) {
        throw unavailable();
    }

    @Override
    public String md5(String objectKey) {
        throw unavailable();
    }

    @Override
    public void remove(String objectKey) {
        throw unavailable();
    }

    private ObjectStorageException unavailable() {
        return new ObjectStorageException("MINIO_DISABLED", "Object storage is disabled", false, null);
    }
}
