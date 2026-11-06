package com.docqa.audit.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class AuditPayloadSanitizer {

    static final Set<String> ALLOWED_KEYS = Set.of(
            "durationMs",
            "tokenCount",
            "toolName",
            "toolSource",
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
            "historyTruncated",
            "targetUserId");

    // D-127：MCP 名不再被静默丢弃，但仍受结构约束；来源是闭集，避免治理字段变成自由文本。
    private static final Pattern TOOL_NAME = Pattern.compile(
            "^(document_search|calculator|current_time|web_fetch|web_search|academic_search|wikipedia_lookup"
                    + "|mcp__[a-z0-9_]{1,32}__[a-z0-9_]{1,32})$");
    private static final Set<String> TOOL_SOURCES = Set.of("builtin", "mcp");
    private static final Set<String> BUDGET_STATUSES = Set.of("OK", "BUDGET_EXHAUSTED", "LOW_CONFIDENCE");
    private static final Set<String> PII_CATEGORIES = Set.of("PHONE", "ID_CARD", "EMAIL");
    private static final Pattern ERROR_CODE = Pattern.compile("^[A-Z]{2,4}-[A-Z_]+-[0-9]{3}$");
    private static final Pattern TARGET_USER_ID =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern MASKING_VERSION = Pattern.compile("^pii-v[0-9]{1,6}$");
    private static final Pattern MODEL = Pattern.compile("^(?=.*[A-Za-z])[A-Za-z0-9._:/-]{1,64}$");
    private static final int MAX_PII_CATEGORIES = 10;
    private static final String UNKNOWN_FIELD = "unknown";

    public SanitizedPayload sanitize(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return new SanitizedPayload(Map.of(), List.of());
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        Set<String> dropped = new LinkedHashSet<>();
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            String field = entry.getKey();
            if (!ALLOWED_KEYS.contains(field)) {
                dropped.add(UNKNOWN_FIELD);
                continue;
            }
            Optional<Object> value = sanitizeField(field, entry.getValue());
            if (value.isEmpty()) {
                dropped.add(field);
            } else {
                sanitized.put(field, value.get());
            }
        }
        return new SanitizedPayload(
                Collections.unmodifiableMap(sanitized),
                List.copyOf(dropped));
    }

    private Optional<Object> sanitizeField(String field, Object value) {
        return switch (field) {
            case "durationMs", "tokenCount", "chunkCount", "piiCount" -> nonNegativeInteger(value);
            case "round" -> positiveInteger(value);
            case "topScore", "confidence" -> ratio(value);
            case "toolName" -> matchingString(value, TOOL_NAME);
            case "toolSource" -> enumeratedString(value, TOOL_SOURCES);
            case "targetUserId" -> matchingString(value, TARGET_USER_ID);
            case "budgetStatus" -> enumeratedString(value, BUDGET_STATUSES);
            case "errorCode" -> matchingString(value, ERROR_CODE);
            case "maskingVersion" -> matchingString(value, MASKING_VERSION);
            case "model" -> matchingString(value, MODEL);
            case "piiCategories" -> categories(value);
            case "cacheHit", "historyTruncated" -> booleanValue(value);
            default -> Optional.empty();
        };
    }

    private Optional<Object> nonNegativeInteger(Object value) {
        Long number = integral(value);
        return number != null && number >= 0 ? Optional.of(number) : Optional.empty();
    }

    private Optional<Object> positiveInteger(Object value) {
        Long number = integral(value);
        return number != null && number >= 1 ? Optional.of(number) : Optional.empty();
    }

    private Optional<Object> ratio(Object value) {
        if (value instanceof Number number) {
            double parsed = number.doubleValue();
            if (Double.isFinite(parsed) && parsed >= 0.0 && parsed <= 1.0) {
                return Optional.of(parsed);
            }
        }
        return Optional.empty();
    }

    private Optional<Object> enumeratedString(Object value, Set<String> allowed) {
        if (value instanceof String text) {
            String normalized = text.trim();
            if (allowed.contains(normalized)) {
                return Optional.of(normalized);
            }
        }
        return Optional.empty();
    }

    private Optional<Object> matchingString(Object value, Pattern pattern) {
        if (value instanceof String text && pattern.matcher(text.trim()).matches()) {
            return Optional.of(text.trim());
        }
        return Optional.empty();
    }

    private Optional<Object> categories(Object value) {
        if (!(value instanceof List<?> list) || list.size() > MAX_PII_CATEGORIES) {
            return Optional.empty();
        }
        List<String> categories = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof String text)) {
                return Optional.empty();
            }
            String normalized = text.trim();
            if (!PII_CATEGORIES.contains(normalized)) {
                return Optional.empty();
            }
            if (!categories.contains(normalized)) {
                categories.add(normalized);
            }
        }
        return Optional.of(List.copyOf(categories));
    }

    private Optional<Object> booleanValue(Object value) {
        return value instanceof Boolean ? Optional.of(value) : Optional.empty();
    }

    private Long integral(Object value) {
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        return null;
    }

    public record SanitizedPayload(Map<String, Object> payload, List<String> droppedFields) {
    }
}
