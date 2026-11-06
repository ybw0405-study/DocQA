package com.docqa.common.api;

import com.docqa.common.trace.TraceIdContext;

public final class ApiResponse<T> {

    public static final String SUCCESS_CODE = "OK";
    public static final String SUCCESS_MESSAGE = "success";

    private final String code;
    private final String msg;
    private final T data;
    private final String traceId;

    private ApiResponse(T data, String traceId) {
        this.code = SUCCESS_CODE;
        this.msg = SUCCESS_MESSAGE;
        this.data = data;
        this.traceId = traceId;
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(data, TraceIdContext.currentOrCreate());
    }

    public String getCode() {
        return code;
    }

    public String getMsg() {
        return msg;
    }

    public T getData() {
        return data;
    }

    public String getTraceId() {
        return traceId;
    }
}
