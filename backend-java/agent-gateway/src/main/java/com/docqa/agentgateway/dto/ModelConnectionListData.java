package com.docqa.agentgateway.dto;

import java.util.List;

/** 租户内的连接清单；生效与否由每项的 active 表达，不另设「当前连接」字段。 */
public record ModelConnectionListData(List<ModelConnection> items) {
}
