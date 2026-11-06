package com.docqa.document.domain;

public enum VectorConsistency {
    MATCHED,
    DIVERGED,
    UNKNOWN;

    public static VectorConsistency of(long chunkCount, Long vectorRows) {
        if (vectorRows == null) {
            return UNKNOWN;
        }
        return vectorRows == chunkCount ? MATCHED : DIVERGED;
    }
}
