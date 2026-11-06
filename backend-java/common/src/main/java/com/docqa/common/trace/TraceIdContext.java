package com.docqa.common.trace;

import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;

public final class TraceIdContext {

    public static final String MDC_KEY = "traceId";

    private static final Pattern TRACE_ID_PATTERN = Pattern.compile("^[0-9a-f]{32}$");
    private static final String ZERO_TRACE_ID = "00000000000000000000000000000000";

    private TraceIdContext() {
    }

    public static String current() {
        return MDC.get(MDC_KEY);
    }

    public static String currentOrCreate() {
        String traceId = current();
        return isValid(traceId) ? traceId : generate();
    }

    public static String generate() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static boolean isValid(String traceId) {
        return traceId != null
                && TRACE_ID_PATTERN.matcher(traceId).matches()
                && !ZERO_TRACE_ID.equals(traceId);
    }

    public static void set(String traceId) {
        if (!isValid(traceId)) {
            throw new IllegalArgumentException("traceId must be 32 lowercase hexadecimal characters");
        }
        MDC.put(MDC_KEY, traceId);
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
    }
}
