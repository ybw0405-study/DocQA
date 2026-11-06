package com.docqa.agentgateway.dto;

import java.util.List;

public record AiQuotaListData(List<AiQuota> items, String nextCursor, int limit) {
}
