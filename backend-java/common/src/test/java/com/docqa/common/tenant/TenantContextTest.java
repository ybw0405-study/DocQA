package com.docqa.common.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TenantContextTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void requireFailsClosedWhenNoTenantIsSet() {
        assertThatThrownBy(TenantContext::require)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tenant context is missing");
    }

    @Test
    void setAndRequireRoundTrip() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);

        assertThat(TenantContext.require()).isEqualTo(tenantId);
        assertThat(TenantContext.currentOrNull()).isEqualTo(tenantId);
    }

    @Test
    void clearRestoresTheFailClosedState() {
        TenantContext.set(UUID.randomUUID());
        TenantContext.clear();

        assertThat(TenantContext.currentOrNull()).isNull();
        assertThatThrownBy(TenantContext::require).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void setRejectsNull() {
        assertThatThrownBy(() -> TenantContext.set(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defaultTenantMatchesMigrationConstant() {
        assertThat(TenantContext.DEFAULT_TENANT_ID.toString())
                .isEqualTo("11111111-1111-1111-1111-111111111111");
    }
}
