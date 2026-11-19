package com.docqa.user.repository;

import com.docqa.user.domain.TenantRecord;
import java.util.Optional;
import java.util.UUID;

/** 租户控制面目录：只在登录/注册（尚无 JWT 可用）时按 slug 解析租户。 */
public interface TenantRepository {

    Optional<TenantRecord> findBySlug(String slug);

    /**
     * 写入一条新租户行。slug 全局唯一，重复时返回 0（由调用方决定是幂等返回还是报冲突）。
     *
     * <p>这是 P1 租户供给入口的写通道；与 {@code findBySlug} 一样不带 tenant_id 谓词——
     * 操作对象就是租户清单本身，见 JdbcTenantRepository 的类注释。
     *
     * @return 1 表示写入成功；0 表示 slug 已存在
     */
    int insert(UUID id, String slug, String name, String tier, String isolation);
}
