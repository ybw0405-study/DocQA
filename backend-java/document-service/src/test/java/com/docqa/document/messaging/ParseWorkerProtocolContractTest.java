package com.docqa.document.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * document-service ↔ Python parse_worker 的 MQ 消息是跨语言契约：Python 的
 * {@code _EventModel} 是 {@code extra="forbid"}，任一侧多字段或少字段都会让消息直接解析失败、
 * 落入 DLQ。这里把 6 个消息的 Java record 与 Python StrictModel 字段集合钉成同一份事实。
 *
 * <p>与 {@code ProtocolTenantContractTest}（问答协议 AgentAskRequest）互补：本测试覆盖的是
 * parse_worker 的消息族。两侧字段都以 camelCase 的线上名为准，忽略 Java 字段名与 Python
 * snake_case 内部名的差异。
 */
class ParseWorkerProtocolContractTest {

    private static final Path PYTHON_MODELS = Path.of(
            "ai-python/src/docqa_ai/parse_worker/models.py");

    private static final Pattern FIELD_LINE = Pattern.compile("^    ([a-z][a-z0-9_]*):", Pattern.MULTILINE);
    private static final Pattern ALIAS = Pattern.compile("alias=\"([A-Za-z][A-Za-z0-9]*)\"");

    /** Java record 类 → Python StrictModel 类名，逐一对应 parse_worker 的 6 个消息。 */
    private static final Map<Class<?>, String> MESSAGE_PAIRS = new LinkedHashMap<>();

    static {
        MESSAGE_PAIRS.put(ParseRequestedEvent.class, "ParseRequestedEvent");
        MESSAGE_PAIRS.put(ParsedChunk.class, "ParsedChunk");
        MESSAGE_PAIRS.put(ParseCompletedEvent.class, "ParseCompletedEvent");
        MESSAGE_PAIRS.put(ParseFailedEvent.class, "ParseFailedEvent");
        MESSAGE_PAIRS.put(VectorChangeRequestedEvent.class, "VectorChangeRequestedEvent");
        MESSAGE_PAIRS.put(VectorChangeCompletedEvent.class, "VectorChangeCompletedEvent");
    }

    /** 各消息的线上字段名集合，逐字锁定；新增/删除字段必须同步改双端与本表。 */
    private static final Map<String, Set<String>> EXPECTED = new LinkedHashMap<>();

    static {
        EXPECTED.put("ParseRequestedEvent", Set.of(
                "eventId", "traceId", "documentId", "userId", "indexVersion", "objectKey",
                "fileName", "contentType", "sizeBytes", "md5", "retryCount", "occurredAt"));
        EXPECTED.put("ParsedChunk", Set.of(
                "chunkIndex", "vectorId", "contentHash", "tokenCount"));
        EXPECTED.put("ParseCompletedEvent", Set.of(
                "eventId", "traceId", "documentId", "indexVersion", "status", "chunks",
                "vectorRows", "occurredAt"));
        EXPECTED.put("ParseFailedEvent", Set.of(
                "eventId", "traceId", "documentId", "indexVersion", "errorCode",
                "errorMessage", "retryable", "occurredAt"));
        EXPECTED.put("VectorChangeRequestedEvent", Set.of(
                "eventId", "traceId", "documentId", "userId", "operation",
                "indexVersion", "occurredAt"));
        EXPECTED.put("VectorChangeCompletedEvent", Set.of(
                "eventId", "requestEventId", "traceId", "documentId", "operation",
                "indexVersion", "status", "remainingRows", "errorCode", "errorMessage",
                "occurredAt"));
    }

    @Test
    void javaAndPythonAgreeOnEveryMessageFieldSet() throws IOException {
        assertThat(MESSAGE_PAIRS).hasSize(6);
        for (Map.Entry<Class<?>, String> pair : MESSAGE_PAIRS.entrySet()) {
            String name = pair.getValue();
            Set<String> javaFields = new TreeSet<>();
            for (RecordComponent component : pair.getKey().getRecordComponents()) {
                javaFields.add(component.getName());
            }
            Set<String> pythonFields = pythonFieldsOf(name);

            assertThat(javaFields).as("%s Java 侧字段漂移", name).isEqualTo(EXPECTED.get(name));
            assertThat(pythonFields).as("%s Python 侧字段漂移", name).isEqualTo(EXPECTED.get(name));
        }
    }

    /**
     * parse_worker 消息刻意不带 tenantId：租户边界由 documentId 全局唯一 + 消费侧反查归属租户
     * 保证（§5.12），消息体不携带租户字段。若有人为「加租户维度」往这里塞 tenantId，
     * 会因双端 extra="forbid" 变成破坏性发布，本用例先把它钉住。
     */
    @Test
    void parseWorkerMessagesStayTenantAgnostic() throws IOException {
        for (Map.Entry<Class<?>, String> pair : MESSAGE_PAIRS.entrySet()) {
            assertThat(EXPECTED.get(pair.getValue()))
                    .as("%s 不应携带 tenantId（靠 documentId 反查租户，§5.12）", pair.getValue())
                    .doesNotContain("tenantId");
            assertThat(pythonFieldsOf(pair.getValue()))
                    .as("%s Python 侧不应出现 tenantId", pair.getValue())
                    .doesNotContain("tenantId");
        }
    }

    private static Set<String> pythonFieldsOf(String className) throws IOException {
        String source = readRepoFile();
        int start = source.indexOf("class " + className + "(");
        if (start < 0) {
            throw new IllegalStateException("parse_worker 模型缺少类：" + className);
        }
        int nextClass = source.indexOf("\nclass ", start + 1);
        int classEnd = nextClass < 0 ? source.length() : nextClass;
        String body = source.substring(start, classEnd);

        Set<String> fields = new TreeSet<>();
        Matcher lines = FIELD_LINE.matcher(body);
        while (lines.find()) {
            String line = lines.group();
            String segment = body.substring(lines.start(), nextLineEnd(body, lines.end()));
            Matcher alias = ALIAS.matcher(segment);
            fields.add(alias.find() ? alias.group(1) : line.trim().replace(":", ""));
        }
        return fields;
    }

    private static int nextLineEnd(String source, int from) {
        int newline = source.indexOf('\n', from);
        return newline < 0 ? source.length() : newline;
    }

    private static String readRepoFile() throws IOException {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve(PYTHON_MODELS);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException(PYTHON_MODELS + " not found from " + Path.of("").toAbsolutePath());
    }
}
