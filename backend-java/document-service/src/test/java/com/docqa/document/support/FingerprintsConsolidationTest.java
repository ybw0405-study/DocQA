package com.docqa.document.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 方案 4.2 步 4：三份 Fingerprints 必须只剩薄委托，算法实现单一来源在 common。 */
class FingerprintsConsolidationTest {

    private static final Map<String, String> MODULE_PACKAGES = Map.of(
            "document-service", "document",
            "reminder-service", "reminder",
            "user-service", "user");

    private static final String KNOWN_SHA256_ABC =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Test
    void allThreeCopiesDelegateToCommonAndDropTheirOwnDigestImplementation() throws IOException {
        // surefire 的工作目录是模块 basedir（backend-java/document-service），其父目录是 reactor 根。
        Path backend = Path.of(System.getProperty("user.dir")).getParent();
        for (Map.Entry<String, String> entry : MODULE_PACKAGES.entrySet()) {
            Path source = backend
                    .resolve(entry.getKey())
                    .resolve("src/main/java/com/docqa")
                    .resolve(entry.getValue())
                    .resolve("support/Fingerprints.java");
            String text = Files.readString(source);
            assertThat(text)
                    .as(entry.getKey() + " 必须委托 common 的 Fingerprints")
                    .contains("com.docqa.common.support.Fingerprints");
            assertThat(text)
                    .as(entry.getKey() + " 不得再自带 MessageDigest 实现")
                    .doesNotContain("MessageDigest");
        }
    }

    @Test
    void delegationPreservesBothOverloadsAndTheKnownVector() {
        assertThat(Fingerprints.sha256("abc")).isEqualTo(KNOWN_SHA256_ABC);
        assertThat(com.docqa.common.support.Fingerprints.sha256("abc".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(KNOWN_SHA256_ABC);
    }
}
