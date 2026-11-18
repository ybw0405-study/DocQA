package com.docqa.document.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.docqa.common.tenant.TenantContext;
import com.docqa.document.repository.TenantBootstrapRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;

@ExtendWith(MockitoExtension.class)
class DefaultTenantInitializerTest {

    @Mock
    private TenantBootstrapRepository tenantBootstrapRepository;

    private DefaultTenantInitializer initializer;

    @BeforeEach
    void setUp() {
        initializer = new DefaultTenantInitializer(tenantBootstrapRepository);
    }

    @Test
    void seedsTheDefaultTenantWithTheFixedAnchorId() {
        when(tenantBootstrapRepository.insertIfAbsent(
                TenantContext.DEFAULT_TENANT_ID, "default", "Default Workspace", "ENTERPRISE", "SHARED"))
                .thenReturn(1);

        initializer.run(new DefaultApplicationArguments());

        verify(tenantBootstrapRepository).insertIfAbsent(
                TenantContext.DEFAULT_TENANT_ID, "default", "Default Workspace", "ENTERPRISE", "SHARED");
    }

    @Test
    void staysQuietWhenTheRowIsAlreadyThere() {
        when(tenantBootstrapRepository.insertIfAbsent(
                TenantContext.DEFAULT_TENANT_ID, "default", "Default Workspace", "ENTERPRISE", "SHARED"))
                .thenReturn(0);

        assertThatCode(() -> initializer.run(new DefaultApplicationArguments()))
                .as("已存在不是异常：每次启动都会执行，必须幂等")
                .doesNotThrowAnyException();
    }
}
