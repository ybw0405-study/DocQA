package com.docqa.audit.repository;

import com.docqa.audit.domain.AuditEventRow;
import java.util.List;
import java.util.UUID;

public interface AuditEventRepository {

    void insert(UUID tenantId, AuditEventRow event);

    List<AuditEventRow> findByTraceId(UUID tenantId, String traceId, int limit);
}
