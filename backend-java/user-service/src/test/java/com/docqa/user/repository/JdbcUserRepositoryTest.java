package com.docqa.user.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.docqa.common.tenant.TenantContext;
import com.docqa.user.domain.NewUser;
import com.docqa.user.domain.UserCredentials;
import com.docqa.user.domain.UserRole;
import com.docqa.user.domain.UserStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;

class JdbcUserRepositoryTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CURSOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final JdbcUserRepository repository = new JdbcUserRepository(jdbcTemplate);

    @Test
    void findsCredentialsWithABoundUsername() {
        UserCredentials credentials = new UserCredentials(USER_ID, "demo", "$2a$04$hash", UserStatus.ACTIVE);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                eq(TenantContext.DEFAULT_TENANT_ID.toString()), eq("demo"))).thenReturn(List.of(credentials));

        Optional<UserCredentials> found =
                repository.findCredentialsByUsername(TenantContext.DEFAULT_TENANT_ID, "demo");

        assertThat(found).contains(credentials);
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class),
                eq(TenantContext.DEFAULT_TENANT_ID.toString()), eq("demo"));
        assertThat(sqlCaptor.getValue())
                .contains("SELECT id, username, password_hash, status FROM app_user")
                .contains("tenant_id = ?")
                .contains("username = ?")
                .doesNotContain("demo");
    }

    @Test
    void findsCredentialsByIdWithABoundId() {
        UserCredentials credentials = new UserCredentials(USER_ID, "demo", "$2a$04$hash", UserStatus.ACTIVE);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                eq(TenantContext.DEFAULT_TENANT_ID.toString()), eq(USER_ID.toString())))
                .thenReturn(List.of(credentials));

        assertThat(repository.findCredentialsById(TenantContext.DEFAULT_TENANT_ID, USER_ID))
                .contains(credentials);
        verify(jdbcTemplate).query(anyString(), any(RowMapper.class),
                eq(TenantContext.DEFAULT_TENANT_ID.toString()), eq(USER_ID.toString()));
    }

    @Test
    void firstPageOnlyCarriesTheLimit() {
        repository.findPage(TenantContext.DEFAULT_TENANT_ID, null, null, null, null, 21);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class),
                eq(TenantContext.DEFAULT_TENANT_ID.toString()), eq(21));
        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("FROM app_user u WHERE u.tenant_id = ?");
        assertThat(sql).contains("ORDER BY u.created_at DESC, u.id DESC LIMIT ?");
        assertThat(sql).doesNotContain("u.status = ?").doesNotContain("EXISTS").doesNotContain("u.created_at < ?");
    }

    @Test
    void filtersAndCursorBindInDeclarationOrder() {
        repository.findPage(TenantContext.DEFAULT_TENANT_ID, UserStatus.ACTIVE, UserRole.ADMIN,
                NOW, CURSOR_ID, 21);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
                sqlCaptor.capture(),
                any(RowMapper.class),
                eq(TenantContext.DEFAULT_TENANT_ID.toString()),
                eq("ACTIVE"),
                eq("ADMIN"),
                eq(Timestamp.from(NOW)),
                eq(Timestamp.from(NOW)),
                eq(CURSOR_ID.toString()),
                eq(21));
        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("AND u.status = ?");
        assertThat(sql).contains("AND EXISTS (SELECT 1 FROM app_user_role r"
                + " WHERE r.tenant_id = u.tenant_id AND r.user_id = u.id AND r.role = ?)");
        assertThat(sql).contains("AND (u.created_at < ? OR (u.created_at = ? AND u.id < ?))");
        assertThat(sql).doesNotContain("ACTIVE").doesNotContain("ADMIN").doesNotContain(CURSOR_ID.toString());
    }

    @Test
    void insertWritesTheAccountAndEveryRoleInTheSameTransaction() throws Exception {
        repository.insert(TenantContext.DEFAULT_TENANT_ID,
                new NewUser(USER_ID, "demo", "$2a$04$hash", UserStatus.ACTIVE, NOW),
                EnumSet.of(UserRole.USER, UserRole.ADMIN));

        assertThat(JdbcUserRepository.class
                .getMethod("insert", UUID.class, NewUser.class, Set.class)
                .isAnnotationPresent(Transactional.class)).isTrue();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, times(3)).update(sqlCaptor.capture(), argsCaptor.capture());

        List<String> statements = sqlCaptor.getAllValues();
        assertThat(statements.get(0)).contains("INSERT INTO app_user");
        assertThat(statements.get(1)).contains("INSERT INTO app_user_role");
        assertThat(statements.get(2)).contains("INSERT INTO app_user_role");

        assertThat(argsCaptor.getAllValues().get(0)).containsExactly(
                TenantContext.DEFAULT_TENANT_ID.toString(),
                USER_ID.toString(),
                "demo",
                "$2a$04$hash",
                "ACTIVE",
                Timestamp.from(NOW),
                Timestamp.from(NOW));
        assertThat(argsCaptor.getAllValues().get(1)).containsExactly(
                TenantContext.DEFAULT_TENANT_ID.toString(), USER_ID.toString(), "USER", Timestamp.from(NOW));
        assertThat(argsCaptor.getAllValues().get(2)).containsExactly(
                TenantContext.DEFAULT_TENANT_ID.toString(), USER_ID.toString(), "ADMIN", Timestamp.from(NOW));
    }

    @Test
    void existsByUsernameReadsTheCount() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class),
                eq(TenantContext.DEFAULT_TENANT_ID.toString()), eq("demo"))).thenReturn(1);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class),
                eq(TenantContext.DEFAULT_TENANT_ID.toString()), eq("free"))).thenReturn(0);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class),
                eq(TenantContext.DEFAULT_TENANT_ID.toString()), eq("null-count"))).thenReturn(null);

        assertThat(repository.existsByUsername(TenantContext.DEFAULT_TENANT_ID, "demo")).isTrue();
        assertThat(repository.existsByUsername(TenantContext.DEFAULT_TENANT_ID, "free")).isFalse();
        assertThat(repository.existsByUsername(TenantContext.DEFAULT_TENANT_ID, "null-count")).isFalse();
    }

    @Test
    void updateStatementsBindTheirColumns() {
        repository.updateStatus(TenantContext.DEFAULT_TENANT_ID, USER_ID, UserStatus.DISABLED, NOW);
        repository.updatePasswordHash(TenantContext.DEFAULT_TENANT_ID, USER_ID, "$2a$04$new", NOW);
        repository.touchLastLogin(TenantContext.DEFAULT_TENANT_ID, USER_ID, NOW);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), eq("DISABLED"), eq(Timestamp.from(NOW)),
                eq(TenantContext.DEFAULT_TENANT_ID.toString()), eq(USER_ID.toString()));
        verify(jdbcTemplate).update(
                eq("UPDATE app_user SET password_hash = ?, updated_at = ? WHERE tenant_id = ? AND id = ?"),
                eq("$2a$04$new"),
                eq(Timestamp.from(NOW)),
                eq(TenantContext.DEFAULT_TENANT_ID.toString()),
                eq(USER_ID.toString()));
        verify(jdbcTemplate).update(
                eq("UPDATE app_user SET last_login_at = ? WHERE tenant_id = ? AND id = ?"),
                eq(Timestamp.from(NOW)),
                eq(TenantContext.DEFAULT_TENANT_ID.toString()),
                eq(USER_ID.toString()));

        assertThat(sqlCaptor.getValue()).isEqualTo(
                "UPDATE app_user SET status = ?, updated_at = ? WHERE tenant_id = ? AND id = ?");
    }

    @Test
    void findByIdSelectsOnlyPublicColumns() {
        repository.findById(TenantContext.DEFAULT_TENANT_ID, USER_ID);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class),
                eq(TenantContext.DEFAULT_TENANT_ID.toString()), eq(USER_ID.toString()));
        assertThat(sqlCaptor.getValue())
                .contains("SELECT id, username, status, last_login_at, created_at"
                        + " FROM app_user WHERE tenant_id = ? AND id = ?")
                .doesNotContain("password_hash");
    }
}
