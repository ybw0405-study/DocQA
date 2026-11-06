package com.docqa.audit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuditPayloadSanitizerTest {

    private final AuditPayloadSanitizer sanitizer = new AuditPayloadSanitizer();

    @Test
    void keepsAllWhitelistedFieldsWhenValuesAreValid() {
        AuditPayloadSanitizer.SanitizedPayload result = sanitizer.sanitize(validPayload());

        assertThat(result.payload()).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                Map.entry("durationMs", 123L),
                Map.entry("tokenCount", 456L),
                Map.entry("toolName", "document_search"),
                Map.entry("toolSource", "builtin"),
                Map.entry("chunkCount", 6L),
                Map.entry("topScore", 0.87),
                Map.entry("round", 2L),
                Map.entry("confidence", 0.9),
                Map.entry("budgetStatus", "OK"),
                Map.entry("errorCode", "AGT-UPSTREAM-004"),
                Map.entry("maskingVersion", "pii-v1"),
                Map.entry("piiCategories", List.of("PHONE", "EMAIL")),
                Map.entry("piiCount", 2L),
                Map.entry("model", "gpt-4o-mini"),
                Map.entry("cacheHit", true),
                Map.entry("historyTruncated", false),
                Map.entry("targetUserId", "00000000-0000-0000-0000-000000000001")));
        assertThat(result.droppedFields()).isEmpty();
    }

    @Test
    void dropsUnknownFieldsWithoutExposingTheirNamesOrValues() {
        Map<String, Object> payload = new LinkedHashMap<>(validPayload());
        payload.put("question", "我的手机号是 13800000000");
        payload.put("email", "student@example.com");

        AuditPayloadSanitizer.SanitizedPayload result = sanitizer.sanitize(payload);

        assertThat(result.payload()).doesNotContainKeys("question", "email");
        assertThat(result.droppedFields()).containsExactly("unknown");
        assertThat(result.droppedFields()).noneMatch(field -> field.contains("13800000000"));
        assertThat(result.droppedFields()).noneMatch(field -> field.contains("student@example.com"));
    }

    @Test
    void dropsEveryFieldWithAnInvalidTypeOrOutOfRangeValue() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("durationMs", -1);
        payload.put("tokenCount", 1.5);
        payload.put("toolName", "shell");
        payload.put("chunkCount", "6");
        payload.put("topScore", 1.5);
        payload.put("round", 0);
        payload.put("confidence", -0.1);
        payload.put("budgetStatus", "MAYBE");
        payload.put("errorCode", "not-an-error-code");
        payload.put("maskingVersion", "v1");
        payload.put("piiCategories", List.of("PHONE", "SSN"));
        payload.put("piiCount", -2);
        payload.put("model", "bad model with spaces");
        payload.put("cacheHit", "true");
        payload.put("historyTruncated", 1);

        AuditPayloadSanitizer.SanitizedPayload result = sanitizer.sanitize(payload);

        assertThat(result.payload()).isEmpty();
        assertThat(result.droppedFields()).containsExactlyInAnyOrder(
                "durationMs",
                "tokenCount",
                "toolName",
                "chunkCount",
                "topScore",
                "round",
                "confidence",
                "budgetStatus",
                "errorCode",
                "maskingVersion",
                "piiCategories",
                "piiCount",
                "model",
                "cacheHit",
                "historyTruncated");
    }

    @Test
    void trimsAndDeduplicatesPiiCategories() {
        AuditPayloadSanitizer.SanitizedPayload result = sanitizer.sanitize(
                Map.of("piiCategories", List.of("PHONE", " PHONE ", "EMAIL")));

        assertThat(result.payload()).containsEntry("piiCategories", List.of("PHONE", "EMAIL"));
        assertThat(result.droppedFields()).isEmpty();
    }

    @Test
    void dropsPiiCategoriesWhenTheListIsTooLongOrContainsUnknownValues() {
        AuditPayloadSanitizer.SanitizedPayload tooLong = sanitizer.sanitize(
                Map.of("piiCategories", List.of("PHONE", "PHONE", "PHONE", "PHONE", "PHONE",
                        "PHONE", "PHONE", "PHONE", "PHONE", "PHONE", "PHONE")));
        AuditPayloadSanitizer.SanitizedPayload unknown = sanitizer.sanitize(
                Map.of("piiCategories", List.of("PHONE", "PASSPORT")));

        assertThat(tooLong.payload()).isEmpty();
        assertThat(tooLong.droppedFields()).containsExactly("piiCategories");
        assertThat(unknown.payload()).isEmpty();
        assertThat(unknown.droppedFields()).containsExactly("piiCategories");
    }

    @Test
    void acceptsBuiltinAndMcpToolNamesButRejectsMalformedOnes() {
        for (String name : List.of(
                "document_search",
                "web_fetch",
                "web_search",
                "academic_search",
                "wikipedia_lookup",
                "mcp__dict__lookup",
                "mcp__a_b__c9")) {
            assertThat(sanitizer.sanitize(Map.of("toolName", name)).payload())
                    .containsEntry("toolName", name);
        }
        for (String name : List.of(
                "mcp__Dict__lookup",
                "mcp__dict",
                "mcp__dict__look up",
                "mcp__" + "d".repeat(33) + "__x",
                "dict__lookup")) {
            AuditPayloadSanitizer.SanitizedPayload rejected = sanitizer.sanitize(Map.of("toolName", name));
            assertThat(rejected.payload()).isEmpty();
            assertThat(rejected.droppedFields()).containsExactly("toolName");
        }
    }

    @Test
    void keepsOnlyKnownToolSources() {
        assertThat(sanitizer.sanitize(Map.of("toolSource", "builtin")).payload())
                .containsEntry("toolSource", "builtin");
        assertThat(sanitizer.sanitize(Map.of("toolSource", "mcp")).payload())
                .containsEntry("toolSource", "mcp");

        AuditPayloadSanitizer.SanitizedPayload rejected = sanitizer.sanitize(Map.of("toolSource", "plugin"));
        assertThat(rejected.payload()).isEmpty();
        assertThat(rejected.droppedFields()).containsExactly("toolSource");
    }

    @Test
    void keepsOnlyUuidTargetUserIds() {
        assertThat(sanitizer.sanitize(Map.of("targetUserId", "00000000-0000-0000-0000-000000000001")).payload())
                .containsEntry("targetUserId", "00000000-0000-0000-0000-000000000001");

        AuditPayloadSanitizer.SanitizedPayload rejected = sanitizer.sanitize(Map.of("targetUserId", "not-a-uuid"));
        assertThat(rejected.payload()).isEmpty();
        assertThat(rejected.droppedFields()).containsExactly("targetUserId");
    }

    @Test
    void dropsPiiLikeModelIdentifiers() {
        AuditPayloadSanitizer.SanitizedPayload result = sanitizer.sanitize(
                Map.of("model", "13800000000"));

        assertThat(result.payload()).isEmpty();
        assertThat(result.droppedFields()).containsExactly("model");
    }

    @Test
    void returnsEmptyPayloadForNullInput() {
        AuditPayloadSanitizer.SanitizedPayload result = sanitizer.sanitize(null);

        assertThat(result.payload()).isEmpty();
        assertThat(result.droppedFields()).isEmpty();
    }

    private Map<String, Object> validPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("durationMs", 123);
        payload.put("tokenCount", 456);
        payload.put("toolName", "document_search");
        payload.put("toolSource", "builtin");
        payload.put("chunkCount", 6);
        payload.put("topScore", 0.87);
        payload.put("round", 2);
        payload.put("confidence", 0.9);
        payload.put("budgetStatus", "OK");
        payload.put("errorCode", "AGT-UPSTREAM-004");
        payload.put("maskingVersion", "pii-v1");
        payload.put("piiCategories", List.of("PHONE", "EMAIL"));
        payload.put("piiCount", 2);
        payload.put("model", "gpt-4o-mini");
        payload.put("cacheHit", true);
        payload.put("historyTruncated", false);
        payload.put("targetUserId", "00000000-0000-0000-0000-000000000001");
        return payload;
    }
}
