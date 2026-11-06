package com.docqa.document.storage;

import io.minio.CreateMultipartUploadResponse;
import io.minio.MinioAsyncClient;
import io.minio.messages.Part;

final class MultipartMinioClient extends MinioAsyncClient {

    MultipartMinioClient(MinioAsyncClient client) {
        super(client);
    }

    String createMultipart(String bucket, String object) throws Exception {
        CreateMultipartUploadResponse response =
                createMultipartUpload(bucket, null, object, newMultimap(), newMultimap());
        return response.result().uploadId();
    }

    void completeMultipart(String bucket, String object, String uploadId, Part[] parts) throws Exception {
        completeMultipartUpload(bucket, null, object, uploadId, parts, newMultimap(), newMultimap());
    }

    void abortMultipart(String bucket, String object, String uploadId) throws Exception {
        abortMultipartUpload(bucket, null, object, uploadId, newMultimap(), newMultimap());
    }
}
