package com.docqa.document.service;

import com.docqa.common.tenant.TenantContext;
import com.docqa.document.repository.TenantBootstrapRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 保证默认租户行存在。
 *
 * <p>为什么不在 V10 迁移里种这一行：迁移账号 {@code ss_migrator} 只持有 schema 级 DDL
 * 权限与 {@code flyway_schema_history} 的 DML，业务表的写权限为 0（ops/sql/README.md）。
 * V1–V9 全是纯 DDL，V10 引入一行 INSERT 会直接让迁移失败——这不是理论风险，本机
 * MySQL 8.4 上已实测报 {@code INSERT command denied}。因此把种子数据挪到启动期，
 * 由 app 账号写入，迁移维持纯 DDL 的契约。
 *
 * <p>V10 给所有业务表的 tenant_id 设了 DEFAULT 全 1 UUID，没有指向 tenant 的外键，
 * 所以「表已建、行未种」的中间态不会破坏约束；本类保证该窗口在服务就绪前关闭。
 *
 * <p>{@code docqa.tenant.bootstrap-default=false} 用于两种场景：只跑迁移不启服务；
 * 以及租户行由 DBA 预置的部署形态。默认开启。
 */
@Component
@ConditionalOnProperty(name = "docqa.tenant.bootstrap-default", havingValue = "true", matchIfMissing = true)
public class DefaultTenantInitializer implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultTenantInitializer.class);

    private static final String DEFAULT_SLUG = "default";
    private static final String DEFAULT_NAME = "Default Workspace";
    private static final String DEFAULT_TIER = "ENTERPRISE";
    private static final String DEFAULT_ISOLATION = "SHARED";

    private final TenantBootstrapRepository tenantBootstrapRepository;

    public DefaultTenantInitializer(TenantBootstrapRepository tenantBootstrapRepository) {
        this.tenantBootstrapRepository = tenantBootstrapRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        int inserted = tenantBootstrapRepository.insertIfAbsent(
                TenantContext.DEFAULT_TENANT_ID,
                DEFAULT_SLUG,
                DEFAULT_NAME,
                DEFAULT_TIER,
                DEFAULT_ISOLATION);
        if (inserted == 1) {
            LOGGER.info("Seeded default tenant id={} slug={}", TenantContext.DEFAULT_TENANT_ID, DEFAULT_SLUG);
        }
    }
}
