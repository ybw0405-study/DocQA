package com.docqa.agentgateway.dto;

import java.util.List;

/** 单个用户的记忆清单；每用户上限 50 条（数据面淘汰最旧），因此不分页、不返回 total。 */
public record MemoryListData(List<MemoryItem> items) {
}