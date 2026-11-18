package com.docqa.agentgateway.repository;

import com.docqa.agentgateway.dto.AuditEventItem;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 管理面审计查询的只读仓库；只提供 SELECT，不提供任何写入能力。 */
public interface AdminAuditQueryRepository {

    List<AuditEventItem> findPage(
            UUID tenantId,
            AuditQueryFilter filter,
            Instant beforeOccurredAt,
            UUID beforeEventId,
            int limit);
}
