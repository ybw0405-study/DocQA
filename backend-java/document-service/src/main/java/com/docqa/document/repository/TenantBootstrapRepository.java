package com.docqa.document.repository;

import java.util.UUID;

/**
 * 默认租户种子行的写入通道。
 *
 * <p>只做一件事：把「存量数据归属的那个租户」写进 tenant 表。这不是迁移的一部分——
 * 迁移账号 {@code ss_migrator} 只有 schema 级 DDL 权限，没有任何业务表的 INSERT 权限
 * （见 ops/sql/README.md），所以 V10 保持纯 DDL，种子行由应用启动期用 app 账号写入。
 *
 * <p>刻意不放进通用的 TenantRepository：P1 接入多租户后会有真正的租户 CRUD，
 * 那时这个类应当保留为「启动期锚点」的窄接口，避免被当成租户管理入口滥用。
 */
public interface TenantBootstrapRepository {

    /**
     * 幂等写入一条租户行；主键或 slug 冲突时静默跳过。
     *
     * @return 1 表示本次确实写入；0 表示已存在（含多个实例并发启动时被抢先写入）
     */
    int insertIfAbsent(UUID tenantId, String slug, String name, String tier, String isolation);
}
