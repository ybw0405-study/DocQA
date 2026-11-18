package com.docqa.agentgateway.repository;

import java.util.UUID;

/** 管理动作审计只允许 INSERT；与 audit-service 的消费链路并行，不依赖 MQ。 */
public interface AdminAuditRepository {

    void insert(UUID tenantId, AdminAuditEntry entry);
}
