package com.docqa.document.support;

import com.docqa.common.trace.TraceIdContext;

public final class TraceScope {

    private TraceScope() {
    }

    public static void run(String traceId, Runnable action) {
        String previous = TraceIdContext.current();
        try {
            if (TraceIdContext.isValid(traceId)) {
                TraceIdContext.set(traceId);
            }
            action.run();
        } finally {
            if (TraceIdContext.isValid(previous)) {
                TraceIdContext.set(previous);
            } else {
                TraceIdContext.clear();
            }
        }
    }
}
