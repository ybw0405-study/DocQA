package com.docqa.agentgateway.service;

import com.docqa.agentgateway.dto.ModelPreset;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 厂商预设目录：公开可查的 OpenAI 兼容端点与常见模型别名，**不含任何密钥**。
 *
 * <p>由服务端下发而不是写死在前端，原因有二：前端红线禁止生产源码出现绝对服务地址；
 * 端点与模型别名会随厂商版本变化，服务端更新即可，不必发前端版本。
 * 向导里端点与模型名都可编辑，服务商控制台才是最终依据。
 *
 * <p>目录是不可变静态数据，不读配置、不出网、不碰凭据。
 */
@Component
public class ModelPresetCatalog {

    private static final List<ModelPreset> PRESETS = List.of(
            new ModelPreset(
                    "deepseek", "深度求索", "DS", "DeepSeek",
                    "deepseek-chat", List.of("deepseek-chat", "deepseek-reasoner"),
                    "https://api.deepseek.com/v1",
                    "https://platform.deepseek.com", "DeepSeek 开放平台创建 API Key",
                    "recommended", "热门",
                    "reasoner 为思考模式；别名会随版本升级，以控制台为准",
                    false, false),
            new ModelPreset(
                    "zhipu-glm", "智谱 AI", "G", "智谱 GLM",
                    "glm-4.6", List.of("glm-4.6", "glm-4.5", "glm-4.5-air"),
                    "https://open.bigmodel.cn/api/paas/v4",
                    "https://open.bigmodel.cn", "智谱开放平台获取 API Key",
                    "recommended", "热门",
                    "路径是 /api/paas/v4，不是 /v1，最容易填错的一处",
                    false, false),
            new ModelPreset(
                    "moonshot-kimi", "月之暗面", "K", "Kimi",
                    "moonshot-v1-8k", List.of("moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k"),
                    "https://api.moonshot.cn/v1",
                    "https://platform.moonshot.cn", "Moonshot 开放平台创建 API Key",
                    "recommended", "长上下文",
                    "长文档与资料整理常用，模型名以控制台为准",
                    false, false),
            new ModelPreset(
                    "qwen", "阿里云百炼", "Q", "通义千问",
                    "qwen-plus", List.of("qwen-plus", "qwen-max", "qwen-turbo"),
                    "https://dashscope.aliyuncs.com/compatible-mode/v1",
                    "https://bailian.console.aliyun.com", "百炼控制台创建 API Key",
                    "recommended", "推荐",
                    "必须带 /compatible-mode/v1，原生 DashScope 路径不兼容",
                    false, false),
            new ModelPreset(
                    "hunyuan", "腾讯混元", "HY", "混元",
                    "hunyuan-turbos-latest", List.of("hunyuan-turbos-latest", "hunyuan-lite"),
                    "https://api.hunyuan.cloud.tencent.com/v1",
                    "https://cloud.tencent.com/product/hunyuan", "腾讯云控制台创建 API Key",
                    "domestic", null,
                    "OpenAI 兼容接口，模型名以控制台为准",
                    false, false),
            new ModelPreset(
                    "volcengine-doubao", "火山方舟", "D", "豆包",
                    "doubao-pro-32k", List.of("doubao-pro-32k", "doubao-lite-32k"),
                    "https://ark.cn-beijing.volces.com/api/v3",
                    "https://console.volcengine.com/ark", "火山方舟控制台创建 API Key",
                    "domestic", null,
                    "生产环境通常填控制台的推理接入点 ID（ep- 开头）而非模型名",
                    false, false),
            new ModelPreset(
                    "minimax", "MiniMax", "M", "MiniMax",
                    "MiniMax-Text-01", List.of("MiniMax-Text-01", "abab6.5s-chat"),
                    "https://api.minimaxi.com/v1",
                    "https://platform.minimaxi.com", "MiniMax 开放平台创建 API Key",
                    "domestic", null,
                    "模型名区分大小写，以控制台显示为准",
                    false, false),
            new ModelPreset(
                    "siliconflow", "硅基流动", "SF", "硅基流动",
                    "deepseek-ai/DeepSeek-V3", List.of("deepseek-ai/DeepSeek-V3", "Qwen/Qwen2.5-7B-Instruct"),
                    "https://api.siliconflow.cn/v1",
                    "https://cloud.siliconflow.cn", "硅基流动控制台创建 API Key",
                    "domestic", "聚合平台",
                    "一个 Key 调用多家开源模型，模型名带命名空间",
                    false, false),
            new ModelPreset(
                    "openai", "OpenAI", "O", "OpenAI",
                    "gpt-4o-mini", List.of("gpt-4o-mini", "gpt-4o"),
                    "https://api.openai.com/v1",
                    "https://platform.openai.com/api-keys", "OpenAI 控制台创建 API Key",
                    "overseas", null,
                    "需要可访问海外网络的出网环境",
                    false, false),
            new ModelPreset(
                    "ollama", "Ollama", "OL", "本地 Ollama",
                    "qwen2.5:7b", List.of("qwen2.5:7b", "llama3.1:8b"),
                    "http://127.0.0.1:11434/v1",
                    "", "本地模型无需 API Key",
                    "local", "需服务端放行",
                    "http + 回环地址默认被出网策略拦截，需服务端打开 DOCQA_LLM_ALLOW_PRIVATE",
                    true, true));

    public List<ModelPreset> presets() {
        return PRESETS;
    }
}
