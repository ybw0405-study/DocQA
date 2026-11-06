package com.docqa.document.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class UploadPlanTest {

    private static final int PART_SIZE = 5 * 1024 * 1024;

    @Test
    void singlePartForSmallFile() {
        UploadPlan plan = UploadPlan.of(1024, PART_SIZE, UploadPlan.MAX_PARTS);

        assertThat(plan.partSize()).isEqualTo(PART_SIZE);
        assertThat(plan.partCount()).isEqualTo(1);
    }

    @Test
    void roundsUpPartCount() {
        assertThat(UploadPlan.of(PART_SIZE + 1L, PART_SIZE, UploadPlan.MAX_PARTS).partCount()).isEqualTo(2);
        assertThat(UploadPlan.of(PART_SIZE * 10L, PART_SIZE, UploadPlan.MAX_PARTS).partCount()).isEqualTo(10);
    }

    @Test
    void rejectsTooManyParts() {
        assertThatThrownBy(() -> UploadPlan.of(PART_SIZE * 10L + 1, PART_SIZE, UploadPlan.MAX_PARTS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too many parts");
    }

    @Test
    void rejectsNonPositiveValues() {
        assertThatThrownBy(() -> UploadPlan.of(0, PART_SIZE, UploadPlan.MAX_PARTS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UploadPlan.of(1, 0, UploadPlan.MAX_PARTS))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
