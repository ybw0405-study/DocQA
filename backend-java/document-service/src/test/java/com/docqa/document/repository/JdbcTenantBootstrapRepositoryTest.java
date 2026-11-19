package com.docqa.document.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.docqa.common.tenant.TenantContext;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class JdbcTenantBootstrapRepositoryTest {

    private static final UUID TENANT_ID = TenantContext.DEFAULT_TENANT_ID;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private JdbcTenantBootstrapRepository repository;

    @BeforeEach
    void setUp() {
        repository = new JdbcTenantBootstrapRepository(jdbcTemplate);
    }

    @Test
    void insertUsesInsertIgnoreSoConcurrentStartupsAreSafe() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        int inserted = repository.insertIfAbsent(
                TENANT_ID, "default", "Default Workspace", "ENTERPRISE", "SHARED");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), eq(TENANT_ID.toString()), eq("default"),
                eq("Default Workspace"), eq("ENTERPRISE"), eq("SHARED"));
        assertThat(sql.getValue())
                .as("先查再插会在多实例并发启动时撞唯一键，必须用 INSERT IGNORE")
                .contains("INSERT IGNORE INTO tenant");
        assertThat(inserted).isEqualTo(1);
    }

    @Test
    void returnsZeroWhenTheRowAlreadyExists() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);

        assertThat(repository.insertIfAbsent(
                TENANT_ID, "default", "Default Workspace", "ENTERPRISE", "SHARED"))
                .isZero();
    }
}
