package com.docqa.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.user.domain.IdempotencyOperation;
import com.docqa.user.dto.LoginResponse;
import com.docqa.user.support.InMemoryIdempotencyStore;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IdempotencyGuardTest {

    private static final String SCOPE = "scope-1";
    private static final String KEY = "idem-key-0001";
    private static final String FINGERPRINT = "fingerprint-a";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private InMemoryIdempotencyStore store;
    private IdempotencyGuard guard;

    @BeforeEach
    void setUp() {
        store = new InMemoryIdempotencyStore();
        guard = new IdempotencyGuard(store, new ObjectMapper().findAndRegisterModules(), new MutableClock(NOW));
    }

    @Test
    void executesOnceAndReplaysTheStoredResponse() {
        AtomicInteger executions = new AtomicInteger();

        IdempotencyGuard.Outcome<LoginResponse> first = guard.run(
                SCOPE, IdempotencyOperation.REGISTER, KEY, FINGERPRINT, LoginResponse.class,
                () -> {
                    executions.incrementAndGet();
                    return new LoginResponse("token-1", "Bearer", 900);
                });
        IdempotencyGuard.Outcome<LoginResponse> replay = guard.run(
                SCOPE, IdempotencyOperation.REGISTER, KEY, FINGERPRINT, LoginResponse.class,
                () -> {
                    executions.incrementAndGet();
                    return new LoginResponse("token-2", "Bearer", 900);
                });

        assertThat(first.replayed()).isFalse();
        assertThat(first.response().accessToken()).isEqualTo("token-1");
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.response()).isEqualTo(first.response());
        assertThat(executions).hasValue(1);
    }

    @Test
    void rejectsTheSameKeyForDifferentRequestContent() {
        guard.run(SCOPE, IdempotencyOperation.REGISTER, KEY, FINGERPRINT, LoginResponse.class,
                () -> new LoginResponse("token-1", "Bearer", 900));

        assertThatThrownBy(() -> guard.run(
                SCOPE, IdempotencyOperation.REGISTER, KEY, "fingerprint-b", LoginResponse.class,
                () -> new LoginResponse("token-2", "Bearer", 900)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.USR_IDEMPOTENCY_001));
    }

    @Test
    void rejectsASecondCallWhileTheFirstIsStillRunning() {
        store.tryStart(SCOPE, IdempotencyOperation.USER_ROLE_UPDATE, KEY, FINGERPRINT, NOW);

        assertThatThrownBy(() -> guard.run(
                SCOPE, IdempotencyOperation.USER_ROLE_UPDATE, KEY, FINGERPRINT, LoginResponse.class,
                () -> new LoginResponse("token", "Bearer", 900)))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.USR_IDEMPOTENCY_001);
                    assertThat(exception.getDetail()).contains("in progress");
                });
    }

    @Test
    void releasesTheKeyWhenTheActionFails() {
        assertThatThrownBy(() -> guard.run(
                SCOPE, IdempotencyOperation.REGISTER, KEY, FINGERPRINT, LoginResponse.class,
                () -> {
                    throw new IllegalStateException("boom");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(store.find(SCOPE, IdempotencyOperation.REGISTER, KEY)).isEmpty();
        assertThat(guard.run(SCOPE, IdempotencyOperation.REGISTER, KEY, FINGERPRINT, LoginResponse.class,
                () -> new LoginResponse("token-after-retry", "Bearer", 900)).replayed()).isFalse();
    }

    @Test
    void replaysBodylessOperations() {
        AtomicInteger executions = new AtomicInteger();

        IdempotencyGuard.Outcome<Void> first = guard.run(
                SCOPE, IdempotencyOperation.CHANGE_PASSWORD, KEY, FINGERPRINT, Void.class,
                () -> {
                    executions.incrementAndGet();
                    return null;
                });
        IdempotencyGuard.Outcome<Void> replay = guard.run(
                SCOPE, IdempotencyOperation.CHANGE_PASSWORD, KEY, FINGERPRINT, Void.class,
                () -> {
                    executions.incrementAndGet();
                    return null;
                });

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.response()).isNull();
        assertThat(executions).hasValue(1);
    }

    @Test
    void scopesKeysByOperationAndCaller() {
        guard.run(SCOPE, IdempotencyOperation.REGISTER, KEY, FINGERPRINT, LoginResponse.class,
                () -> new LoginResponse("token-1", "Bearer", 900));

        assertThat(store.find("another-scope", IdempotencyOperation.REGISTER, KEY)).isEmpty();
        assertThat(store.find(SCOPE, IdempotencyOperation.CHANGE_PASSWORD, KEY)).isEmpty();
        assertThat(store.size()).isEqualTo(1);
    }
}
