package com.docqa.common.web;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.common.trace.TraceIdContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

class ErrorCodeContractTest {

    private static final String TRACE_ID_PATTERN = "^[0-9a-f]{32}$";

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ErrorController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new TraceIdFilter())
                .build();
        TraceIdContext.clear();
    }

    @AfterEach
    void tearDown() {
        TraceIdContext.clear();
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void everyRegisteredCodeKeepsHttpAndProblemDetailContract(ErrorCode errorCode) throws Exception {
        mockMvc.perform(get("/test/error/{code}", errorCode.code()))
                .andExpect(status().is(errorCode.status().value()))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(errorCode.code()))
                .andExpect(jsonPath("$.status").value(errorCode.status().value()))
                .andExpect(jsonPath("$.title").value(errorCode.title()))
                .andExpect(jsonPath("$.traceId").value(matchesPattern(TRACE_ID_PATTERN)));
    }

    @RestController
    static class ErrorController {

        @GetMapping("/test/error/{code}")
        public void error(@PathVariable String code) {
            ErrorCode errorCode = ErrorCode.find(code)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown error code"));
            throw new BusinessException(errorCode);
        }
    }
}
