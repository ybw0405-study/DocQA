package com.docqa.document.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

/** DQ-3 后半：readiness 探针的四种行为，纯逻辑、不需要真实 MQ / MinIO。 */
class DependencyHealthIndicatorTest {

    @Test
    void disabledDependencyIsUpButMarkedAsDisabled() {
        try (DependencyHealthIndicator indicator = new DependencyHealthIndicator(
                "mq", false, () -> {
                    throw new AssertionError("disabled indicator must not probe");
                })) {
            Health health = indicator.health();
            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails()).containsEntry("status", "disabled");
        }
    }

    @Test
    void reachableDependencyIsUp() {
        try (DependencyHealthIndicator indicator = new DependencyHealthIndicator("minio", true, () -> {})) {
            assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        }
    }

    @Test
    void unreachableDependencyIsDownAndOnlyExposesErrorType() {
        try (DependencyHealthIndicator indicator = new DependencyHealthIndicator(
                "mq", true, () -> {
                    throw new IOException("nameserver 127.0.0.1:9876 refused");
                })) {
            Health health = indicator.health();
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails())
                    .containsEntry("reason", "unreachable")
                    .containsEntry("errorType", "IOException");
            // 异常 message 常含地址 / 凭据，绝不能进 health 响应（§4.3 脱敏）。
            assertThat(health.getDetails().toString()).doesNotContain("9876");
        }
    }

    @Test
    void slowDependencyTimesOutInsteadOfBlockingTheProbe() {
        try (DependencyHealthIndicator indicator = new DependencyHealthIndicator(
                "minio", true, Duration.ofMillis(40), () -> Thread.sleep(1000), null)) {
            Health health = indicator.health();
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsEntry("reason", "timeout");
        }
    }
}
