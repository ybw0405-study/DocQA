package com.docqa.user.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.docqa.user.domain.UserRole;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;

class JdbcRoleRepositoryTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final JdbcRoleRepository repository = new JdbcRoleRepository(jdbcTemplate);

    @Test
    void findRolesBindsTheUserId() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(USER_ID.toString())))
                .thenReturn(List.of(UserRole.USER, UserRole.ADMIN));

        Set<UserRole> roles = repository.findRoles(USER_ID);

        assertThat(roles).containsExactlyInAnyOrder(UserRole.USER, UserRole.ADMIN);
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class), eq(USER_ID.toString()));
        assertThat(sqlCaptor.getValue()).isEqualTo(
                "SELECT role FROM app_user_role WHERE user_id = ? ORDER BY role");
    }

    @Test
    void findRolesReturnsAnEmptySetWhenTheUserHasNone() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(USER_ID.toString())))
                .thenReturn(List.of());

        assertThat(repository.findRoles(USER_ID)).isEmpty();
    }

    @Test
    void findRolesByUserIdsShortCircuitsOnAnEmptyPage() {
        assertThat(repository.findRolesByUserIds(List.of())).isEmpty();

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void findRolesByUserIdsBatchesOnePlaceholderPerId() throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString("user_id")).thenReturn(USER_ID.toString(), OTHER_ID.toString());
        when(resultSet.getString("role")).thenReturn("USER", "ADMIN");
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(1);
            handler.processRow(resultSet);
            handler.processRow(resultSet);
            return null;
        }).when(jdbcTemplate).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));

        Map<UUID, Set<UserRole>> roles = repository.findRolesByUserIds(List.of(USER_ID, OTHER_ID));

        assertThat(roles).isEqualTo(Map.of(
                USER_ID, Set.of(UserRole.USER),
                OTHER_ID, Set.of(UserRole.ADMIN)));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowCallbackHandler.class), argsCaptor.capture());
        assertThat(sqlCaptor.getValue())
                .contains("WHERE user_id IN (?, ?)")
                .contains("ORDER BY user_id, role");
        assertThat(argsCaptor.getValue()).containsExactly(USER_ID.toString(), OTHER_ID.toString());
    }

    @Test
    void replaceRolesDeletesThenInsertsInsideOneTransaction() throws Exception {
        repository.replaceRoles(USER_ID, EnumSet.of(UserRole.USER, UserRole.ADMIN), NOW);

        assertThat(JdbcRoleRepository.class
                .getMethod("replaceRoles", UUID.class, Set.class, Instant.class)
                .isAnnotationPresent(Transactional.class)).isTrue();

        InOrder inOrder = inOrder(jdbcTemplate);
        inOrder.verify(jdbcTemplate).update(
                eq("DELETE FROM app_user_role WHERE user_id = ?"), eq(USER_ID.toString()));
        inOrder.verify(jdbcTemplate).update(
                eq("INSERT INTO app_user_role (user_id, role, created_at) VALUES (?, ?, ?)"),
                eq(USER_ID.toString()),
                eq("USER"),
                eq(Timestamp.from(NOW)));
        inOrder.verify(jdbcTemplate).update(
                eq("INSERT INTO app_user_role (user_id, role, created_at) VALUES (?, ?, ?)"),
                eq(USER_ID.toString()),
                eq("ADMIN"),
                eq(Timestamp.from(NOW)));
        inOrder.verifyNoMoreInteractions();
    }
}
