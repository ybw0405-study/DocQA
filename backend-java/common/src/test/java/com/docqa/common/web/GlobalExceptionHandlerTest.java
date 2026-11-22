package com.docqa.common.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.common.trace.TraceIdContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class GlobalExceptionHandlerTest {

    private static final String TRACE_ID_PATTERN = "^[0-9a-f]{32}$";

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new TraceIdFilter(), new JsonCharsetFilter())
                .build();
        TraceIdContext.clear();
    }

    @AfterEach
    void tearDown() {
        TraceIdContext.clear();
    }

    @Test
    void mapsBeanValidationFailureToProblemDetail() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("COM-VALIDATION-001"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.traceId").value(matchesPattern(TRACE_ID_PATTERN)))
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void mapsBusinessExceptionToRegisteredCode() throws Exception {
        mockMvc.perform(get("/test/business"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("DOC-CONFLICT-001"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value(ErrorCode.DOC_CONFLICT_001.defaultDetail()))
                .andExpect(jsonPath("$.traceId").value(matchesPattern(TRACE_ID_PATTERN)));
    }

    @Test
    void hidesUnexpectedExceptionMessage() throws Exception {
        mockMvc.perform(get("/test/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("COM-INTERNAL-001"))
                .andExpect(jsonPath("$.detail").value(not(containsString("secret-value"))))
                .andExpect(jsonPath("$.traceId").value(matchesPattern(TRACE_ID_PATTERN)));
    }

    @Test
    void mapsMalformedJsonToValidationProblem() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COM-VALIDATION-001"));
    }

    @Test
    void mapsMissingHeaderToValidationProblem() throws Exception {
        mockMvc.perform(get("/test/header"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COM-VALIDATION-001"))
                .andExpect(jsonPath("$.errors[0].field").value("X-Test"));
    }

    @Test
    void mapsMissingStaticResourceToNotFoundProblem() throws Exception {
        mockMvc.perform(get("/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("Content-Type", containsString("charset=UTF-8")))
                .andExpect(jsonPath("$.code").value("COM-NOT_FOUND-001"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Not found"))
                .andExpect(jsonPath("$.detail").value(ErrorCode.COM_NOT_FOUND_001.defaultDetail()))
                .andExpect(jsonPath("$.traceId").value(matchesPattern(TRACE_ID_PATTERN)));
    }

    @Test
    void mapsUnmappedHandlerToNotFoundProblem() throws Exception {
        mockMvc.perform(get("/test/no-handler"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("COM-NOT_FOUND-001"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void mapsUnsupportedMethodToMethodNotAllowedWithAllowHeader() throws Exception {
        mockMvc.perform(post("/test/business"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("Allow", containsString("GET")))
                .andExpect(jsonPath("$.code").value("COM-METHOD_NOT_ALLOWED-001"))
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.traceId").value(matchesPattern(TRACE_ID_PATTERN)));
    }

    @Test
    void mapsUnsupportedMediaTypeToUnsupportedMediaTypeProblem() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("name=demo"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("COM-UNSUPPORTED_MEDIA-001"))
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(jsonPath("$.traceId").value(matchesPattern(TRACE_ID_PATTERN)));
    }

    @RestController
    static class TestController {

        @PostMapping("/test/validate")
        public void validate(@Valid @RequestBody TestRequest request) {
        }

        @GetMapping("/test/business")
        public void business() {
            throw new BusinessException(ErrorCode.DOC_CONFLICT_001);
        }

        @GetMapping("/test/unexpected")
        public void unexpected() {
            throw new IllegalStateException("secret-value");
        }

        @GetMapping("/test/header")
        public void header(@RequestHeader("X-Test") String value) {
        }

        @GetMapping("/test/not-found")
        public void notFound() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "/test/not-found");
        }

        @GetMapping("/test/no-handler")
        public void noHandler() throws NoHandlerFoundException {
            throw new NoHandlerFoundException("GET", "/test/no-handler", new HttpHeaders());
        }
    }

    record TestRequest(@NotBlank String name) {
    }
}
