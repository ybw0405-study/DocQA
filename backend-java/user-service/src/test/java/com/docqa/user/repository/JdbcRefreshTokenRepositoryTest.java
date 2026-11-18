package com.docqa.user.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.docqa.common.tenant.TenantContext;
import com.docqa.user.domain.RefreshTokenRow;
import com.docqa.user.domain.RefreshTokenStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcRefreshTokenRepositoryTest {

    private static final UUID TOKEN_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID FAMILY_ID = UUID.fromString("00000000-0000-0000-0000-000000000020");
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant EXPIRES_AT = NOW.plusSeconds(86_400);

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final JdbcRefreshTokenRepository repository = new JdbcRefreshTokenRepository(jdbcTemplate);

    @Test
    void insertBindsEveryColumn() {
        repository.insert(TenantContext.DEFAULT_TENANT_ID, row(RefreshTokenStatus.ACTIVE));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(
                sqlCaptor.capture(),
                eq(TenantContext.DEFAULT_TENANT_ID.toString()),
                eq(TOKEN_ID.toString()),
                eq(USER_ID.toString()),
                eq(FAMILY_ID.toString()),
                eq("hash-1"),
                eq("ACTIVE"),
                eq(Timestamp.from(EXPIRES_AT)),
                eq(Timestamp.from(NOW)),
                eq(Timestamp.from(NOW)));
        assertThat(sqlCaptor.getValue())
                .contains("INSERT INTO refresh_token")
                .contains("tenant_id");
    }

    @Test
    void findByHashReadsTheRow() {
        RefreshTokenRow stored = row(RefreshTokenStatus.ACTIVE);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                eq(TenantContext.DEFAULT_TENANT_ID.toString()), eq("hash-1"))).thenReturn(List.of(stored));

        Optional<RefreshTokenRow> found =
                repository.findByHash(TenantContext.DEFAULT_TENANT_ID, "hash-1");

        assertThat(found).contains(stored);
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class),
                eq(TenantContext.DEFAULT_TENANT_ID.toString()), eq("hash-1"));
        assertThat(sqlCaptor.getValue())
                .contains("FROM refresh_token WHERE tenant_id = ? AND token_hash = ?");
        assertThat(sqlCaptor.getValue()).contains("token_hash").doesNotContain("hash-1");
    }

    @Test
    void consumeActiveIsAConditionalUpdate() {
        when(jdbcTemplate.update(anyString(), eq(Timestamp.from(NOW)),
                eq(TenantContext.DEFAULT_TENANT_ID.toString()), eq(TOKEN_ID.toString()))).thenReturn(1);

        assertThat(repository.consumeActive(TenantContext.DEFAULT_TENANT_ID, TOKEN_ID, NOW)).isEqualTo(1);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), eq(Timestamp.from(NOW)),
                eq(TenantContext.DEFAULT_TENANT_ID.toString()), eq(TOKEN_ID.toString()));
        assertThat(sqlCaptor.getValue())
                .contains("SET status = 'USED'")
                .contains("WHERE tenant_id = ? AND id = ? AND status = 'ACTIVE'");
    }

    @Test
    void revokeFamilyOnlyTouchesActiveRows() {
        repository.revokeFamily(TenantContext.DEFAULT_TENANT_ID, FAMILY_ID, NOW);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), eq(Timestamp.from(NOW)),
                eq(TenantContext.DEFAULT_TENANT_ID.toString()), eq(FAMILY_ID.toString()));
        assertThat(sqlCaptor.getValue())
                .contains("SET status = 'REVOKED'")
                .contains("WHERE tenant_id = ? AND family_id = ? AND status = 'ACTIVE'");
    }

    @Test
    void revokeAllActiveForUserOnlyTouchesActiveRows() {
        repository.revokeAllActiveForUser(TenantContext.DEFAULT_TENANT_ID, USER_ID, NOW);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), eq(Timestamp.from(NOW)),
                eq(TenantContext.DEFAULT_TENANT_ID.toString()), eq(USER_ID.toString()));
        assertThat(sqlCaptor.getValue())
                .contains("SET status = 'REVOKED'")
                .contains("WHERE tenant_id = ? AND user_id = ? AND status = 'ACTIVE'");
    }

    @Test
    void deleteExpiredTerminalSkipsLiveAndActiveRows() {
        repository.deleteExpiredTerminal(NOW, 500);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), eq(Timestamp.from(NOW)), eq(500));
        assertThat(sqlCaptor.getValue())
                .contains("DELETE FROM refresh_token")
                .contains("WHERE status IN ('USED', 'REVOKED') AND expires_at < ?")
                .contains("LIMIT ?");
    }

    private RefreshTokenRow row(RefreshTokenStatus status) {
        return new RefreshTokenRow(TOKEN_ID, USER_ID, FAMILY_ID, "hash-1", status, EXPIRES_AT, NOW);
    }
}
