package com.docqa.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.docqa.common.api.FieldViolation;
import com.docqa.common.trace.TraceIdContext;
import java.net.URI;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;

class ProblemDetailFactoryTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    @AfterEach
    void clearTraceId() {
        TraceIdContext.clear();
    }

    @Test
    void createsRfc9457ProblemDetail() {
        TraceIdContext.set(TRACE_ID);

        ProblemDetail problem = ProblemDetailFactory.create(
                ErrorCode.DOC_CONFLICT_001,
                "文档当前状态不允许执行该操作。",
                List.of(FieldViolation.of("documentId", "state conflict")),
                "/api/v1/documents/123/complete");

        assertThat(problem.getType()).isEqualTo(URI.create("https://docqa.example/problems/document-conflict"));
        assertThat(problem.getTitle()).isEqualTo("Document state conflict");
        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getDetail()).isEqualTo("文档当前状态不允许执行该操作。");
        assertThat(problem.getInstance()).isEqualTo(URI.create("/api/v1/documents/123/complete"));
        assertThat(problem.getProperties())
                .containsEntry("code", "DOC-CONFLICT-001")
                .containsEntry("traceId", TRACE_ID);
        assertThat(problem.getProperties().get("errors")).isEqualTo(
                List.of(FieldViolation.of("documentId", "state conflict")));
    }

    @Test
    void limitsErrorsAndTruncatesSensitiveLengths() {
        List<FieldViolation> errors = IntStream.range(0, 25)
                .mapToObj(index -> FieldViolation.of("field-" + index + "-" + "x".repeat(250), "y".repeat(350)))
                .toList();

        ProblemDetail problem = ProblemDetailFactory.create(
                ErrorCode.COM_VALIDATION_001,
                "z".repeat(1200),
                errors,
                "bad path with spaces");

        assertThat(problem.getDetail()).hasSize(1000);
        assertThat(problem.getInstance()).isEqualTo(URI.create("/"));

        @SuppressWarnings("unchecked")
        List<FieldViolation> safeErrors = (List<FieldViolation>) problem.getProperties().get("errors");
        assertThat(safeErrors).hasSize(20);
        assertThat(safeErrors.get(0).field()).hasSize(200);
        assertThat(safeErrors.get(0).message()).hasSize(300);
    }

    @Test
    void usesDefaultDetailWhenDetailIsBlank() {
        ProblemDetail problem = ProblemDetailFactory.create(
                ErrorCode.COM_INTERNAL_001,
                " ",
                List.of(),
                null);

        assertThat(problem.getDetail()).isEqualTo(ErrorCode.COM_INTERNAL_001.defaultDetail());
        assertThat(problem.getProperties()).doesNotContainKey("errors");
    }
}
