package com.docqa.agentgateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.docqa.common.web.PublicPaths;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * 网关放行清单必须与契约对账，而不是靠人工记忆维护：
 * 契约里「存在一个不需要 bearerAuth 的 security 备选」的端点，就是网关必须放行的端点。
 */
class PublicPathsContractTest {

    private static final Set<String> HTTP_METHODS =
            Set.of("get", "put", "post", "delete", "patch", "head", "options", "trace");

    @Test
    void publicPathSetMatchesContract() throws IOException {
        Map<String, Object> contract = loadContract();
        Set<String> expected = new TreeSet<>();
        for (Map.Entry<String, Object> path : asMap(contract.get("paths")).entrySet()) {
            for (Map.Entry<String, Object> operation : asMap(path.getValue()).entrySet()) {
                if (!HTTP_METHODS.contains(operation.getKey())) {
                    continue;
                }
                if (!requiresBearer(asMap(operation.getValue()), contract)) {
                    expected.add(path.getKey());
                }
            }
        }

        assertThat(new TreeSet<>(PublicPaths.all())).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void bearerExemptEndpointsStayInsideAuthSurface() throws IOException {
        for (String path : PublicPaths.all()) {
            assertThat(path).as("Bearer 免检端点必须落在认证前缀内，下游服务其余端点一律要求令牌")
                    .startsWith("/api/v1/auth/");
            assertThat(path).as("Bearer 免检端点不能是路径模板").doesNotContain("{");
        }
    }

    @Test
    void contractStillDeclaresPaths() throws IOException {
        assertThat(asMap(loadContract().get("paths"))).isNotEmpty();
    }

    private static boolean requiresBearer(Map<String, Object> operation, Map<String, Object> contract) {
        Object raw = operation.containsKey("security") ? operation.get("security") : contract.get("security");
        if (!(raw instanceof List<?> alternatives) || alternatives.isEmpty()) {
            return false;
        }
        for (Object alternative : alternatives) {
            if (!asMap(alternative).containsKey("bearerAuth")) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, Object> loadContract() throws IOException {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve("docs/02-design/openapi-java.yaml");
            if (Files.isRegularFile(candidate)) {
                try (Reader reader = Files.newBufferedReader(candidate, StandardCharsets.UTF_8)) {
                    return asMap(new Yaml().load(reader));
                }
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("docs/02-design/openapi-java.yaml not found from "
                + Path.of("").toAbsolutePath());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
