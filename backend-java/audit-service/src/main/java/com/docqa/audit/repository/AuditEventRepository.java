package com.docqa.audit.repository;

import com.docqa.audit.domain.AuditEventRow;
import java.util.List;

public interface AuditEventRepository {

    void insert(AuditEventRow event);

    List<AuditEventRow> findByTraceId(String traceId, int limit);
}
