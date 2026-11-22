package com.docqa.agentgateway.dto;

import java.util.List;

/**
 * 厂商预设：公开可查的端点地址与常见模型别名，**不含任何密钥**。
 *
 * <p>为什么由后端下发而不是写死在前端：前端红线禁止生产源码出现任何绝对服务地址
 * （见 frontend-vue/tests/redlines.spec.ts），厂商端点属于同一类字面量；由服务端下发
 * 既守住红线，也让换端点不必发前端版本。端点与模型名在向导里都可编辑，服务商控制台才是最终依据。
 */
public record ModelPreset(
        String id,
        String vendor,
        String mark,
        String label,
        String model,
        List<String> models,
        String baseUrl,
        String keyUrl,
        String keyHint,
        String group,
        String tag,
        String note,
        boolean noKey,
        boolean needsServerOptIn) {
}
