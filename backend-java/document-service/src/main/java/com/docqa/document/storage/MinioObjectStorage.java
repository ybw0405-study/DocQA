package com.docqa.document.storage;

import com.docqa.document.config.MinioProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioAsyncClient;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import io.minio.messages.Part;
import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "docqa.minio.enabled", havingValue = "true", matchIfMissing = true)
public class MinioObjectStorage implements ObjectStorage {

    private static final int BUFFER_SIZE = 8192;

    private final MinioClient client;
    private final MultipartMinioClient multipartClient;
    private final MinioProperties properties;

    public MinioObjectStorage(MinioClient client, MinioAsyncClient asyncClient, MinioProperties properties) {
        this.client = client;
        this.multipartClient = new MultipartMinioClient(asyncClient);
        this.properties = properties;
    }

    @PostConstruct
    void ensureBucket() {
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(properties.getBucket()).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(properties.getBucket()).build());
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to initialize MinIO bucket", ex);
        }
    }

    @Override
    public String createMultipartUpload(String objectKey) {
        try {
            return multipartClient.createMultipart(properties.getBucket(), objectKey);
        } catch (ErrorResponseException ex) {
            throw new ObjectStorageException(
                    ex.errorResponse().code(),
                    "Unable to create multipart upload",
                    true,
                    ex);
        } catch (Exception ex) {
            throw new ObjectStorageException("MINIO_UNAVAILABLE", "Unable to create multipart upload", false, ex);
        }
    }

    @Override
    public String presignPart(String objectKey, String uploadId, int partNumber, Duration expiry) {
        try {
            return client.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.PUT)
                            .bucket(properties.getBucket())
                            .object(objectKey)
                            .expiry((int) expiry.toSeconds())
                            .extraQueryParams(Map.of(
                                    "uploadId", uploadId,
                                    "partNumber", Integer.toString(partNumber)))
                            .build());
        } catch (Exception ex) {
            throw new ObjectStorageException("MINIO_UNAVAILABLE", "Unable to sign upload part", false, ex);
        }
    }

    @Override
    public void completeMultipartUpload(String objectKey, String uploadId, List<UploadPart> parts) {
        try {
            Part[] minioParts = parts.stream()
                    .map(part -> new Part(part.partNumber(), part.etag()))
                    .toArray(Part[]::new);
            multipartClient.completeMultipart(properties.getBucket(), objectKey, uploadId, minioParts);
        } catch (ErrorResponseException ex) {
            throw new ObjectStorageException(
                    ex.errorResponse().code(),
                    "Unable to complete multipart upload",
                    true,
                    ex);
        } catch (Exception ex) {
            throw new ObjectStorageException("MINIO_UNAVAILABLE", "Unable to complete multipart upload", false, ex);
        }
    }

    @Override
    public void abortMultipartUpload(String objectKey, String uploadId) {
        try {
            multipartClient.abortMultipart(properties.getBucket(), objectKey, uploadId);
        } catch (Exception ex) {
            throw new ObjectStorageException("MINIO_UNAVAILABLE", "Unable to abort multipart upload", false, ex);
        }
    }

    @Override
    public long size(String objectKey) {
        try {
            StatObjectResponse stat = client.statObject(
                    StatObjectArgs.builder()
                            .bucket(properties.getBucket())
                            .object(objectKey)
                            .build());
            return stat.size();
        } catch (Exception ex) {
            throw new ObjectStorageException("MINIO_UNAVAILABLE", "Unable to stat object", false, ex);
        }
    }

    @Override
    public String md5(String objectKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            try (InputStream input = client.getObject(
                    GetObjectArgs.builder()
                            .bucket(properties.getBucket())
                            .object(objectKey)
                            .build());
                 DigestInputStream digestInput = new DigestInputStream(input, digest)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                while (digestInput.read(buffer) != -1) {
                    // Reading the stream updates the digest.
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("MD5 is not available", ex);
        } catch (Exception ex) {
            throw new ObjectStorageException("MINIO_UNAVAILABLE", "Unable to read object", false, ex);
        }
    }

    @Override
    public void remove(String objectKey) {
        try {
            client.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(properties.getBucket())
                            .object(objectKey)
                            .build());
        } catch (Exception ex) {
            throw new ObjectStorageException("MINIO_UNAVAILABLE", "Unable to remove object", false, ex);
        }
    }
}
