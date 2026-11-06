package com.docqa.document.storage;

import java.time.Duration;
import java.util.List;

public interface ObjectStorage {

    String createMultipartUpload(String objectKey);

    String presignPart(String objectKey, String uploadId, int partNumber, Duration expiry);

    void completeMultipartUpload(String objectKey, String uploadId, List<UploadPart> parts);

    void abortMultipartUpload(String objectKey, String uploadId);

    long size(String objectKey);

    String md5(String objectKey);

    void remove(String objectKey);
}
