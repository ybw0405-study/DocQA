package com.docqa.user.support;

import com.docqa.user.domain.RefreshTokenRow;
import com.docqa.user.domain.RefreshTokenStatus;
import com.docqa.user.repository.RefreshTokenRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/** 进程内刷新令牌表，复刻 CAS 占用与整家族吊销的语义，使轮换与复用检测可被真实断言。 */
public class InMemoryRefreshTokenRepository implements RefreshTokenRepository {

    private final Map<UUID, RefreshTokenRow> rows = new LinkedHashMap<>();
    /** 行的租户归属：让测试里的 findByHash / revoke 也按租户过滤，不能一根筋全局匹配。 */
    private final Map<UUID, UUID> tenantById = new LinkedHashMap<>();

    @Override
    public void insert(UUID tenantId, RefreshTokenRow token) {
        rows.put(token.id(), token);
        tenantById.put(token.id(), tenantId);
    }

    @Override
    public Optional<RefreshTokenRow> findByHash(UUID tenantId, String tokenHash) {
        return rows.values().stream()
                .filter(row -> row.tokenHash().equals(tokenHash))
                .filter(row -> tenantId.equals(tenantById.get(row.id())))
                .findFirst();
    }

    @Override
    public Optional<UUID> findTenantIdByHash(String tokenHash) {
        return rows.values().stream()
                .filter(row -> row.tokenHash().equals(tokenHash))
                .map(row -> tenantById.get(row.id()))
                .findFirst();
    }

    @Override
    public int consumeActive(UUID tenantId, UUID id, Instant now) {
        RefreshTokenRow row = rows.get(id);
        if (row == null || !tenantId.equals(tenantById.get(id)) || row.status() != RefreshTokenStatus.ACTIVE) {
            return 0;
        }
        rows.put(id, copyWithStatus(row, RefreshTokenStatus.USED));
        return 1;
    }

    @Override
    public int revokeFamily(UUID tenantId, UUID familyId, Instant now) {
        return revokeActive(row -> row.familyId().equals(familyId)
                && tenantId.equals(tenantById.get(row.id())));
    }

    @Override
    public int revokeAllActiveForUser(UUID tenantId, UUID userId, Instant now) {
        return revokeActive(row -> row.userId().equals(userId)
                && tenantId.equals(tenantById.get(row.id())));
    }

    @Override
    public int deleteExpiredTerminal(Instant now, int limit) {
        List<UUID> removable = rows.values().stream()
                .filter(row -> row.status() != RefreshTokenStatus.ACTIVE)
                .filter(row -> !row.expiresAt().isAfter(now))
                .limit(limit)
                .map(RefreshTokenRow::id)
                .toList();
        removable.forEach(id -> {
            rows.remove(id);
            tenantById.remove(id);
        });
        return removable.size();
    }

    public List<RefreshTokenRow> all() {
        return List.copyOf(rows.values());
    }

    public long countByStatus(RefreshTokenStatus status) {
        return rows.values().stream().filter(row -> row.status() == status).count();
    }

    private int revokeActive(Predicate<RefreshTokenRow> selector) {
        List<UUID> targets = new ArrayList<>();
        for (RefreshTokenRow row : rows.values()) {
            if (row.status() == RefreshTokenStatus.ACTIVE && selector.test(row)) {
                targets.add(row.id());
            }
        }
        targets.forEach(id -> rows.put(id, copyWithStatus(rows.get(id), RefreshTokenStatus.REVOKED)));
        return targets.size();
    }

    private RefreshTokenRow copyWithStatus(RefreshTokenRow row, RefreshTokenStatus status) {
        return new RefreshTokenRow(
                row.id(),
                row.userId(),
                row.familyId(),
                row.tokenHash(),
                status,
                row.expiresAt(),
                row.createdAt());
    }
}
