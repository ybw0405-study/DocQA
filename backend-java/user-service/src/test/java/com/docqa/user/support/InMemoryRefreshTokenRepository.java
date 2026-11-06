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

    @Override
    public void insert(RefreshTokenRow token) {
        rows.put(token.id(), token);
    }

    @Override
    public Optional<RefreshTokenRow> findByHash(String tokenHash) {
        return rows.values().stream()
                .filter(row -> row.tokenHash().equals(tokenHash))
                .findFirst();
    }

    @Override
    public int consumeActive(UUID id, Instant now) {
        RefreshTokenRow row = rows.get(id);
        if (row == null || row.status() != RefreshTokenStatus.ACTIVE) {
            return 0;
        }
        rows.put(id, copyWithStatus(row, RefreshTokenStatus.USED));
        return 1;
    }

    @Override
    public int revokeFamily(UUID familyId, Instant now) {
        return revokeActive(row -> row.familyId().equals(familyId));
    }

    @Override
    public int revokeAllActiveForUser(UUID userId, Instant now) {
        return revokeActive(row -> row.userId().equals(userId));
    }

    @Override
    public int deleteExpiredTerminal(Instant now, int limit) {
        List<UUID> removable = rows.values().stream()
                .filter(row -> row.status() != RefreshTokenStatus.ACTIVE)
                .filter(row -> !row.expiresAt().isAfter(now))
                .limit(limit)
                .map(RefreshTokenRow::id)
                .toList();
        removable.forEach(rows::remove);
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
