package com.docqa.agentgateway.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 三处审计白名单必须逐字一致：任何一侧少一个键，治理信号就在那条路径上无声消失。
 *
 * <p>网关的集合按运行期反射读取，另两侧按源码解析——它们位于不同模块/语言，无法在编译期共享常量；
 * 解析范围严格限定在常量声明体内，避免把同文件其它字符串误算进来。
 */
class AuditPayloadKeySetContractTest {

    private static final Pattern QUOTED_KEY = Pattern.compile("\"([A-Za-z][A-Za-z0-9]*)\"");

    @Test
    void gatewayWhitelistMatchesPythonAndAuditService() throws Exception {
        Set<String> gateway = gatewayKeys();
        Set<String> python = keysIn(
                readRepoFile("ai-python/src/docqa_ai/governance/audit.py"),
                "ALLOWED_PAYLOAD_KEYS = frozenset(",
                ")");
        Set<String> auditService = keysIn(
                readRepoFile(
                        "backend-java/audit-service/src/main/java/com/docqa/audit/service/"
                                + "AuditPayloadSanitizer.java"),
                "ALLOWED_KEYS = Set.of(",
                ");");

        assertThat(python).as("Python 白名单与网关不一致").isEqualTo(gateway);
        assertThat(auditService).as("audit-service 白名单与网关不一致").isEqualTo(gateway);
        assertThat(gateway)
                .as("toolSource 与 targetUserId 必须留在三侧白名单里")
                .hasSize(17)
                .contains("toolSource", "targetUserId");
    }

    @SuppressWarnings("unchecked")
    private static Set<String> gatewayKeys() throws ReflectiveOperationException {
        Field field = AuditEventValidator.class.getDeclaredField("ALLOWED_KEYS");
        field.setAccessible(true);
        return new TreeSet<>((Set<String>) field.get(null));
    }

    private static Set<String> keysIn(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        if (start < 0) {
            throw new IllegalStateException("白名单声明缺少标记：" + startMarker);
        }
        int bodyStart = start + startMarker.length();
        int end = source.indexOf(endMarker, bodyStart);
        if (end < 0) {
            throw new IllegalStateException("白名单声明缺少结束标记：" + endMarker);
        }
        Set<String> keys = new TreeSet<>();
        Matcher matcher = QUOTED_KEY.matcher(source.substring(bodyStart, end));
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
        return keys;
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
