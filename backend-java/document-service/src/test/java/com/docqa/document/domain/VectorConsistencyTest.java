package com.docqa.document.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VectorConsistencyTest {

    @Test
    void consistencyStaysUnknownUntilTheVectorSideReportsARowCount() {
        assertThat(VectorConsistency.of(42, null)).isEqualTo(VectorConsistency.UNKNOWN);
        assertThat(VectorConsistency.of(0, null)).isEqualTo(VectorConsistency.UNKNOWN);
    }

    @Test
    void matchedRequiresAnExactlyEqualRowCount() {
        assertThat(VectorConsistency.of(42, 42L)).isEqualTo(VectorConsistency.MATCHED);
        assertThat(VectorConsistency.of(0, 0L)).isEqualTo(VectorConsistency.MATCHED);
    }

    @Test
    void anyDriftInEitherDirectionIsDiverged() {
        assertThat(VectorConsistency.of(42, 41L)).isEqualTo(VectorConsistency.DIVERGED);
        assertThat(VectorConsistency.of(42, 43L)).isEqualTo(VectorConsistency.DIVERGED);
    }
}
