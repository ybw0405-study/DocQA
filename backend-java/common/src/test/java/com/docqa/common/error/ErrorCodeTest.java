package com.docqa.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ErrorCodeTest {

    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Z]{2,4}-[A-Z_]+-[0-9]{3}$");

    @Test
    void registersExpectedCodes() {
        assertThat(ErrorCode.values()).hasSize(40);
        assertThat(Arrays.stream(ErrorCode.values()).map(ErrorCode::code).distinct()).hasSize(40);
    }

    @Test
    void registersExpectedCodesPerDomain() {
        Map<String, Long> countsByDomain = Arrays.stream(ErrorCode.values())
                .collect(Collectors.groupingBy(
                        errorCode -> errorCode.code().substring(0, errorCode.code().indexOf('-')),
                        Collectors.counting()));
        assertThat(countsByDomain).containsExactlyInAnyOrderEntriesOf(Map.of(
                "COM", 6L,
                "USR", 9L,
                "DOC", 6L,
                "AGT", 11L,
                "REM", 8L));
    }

    @Test
    void everyCodeHasConsistentMetadata() {
        for (ErrorCode errorCode : ErrorCode.values()) {
            assertThat(errorCode.code()).matches(CODE_PATTERN);
            assertThat(errorCode.status().is4xxClientError() || errorCode.status().is5xxServerError()).isTrue();
            assertThat(errorCode.problemType().toString()).startsWith("https://docqa.example/problems/");
            assertThat(errorCode.title()).isNotBlank();
            assertThat(errorCode.defaultDetail()).isNotBlank();
            assertThat(ErrorCode.find(errorCode.code())).contains(errorCode);
        }
    }

    @Test
    void returnsEmptyForUnknownCode() {
        assertThat(ErrorCode.find("COM-UNKNOWN-999")).isEmpty();
    }

    @Test
    void mapsPiiBlockToUnprocessableEntity() {
        assertThat(ErrorCode.AGT_VALIDATION_003.status().value()).isEqualTo(422);
    }

    @Test
    void mapsFrameworkRejectionCodesToContractStatuses() {
        assertThat(ErrorCode.COM_METHOD_NOT_ALLOWED_001.status().value()).isEqualTo(405);
        assertThat(ErrorCode.COM_UNSUPPORTED_MEDIA_001.status().value()).isEqualTo(415);
    }

    @Test
    void mapsScheduleImportCodesToContractStatuses() {
        assertThat(ErrorCode.REM_VALIDATION_002.status().value()).isEqualTo(413);
        assertThat(ErrorCode.REM_VALIDATION_003.status().value()).isEqualTo(415);
        assertThat(ErrorCode.REM_CONFLICT_002.status().value()).isEqualTo(409);
    }

    @Test
    void mapsReminderStateMachineCodesToContractStatuses() {
        assertThat(ErrorCode.REM_NOT_FOUND_001.status().value()).isEqualTo(404);
        assertThat(ErrorCode.REM_CONFLICT_003.status().value()).isEqualTo(409);
        assertThat(ErrorCode.REM_CONFLICT_004.status().value()).isEqualTo(409);
    }

    @Test
    void mapsIdentityAndAuthorizationCodesToContractStatuses() {
        assertThat(ErrorCode.USR_NOT_FOUND_001.status().value()).isEqualTo(404);
        assertThat(ErrorCode.USR_CONFLICT_001.status().value()).isEqualTo(409);
        assertThat(ErrorCode.USR_FORBIDDEN_001.status().value()).isEqualTo(403);
        assertThat(ErrorCode.USR_DISABLED_001.status().value()).isEqualTo(403);
        assertThat(ErrorCode.USR_REFRESH_001.status().value()).isEqualTo(401);
        assertThat(ErrorCode.USR_VALIDATION_001.status().value()).isEqualTo(422);
        assertThat(ErrorCode.USR_IDEMPOTENCY_001.status().value()).isEqualTo(409);
    }

    @Test
    void everyProblemTypeUriIsUnique() {
        // 断言的是「一码一 type」这个不变量本身，而不是某个具体数字：
        // 写死枚举条数的快照每加一个错误码就红一次，报的数是「数量对不上」而不是「有两码共用 type」。
        assertThat(Arrays.stream(ErrorCode.values()).map(ErrorCode::problemType).distinct())
                .hasSize(ErrorCode.values().length);
    }
}
