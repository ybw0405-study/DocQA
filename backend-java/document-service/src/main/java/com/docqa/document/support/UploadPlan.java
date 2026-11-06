package com.docqa.document.support;

public final class UploadPlan {

    public static final int MAX_PARTS = 10;

    private final int partSize;
    private final int partCount;

    private UploadPlan(int partSize, int partCount) {
        this.partSize = partSize;
        this.partCount = partCount;
    }

    public static UploadPlan of(long sizeBytes, int partSize, int maxParts) {
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be positive");
        }
        if (partSize <= 0) {
            throw new IllegalArgumentException("partSize must be positive");
        }
        long count = (sizeBytes + partSize - 1L) / partSize;
        if (count > maxParts) {
            throw new IllegalArgumentException("file requires too many parts");
        }
        return new UploadPlan(partSize, (int) count);
    }

    public int partSize() {
        return partSize;
    }

    public int partCount() {
        return partCount;
    }
}
