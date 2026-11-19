package com.docqa.user.repository;

import com.docqa.user.domain.TenantRecord;
import java.util.Optional;

/** 租户控制面目录：只在登录/注册（尚无 JWT 可用）时按 slug 解析租户。 */
public interface TenantRepository {

    Optional<TenantRecord> findBySlug(String slug);
}
