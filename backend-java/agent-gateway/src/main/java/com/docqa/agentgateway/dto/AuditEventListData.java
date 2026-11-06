package com.docqa.agentgateway.dto;

import java.util.List;

/** 审计查询的 keyset 分页信封；与其他列表一致，有意不返回 total。 */
public record AuditEventListData(List<AuditEventItem> items, String nextCursor, int limit) {
}
