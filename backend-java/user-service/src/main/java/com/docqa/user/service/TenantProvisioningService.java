package com.docqa.user.service;

import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.user.config.TenantBootstrapProperties;
import com.docqa.user.domain.TenantRecord;
import com.docqa.user.dto.CreateTenantRequest;
import com.docqa.user.dto.TenantCreatedData;
import com.docqa.user.repository.TenantRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 租户供给：在引导阶段创建真实租户，供登录/注册按 slug 归属。
 *
 * <p>访问控制用共享令牌而非 JWT：此时还没有任何租户管理员身份可依赖，引入平台管理员角色
 * 又要改 JWT claim 与三服务守卫，超出引导入口的必要范围。令牌留空时端点整体禁用。
 *
 * <p>幂等：相同 slug 重复供给返回既有租户，避免运维重试因唯一键冲突拿到 500。
 */
@Service
public class TenantProvisioningService {

    private static final String ACTIVE = "ACTIVE";

    private final TenantRepository tenantRepository;
    private final TenantBootstrapProperties properties;

    public TenantProvisioningService(
            TenantRepository tenantRepository,
            TenantBootstrapProperties properties) {
        this.tenantRepository = tenantRepository;
        this.properties = properties;
    }

    /**
     * @param suppliedToken 请求头携带的令牌；为 null 时按无权限处理
     * @return 新建或既有的租户
     * @throws BusinessException 令牌未配置或不符时 403
     */
    public TenantCreatedData create(String suppliedToken, CreateTenantRequest request) {
        requireToken(suppliedToken);
        String slug = request.slug().trim().toLowerCase(Locale.ROOT);
        UUID id = UUID.randomUUID();
        int inserted = tenantRepository.insert(
                id, slug, request.name().trim(), request.tier(), request.isolation());
        if (inserted == 1) {
            return new TenantCreatedData(id, slug, ACTIVE);
        }
        // slug 已存在：幂等返回既有记录，不泄露除 slug/id/status 之外的目录信息。
        TenantRecord existing = tenantRepository.findBySlug(slug).orElseThrow(() ->
                new BusinessException(ErrorCode.COM_INTERNAL_001, "租户写入后回读失败"));
        return new TenantCreatedData(existing.id(), existing.slug(), existing.status());
    }

    private void requireToken(String suppliedToken) {
        if (!properties.isEnabled()) {
            throw new BusinessException(ErrorCode.USR_FORBIDDEN_001, "租户供给端点未启用");
        }
        byte[] expected = properties.getToken().getBytes(StandardCharsets.UTF_8);
        byte[] actual = suppliedToken == null
                ? new byte[0]
                : suppliedToken.getBytes(StandardCharsets.UTF_8);
        // 常量时间比较，避免用响应耗时逐字符猜解共享令牌。
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new BusinessException(ErrorCode.USR_FORBIDDEN_001, "租户供给令牌无效");
        }
    }
}
