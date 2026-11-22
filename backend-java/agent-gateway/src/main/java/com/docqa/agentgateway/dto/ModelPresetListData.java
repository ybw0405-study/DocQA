package com.docqa.agentgateway.dto;

import java.util.List;

/** 预设清单；纯静态公开数据，不分页。 */
public record ModelPresetListData(List<ModelPreset> items) {
}
