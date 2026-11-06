package com.docqa.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class JsonCharsetFilterTest {

    private final JsonCharsetFilter filter = new JsonCharsetFilter();

    @Test
    void appendsUtf8ToJsonAndProblemJsonContentTypes() throws Exception {
        assertThat(run(response -> response.setContentType("application/json")).getContentType())
                .isEqualTo("application/json;charset=UTF-8");
        assertThat(run(response -> response.setContentType("application/problem+json")).getContentType())
                .isEqualTo("application/problem+json;charset=UTF-8");
    }

    @Test
    void appendsUtf8WhenContentTypeIsWrittenAsAHeader() throws Exception {
        assertThat(run(response -> response.setHeader("Content-Type", "application/json")).getHeader("Content-Type"))
                .isEqualTo("application/json;charset=UTF-8");
        assertThat(run(response -> response.addHeader("Content-Type", "application/json")).getHeader("Content-Type"))
                .isEqualTo("application/json;charset=UTF-8");
        assertThat(run(response -> response.setHeader("content-type", "application/json")).getHeader("content-type"))
                .isEqualTo("application/json;charset=UTF-8");
    }

    @Test
    void matchesTheMediaTypeCaseInsensitively() throws Exception {
        assertThat(run(response -> response.setContentType("Application/JSON")).getContentType())
                .isEqualTo("Application/JSON;charset=UTF-8");
    }

    @Test
    void keepsAdditionalParametersWhenAppendingCharset() throws Exception {
        assertThat(run(response -> response.setContentType("application/json;profile=compact")).getContentType())
                .isEqualTo("application/json;profile=compact;charset=UTF-8");
    }

    @Test
    void neverOverridesAnExplicitCharset() throws Exception {
        String declared = "application/json;charset=ISO-8859-1";

        assertThat(run(response -> response.setContentType(declared)).getContentType()).isEqualTo(declared);
        assertThat(run(response -> response.setHeader("Content-Type", declared)).getHeader("Content-Type"))
                .isEqualTo(declared);
    }

    @Test
    void doesNotDuplicateAnExistingUtf8Charset() throws Exception {
        String declared = "application/json;charset=UTF-8";

        assertThat(run(response -> response.setContentType(declared)).getContentType()).isEqualTo(declared);
    }

    @Test
    void leavesNonJsonAndMissingContentTypesUntouched() throws Exception {
        assertThat(run(response -> response.setContentType("application/octet-stream")).getContentType())
                .isEqualTo("application/octet-stream");
        assertThat(run(response -> response.setContentType("text/event-stream")).getContentType())
                .isEqualTo("text/event-stream");
        assertThat(run(response -> response.setContentType("text/plain")).getContentType())
                .isEqualTo("text/plain");
        assertThat(run(response -> response.setStatus(HttpServletResponse.SC_NO_CONTENT)).getContentType()).isNull();
    }

    @Test
    void leavesOtherHeadersUntouched() throws Exception {
        MockHttpServletResponse response = run(res -> res.setHeader("X-Result", "application/json"));

        assertThat(response.getHeader("X-Result")).isEqualTo("application/json");
    }

    /**
     * SSE 的 Content-Type 常常由框架以「直接写头」而非 {@code setContentType} 落下，
     * 因此不重写这条纪律必须对 {@code setHeader} / {@code addHeader} 形态同样成立：
     * 追加 {@code ;charset=UTF-8} 会改变网关对外承诺的事件流媒体类型。
     */
    @Test
    void neverRewritesTheEventStreamContentTypeWrittenAsAHeader() throws Exception {
        assertThat(run(res -> res.setHeader("Content-Type", "text/event-stream")).getHeader("Content-Type"))
                .isEqualTo("text/event-stream");
        assertThat(run(res -> res.addHeader("Content-Type", "text/event-stream")).getHeader("Content-Type"))
                .isEqualTo("text/event-stream");
    }

    /**
     * 流式响应依赖「写出即上链」：包装器若自带缓冲区，帧会停在过滤器里，
     * 客户端只能在流关闭时一次性收到全部内容。这里证明包装层只改头、不换流、不缓存。
     */
    @Test
    void passesTheOutputStreamThroughWithoutBuffering() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        ServletOutputStream delegateStream = response.getOutputStream();
        AtomicReference<ServletOutputStream> streamSeenInsideChain = new AtomicReference<>();
        AtomicReference<byte[]> visibleInsideChain = new AtomicReference<>();

        filter.doFilter(
                new MockHttpServletRequest(),
                response,
                (request, res) -> {
                    HttpServletResponse wrapped = (HttpServletResponse) res;
                    wrapped.setContentType("text/event-stream");
                    ServletOutputStream stream = wrapped.getOutputStream();
                    streamSeenInsideChain.set(stream);
                    stream.write("event: meta\n\n".getBytes(StandardCharsets.UTF_8));
                    stream.flush();
                    visibleInsideChain.set(response.getContentAsByteArray());
                });

        assertThat(streamSeenInsideChain.get()).isSameAs(delegateStream);
        assertThat(response.getContentType()).isEqualTo("text/event-stream");
        assertThat(new String(visibleInsideChain.get(), StandardCharsets.UTF_8)).isEqualTo("event: meta\n\n");
    }

    /**
     * 首帧之前的 HTTP 状态行与响应头必须已经上链（SSE 一旦提交就无法回退成 JSON ProblemDetail），
     * 因此包装器也不能截留提交态：链内 flush 之后，真实响应本身就应当已提交。
     */
    @Test
    void passesTheCommitStateThrough() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean committedInsideChain = new AtomicBoolean();

        filter.doFilter(
                new MockHttpServletRequest(),
                response,
                (request, res) -> {
                    res.getOutputStream().flush();
                    committedInsideChain.set(response.isCommitted());
                });

        assertThat(committedInsideChain.get()).isTrue();
        assertThat(response.isCommitted()).isTrue();
    }

    private MockHttpServletResponse run(ResponseAction action) throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(
                new MockHttpServletRequest(),
                response,
                (request, res) -> action.apply((HttpServletResponse) res));
        return response;
    }

    @FunctionalInterface
    private interface ResponseAction {
        void apply(HttpServletResponse response);
    }
}
