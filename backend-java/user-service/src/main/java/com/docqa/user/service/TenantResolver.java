package com.docqa.user.service;

import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.common.tenant.TenantContext;
import com.docqa.user.domain.TenantRecord;
import com.docqa.user.repository.TenantRepository;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 登录/注册的租户解析入口（P1）。
 *
 * <p>安全边界：tenant 身份只允许在这两个「尚无 JWT」的入口由请求体显式给出；
 * 一旦令牌签发，后续所有请求的 tenantId 一律取自已验签的 {@code tenant_id} claim，
 * Header / Body 里的租户字段不参与任何授权判断（见 TenantContext 的约定）。
 *
 * <p>兼容性：slug 缺省时回落到 V10 回填的默认租户，保证 P0 既有客户端与存量账号
 * 不需改动即可继续登录。显式给出 slug 时，未知或非可登录状态一律返回与「口令错误」
 * 相同的通用 401，避免把租户清单变成可枚举接口。
 */
@Service
public class TenantResolver {

    /** 允许登录的租户状态；其余（PAST_DUE / SUSPENDED / DELETED）一律拒绝。 */
    private static final Set<String> LOGIN_ALLOWED_STATUSES = Set.of("ACTIVE", "TRIAL");

    private static final String DEFAULT_SLUG = "default";

    private final TenantRepository tenantRepository;

    public TenantResolver(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    /**
     * @param tenantSlug 可选；null / 空白 / "default" 均解析为默认租户
     * @return 本次会话应写入 JWT 的 tenantId
     * @throws BusinessException 401 通用错误，slug 不存在或租户状态不允许登录
     */
    public UUID resolve(String tenantSlug) {
        if (tenantSlug == null || tenantSlug.isBlank()) {
            return TenantContext.DEFAULT_TENANT_ID;
        }
        String slug = tenantSlug.trim().toLowerCase(Locale.ROOT);
        if (DEFAULT_SLUG.equals(slug)) {
            return TenantContext.DEFAULT_TENANT_ID;
        }
        TenantRecord record = tenantRepository.findBySlug(slug).orElse(null);
        if (record == null || !LOGIN_ALLOWED_STATUSES.contains(record.status())) {
            throw new BusinessException(ErrorCode.USR_AUTH_001);
        }
        return record.id();
    }
}
