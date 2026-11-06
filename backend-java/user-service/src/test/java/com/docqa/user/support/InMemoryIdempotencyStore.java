package com.docqa.user.support;

import com.docqa.user.domain.IdempotencyOperation;
import com.docqa.user.service.IdempotencyRecord;
import com.docqa.user.service.IdempotencyStatus;
import com.docqa.user.service.IdempotencyStore;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** 进程内幂等存储，用来在不连 Redis 的情况下验证「占位-执行-落结果」的真实时序。 */
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private final Map<String, IdempotencyRecord> records = new HashMap<>();

    @Override
    public Optional<IdempotencyRecord> find(String scope, IdempotencyOperation operation, String key) {
        return Optional.ofNullable(records.get(redisKey(scope, operation, key)));
    }

    @Override
    public boolean tryStart(
            String scope,
            IdempotencyOperation operation,
            String key,
            String fingerprint,
            Instant now) {
        String redisKey = redisKey(scope, operation, key);
        if (records.containsKey(redisKey)) {
            return false;
        }
        records.put(redisKey, new IdempotencyRecord(IdempotencyStatus.IN_PROGRESS, fingerprint, null, now));
        return true;
    }

    @Override
    public void complete(
            String scope,
            IdempotencyOperation operation,
            String key,
            String fingerprint,
            String responseJson,
            Instant now) {
        records.put(
                redisKey(scope, operation, key),
                new IdempotencyRecord(IdempotencyStatus.COMPLETED, fingerprint, responseJson, now));
    }

    @Override
    public void clear(String scope, IdempotencyOperation operation, String key) {
        records.remove(redisKey(scope, operation, key));
    }

    public int size() {
        return records.size();
    }

    private String redisKey(String scope, IdempotencyOperation operation, String key) {
        return scope + "|" + operation.name() + "|" + key;
    }
}
