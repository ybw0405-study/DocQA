package com.docqa.agentgateway.dto;

import java.util.List;

/**
 * 连通性探测结果。**只回模型 id 与失败原因**，凭据在服务端解密后直接用于出网，
 * 任何形式的密钥材料都不进这个响应。
 *
 * <p>探测失败也返回 200：这是「探测这个动作成功了，结论是连不上」，
 * 不是 HTTP 层面的错误；界面据 failureReason 给出可操作的文案。
 */
public record ModelProbeData(
        boolean ok,
        List<String> models,
        ModelProbeFailure failureReason,
        String detail) {

    public static ModelProbeData success(List<String> models) {
        return new ModelProbeData(true, models, null, null);
    }

    public static ModelProbeData failure(ModelProbeFailure reason, String detail) {
        return new ModelProbeData(false, List.of(), reason, detail);
    }
}
