package com.docqa.agentgateway.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.docqa.agentgateway.dto.PythonAskRequest;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Java → Python 问答请求是跨语言契约：Python 的 StrictModel 是 {@code extra="forbid"}，
 * 任一侧多字段或少字段都会让请求直接 422。这里把双端字段集合与 tenantId 的必填语义
 * 钉成同一份事实，避免 §5.2 第 5 条落地后再次漂移。
 */
class ProtocolTenantContractTest {

    private static final Pattern FIELD_LINE = Pattern.compile("^    ([a-z][a-z0-9_]*):", Pattern.MULTILINE);
    private static final Pattern ALIAS = Pattern.compile("alias=\"([A-Za-z][A-Za-z0-9]*)\"");
    private static final Set<String> EXPECTED = Set.of(
            "protocolVersion",
            "tenantId",
            "userId",
            "traceId",
            "conversationId",
            "question",
            "piiMaskedQuestion",
            "piiMaskingVersion",
            "tokenBudget",
            "maxToolCalls",
            "maxRounds",
            "allowedTools",
            "deadlineMs",
            "documentScopes",
            "history",
            "resumeApproved");

    @Test
    void javaAndPythonAgreeOnTheFieldSet() throws Exception {
        Set<String> javaFields = new TreeSet<>();
        for (RecordComponent component : PythonAskRequest.class.getRecordComponents()) {
            javaFields.add(component.getName());
        }
        Set<String> pythonFields = pythonAskRequestFields();

        assertThat(javaFields).as("Java 侧协议字段漂移").isEqualTo(EXPECTED);
        assertThat(pythonFields).as("Python 侧协议字段漂移").isEqualTo(EXPECTED);
    }

    @Test
    void tenantIdIsMandatoryOnBothSides() throws Exception {
        assertThat(PythonAskRequest.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .contains("tenantId");

        String body = bodyBetween(
                readRepoFile("ai-python/src/docqa_ai/protocol/models.py"),
                "class AgentAskRequest(StrictModel):",
                "\n    @field_validator");
        Matcher field = Pattern.compile("^    tenant_id:.*Field\\(alias=\"tenantId\"\\)", Pattern.MULTILINE)
                .matcher(body);
        assertThat(field.find()).as("Python 侧 tenant_id 必须声明为必填字段").isTrue();
        assertThat(field.group()).as("Python 侧 tenant_id 不得带默认值").doesNotContain("default");
    }

    private static Set<String> pythonAskRequestFields() throws IOException {
        String body = bodyBetween(
                readRepoFile("ai-python/src/docqa_ai/protocol/models.py"),
                "class AgentAskRequest(StrictModel):",
                "\n    @field_validator");
        Set<String> fields = new TreeSet<>();
        Matcher lines = FIELD_LINE.matcher(body);
        while (lines.find()) {
            String line = lines.group();
            Matcher alias = ALIAS.matcher(body.substring(lines.start(), nextLineEnd(body, lines.end())));
            fields.add(alias.find() ? alias.group(1) : lines.group(1));
        }
        return fields;
    }

    private static int nextLineEnd(String source, int from) {
        int newline = source.indexOf('\n', from);
        return newline < 0 ? source.length() : newline;
    }

    private static String bodyBetween(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        if (start < 0) {
            throw new IllegalStateException("协议模型缺少标记：" + startMarker);
        }
        int bodyStart = start + startMarker.length();
        int end = source.indexOf(endMarker, bodyStart);
        if (end < 0) {
            throw new IllegalStateException("协议模型缺少结束标记：" + endMarker);
        }
        return source.substring(bodyStart, end);
    }

    private static String readRepoFile(String relativePath) throws IOException {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException(relativePath + " not found from " + Path.of("").toAbsolutePath());
    }
}
