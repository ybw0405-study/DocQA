package com.docqa.common.config;

import com.docqa.common.db.AuditAppendOnlyGuard;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 审计只追加自检的装配点。
 *
 * <p>只按「属性存在」激活（{@code docqa.audit.append-only-guard}），不用默认开启：
 * 五个服务里只有 agent-gateway 与 audit-service 会写 {@code audit_event}，把它们之外
 * 的服务也点着，只会在启动日志里多出三条和自身无关的告警，久而久之所有人都会忽略这条日志。</p>
 */
@AutoConfiguration
@ConditionalOnClass(name = "org.springframework.jdbc.core.JdbcTemplate")
@ConditionalOnProperty(prefix = "docqa.audit", name = "append-only-guard")
public class AuditAppendOnlyAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AuditAppendOnlyGuard auditAppendOnlyGuard(
            JdbcTemplate jdbcTemplate,
            @Value("${docqa.audit.append-only-guard:enforce}") String mode) {
        return new AuditAppendOnlyGuard(jdbcTemplate, AuditAppendOnlyGuard.Mode.parse(mode));
    }
}
