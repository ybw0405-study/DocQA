package com.docqa.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 给 JSON 响应统一补上 {@code charset=UTF-8}。
 *
 * <p>Spring 6 有意不为 JSON 写 charset，而契约与前端都按 UTF-8 约定；本过滤器在响应写回前
 * 只改写两类 Content-Type，二进制、SSE 与已声明 charset 的响应保持原样。
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class JsonCharsetFilter extends OncePerRequestFilter {

    static final String UTF8_SUFFIX = ";charset=UTF-8";

    private static final String CHARSET_PARAMETER = "charset=";
    private static final List<String> JSON_TYPES =
            List.of("application/json", "application/problem+json");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        filterChain.doFilter(request, new JsonCharsetResponse(response));
    }

    static String withUtf8Charset(String contentType) {
        if (contentType == null) {
            return null;
        }
        String lowerCase = contentType.toLowerCase(Locale.ROOT);
        if (lowerCase.contains(CHARSET_PARAMETER)) {
            return contentType;
        }
        int semicolon = lowerCase.indexOf(';');
        String mediaType = (semicolon < 0 ? lowerCase : lowerCase.substring(0, semicolon)).trim();
        if (!JSON_TYPES.contains(mediaType)) {
            return contentType;
        }
        return contentType + UTF8_SUFFIX;
    }

    static class JsonCharsetResponse extends HttpServletResponseWrapper {

        JsonCharsetResponse(HttpServletResponse response) {
            super(response);
        }

        @Override
        public void setContentType(String type) {
            super.setContentType(withUtf8Charset(type));
        }

        @Override
        public void setHeader(String name, String value) {
            super.setHeader(name, withUtf8CharsetIfContentType(name, value));
        }

        @Override
        public void addHeader(String name, String value) {
            super.addHeader(name, withUtf8CharsetIfContentType(name, value));
        }

        private static String withUtf8CharsetIfContentType(String name, String value) {
            return "Content-Type".equalsIgnoreCase(name) ? withUtf8Charset(value) : value;
        }
    }
}
