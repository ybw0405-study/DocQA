package com.docqa.document.storage;

import io.minio.CreateMultipartUploadResponse;
import io.minio.ListPartsResponse;
import io.minio.MinioAsyncClient;
import io.minio.messages.Part;
import java.util.List;

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

    /** 已上传分片号（1-based）。分片数受 UploadPlan.MAX_PARTS 上限约束，单页即可取全。 */
    List<Integer> listUploadedPartNumbers(String bucket, String object, String uploadId) throws Exception {
        ListPartsResponse response =
                listParts(bucket, null, object, 1000, 0, uploadId, newMultimap(), newMultimap());
        return response.result().partList().stream()
                .map(Part::partNumber)
                .toList();
    }
}
