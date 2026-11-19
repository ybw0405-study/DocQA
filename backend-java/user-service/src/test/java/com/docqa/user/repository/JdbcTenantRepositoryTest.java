package com.docqa.user.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.docqa.user.domain.TenantRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcTenantRepositoryTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final JdbcTenantRepository repository = new JdbcTenantRepository(jdbcTemplate);

    @Test
    void insertUsesInsertIgnoreForIdempotentProvisioning() {
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        int inserted = repository.insert(TENANT_ID, "acme", "Acme Inc", "STANDARD", "SHARED");

        assertThat(inserted).isEqualTo(1);
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(
                sqlCaptor.capture(),
                eq(TENANT_ID.toString()),
                eq("acme"),
                eq("Acme Inc"),
                eq("STANDARD"),
                eq("SHARED"));
        assertThat(sqlCaptor.getValue())
                .contains("INSERT IGNORE INTO tenant")
                .contains("'ACTIVE'");
    }

    @Test
    void findBySlugReturnsTheTenantIdentity() {
        TenantRecord record = new TenantRecord(TENANT_ID, "acme", "ACTIVE");
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("acme")))
                .thenReturn(List.of(record));

        Optional<TenantRecord> found = repository.findBySlug("acme");

        assertThat(found).contains(record);
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class), eq("acme"));
        assertThat(sqlCaptor.getValue()).contains("SELECT id, slug, status FROM tenant WHERE slug = ?");
    }
}
