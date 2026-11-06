package com.docqa.user.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.user.domain.IdempotencyOperation;
import java.time.Clock;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 幂等写的统一流程：占位 -> 执行业务 -> 落结果，失败则释放占位。
 * 三个写操作（注册、角色替换、状态变更）语义一致，集中一处以免各自实现漂移出不同的 409 行为。
 */
@Component
public class IdempotencyGuard {

    private static final Logger LOGGER = LoggerFactory.getLogger(IdempotencyGuard.class);
    private static final String NO_BODY = "null";

    private final IdempotencyStore store;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IdempotencyGuard(IdempotencyStore store, ObjectMapper objectMapper, Clock clock) {
        this.store = store;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public <T> Outcome<T> run(
            String scope,
            IdempotencyOperation operation,
            String key,
            String fingerprint,
            Class<T> responseType,
            Supplier<T> action) {
        Optional<IdempotencyRecord> existing = store.find(scope, operation, key);
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint, responseType);
        }
        if (!store.tryStart(scope, operation, key, fingerprint, clock.instant())) {
            IdempotencyRecord record = store.find(scope, operation, key)
                    .orElseThrow(() -> inProgress());
            return replay(record, fingerprint, responseType);
        }

        boolean finished = false;
        try {
            T response = action.get();
            finished = true;
            complete(scope, operation, key, fingerprint, response, responseType);
            return new Outcome<>(response, false);
        } finally {
            if (!finished) {
                store.clear(scope, operation, key);
            }
        }
    }

    private <T> void complete(
            String scope,
            IdempotencyOperation operation,
            String key,
            String fingerprint,
            T response,
            Class<T> responseType) {
        String json = responseType == Void.class ? NO_BODY : serialize(response);
        if (json == null) {
            return;
        }
        store.complete(scope, operation, key, fingerprint, json, clock.instant());
    }

    private <T> Outcome<T> replay(IdempotencyRecord record, String fingerprint, Class<T> responseType) {
        if (record.status() == IdempotencyStatus.IN_PROGRESS) {
            throw inProgress();
        }
        if (!fingerprint.equals(record.fingerprint())) {
            throw new BusinessException(
                    ErrorCode.USR_IDEMPOTENCY_001,
                    "The same Idempotency-Key was used with a different request");
        }
        if (responseType == Void.class) {
            return new Outcome<>(null, true);
        }
        try {
            T response = objectMapper.readValue(record.responseJson(), responseType);
            return new Outcome<>(response, true);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(
                    ErrorCode.COM_INTERNAL_001,
                    "Idempotency record is unreadable, please retry later",
                    ex);
        }
    }

    private String serialize(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException ex) {
            LOGGER.error("Unable to serialize user idempotency response, releasing the key");
            return null;
        }
    }

    private BusinessException inProgress() {
        return new BusinessException(
                ErrorCode.USR_IDEMPOTENCY_001,
                "The same Idempotency-Key is still in progress");
    }

    public record Outcome<T>(T response, boolean replayed) {
    }
}
