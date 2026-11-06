package com.docqa.user.service;

import com.docqa.user.domain.IdempotencyOperation;
import java.time.Instant;
import java.util.Optional;

/**
 * 写接口幂等存储。key 第一段是「作用域」：已登录操作用 userId，匿名的注册操作用归一化用户名，
 * 因为此时还不存在 userId。
 */
public interface IdempotencyStore {

    Optional<IdempotencyRecord> find(String scope, IdempotencyOperation operation, String key);

    boolean tryStart(String scope, IdempotencyOperation operation, String key, String fingerprint, Instant now);

    void complete(String scope, IdempotencyOperation operation, String key, String fingerprint,
            String responseJson, Instant now);

    void clear(String scope, IdempotencyOperation operation, String key);
}
