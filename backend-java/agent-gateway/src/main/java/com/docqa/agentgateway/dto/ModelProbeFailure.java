package com.docqa.agentgateway.dto;

/** 探测失败原因；与 AgentFlow 的 CONN-* 语义对齐，但用 DocQA 自己的枚举名。 */
public enum ModelProbeFailure {
    /** 端点返回 4xx：多半是 Base URL 路径或 API Key 填错。 */
    HTTP_4XX,
    /** 端点返回 5xx：对端暂不可用。 */
    HTTP_5XX,
    /** 连接建立不上（拒绝、DNS 失败、网络不可达）。 */
    UNREACHABLE,
    /** 超过探测超时。 */
    TIMEOUT,
    /** 通了但不是 OpenAI 兼容形态（/models 没给出模型列表）。 */
    NOT_OPENAI_COMPATIBLE,
    /** 出网被白名单或私网判定拦下。 */
    EGRESS_BLOCKED,
    /** 这条连接还没录入 API Key。 */
    CREDENTIAL_MISSING,
    /** 密文解不开（主密钥轮换后旧密钥未保留、或行被搬动过）：需要重新录入 Key。 */
    CREDENTIAL_UNREADABLE
}
