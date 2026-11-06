package com.docqa.common.web;

import com.docqa.common.trace.TraceIdContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String TRACE_PARENT_HEADER = "traceparent";

    private static final String ZERO_SPAN_ID = "0000000000000000";
    private static final Pattern TRACE_PARENT_PATTERN = Pattern.compile(
            "^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String traceId = resolveTraceId(request);
        String previousTraceId = TraceIdContext.current();

        TraceIdContext.set(traceId);
        request.setAttribute(TraceIdContext.MDC_KEY, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            if (TraceIdContext.isValid(previousTraceId)) {
                TraceIdContext.set(previousTraceId);
            } else {
                TraceIdContext.clear();
            }
        }
    }

    public static String resolveTraceId(HttpServletRequest request) {
        String traceId = parseTraceParent(request.getHeader(TRACE_PARENT_HEADER));
        if (traceId != null) {
            return traceId;
        }

        String fallbackTraceId = request.getHeader(TRACE_ID_HEADER);
        if (TraceIdContext.isValid(fallbackTraceId)) {
            return fallbackTraceId;
        }
        return TraceIdContext.generate();
    }

    private static String parseTraceParent(String traceParent) {
        if (traceParent == null) {
            return null;
        }
        Matcher matcher = TRACE_PARENT_PATTERN.matcher(traceParent);
        if (!matcher.matches()) {
            return null;
        }

        String traceId = matcher.group(1);
        String spanId = matcher.group(2);
        if (!TraceIdContext.isValid(traceId) || ZERO_SPAN_ID.equals(spanId)) {
            return null;
        }
        return traceId;
    }
}
