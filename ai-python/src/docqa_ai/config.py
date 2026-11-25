from __future__ import annotations

import ipaddress
import re
from typing import Annotated, Literal
from urllib.parse import urlparse

from pydantic import AliasChoices, BaseModel, ConfigDict, Field, SecretStr, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

# offline 空间的表名（迁移 001）。它同时是  启动守卫识别「真实向量被写进了 offline 空间」的判据。
OFFLINE_VECTOR_TABLE = "chunk_embedding_1024"

McpToolName = Annotated[str, Field(pattern=r"^[a-z0-9_]{1,32}$")]
McpEnvName = Annotated[str, Field(pattern=r"^[A-Z][A-Z0-9_]{0,63}$")]
_HOST_ENTRY = re.compile(
    r"^(?=.{1,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)*"
    r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$"
)


class McpServerSettings(BaseModel):
    """一条 Server 配置要么是远程 HTTP，要么是本地 stdio，二者互斥。"""

    model_config = ConfigDict(extra="forbid", populate_by_name=True, frozen=True)

    name: str = Field(pattern=r"^[a-z0-9_]{1,32}$")
    transport: Literal["http", "stdio"] = "http"
    url: str = Field(default="", max_length=2048)
    # stdio：命令必须是已预置的绝对路径可执行文件，运行期不做 npx/uvx/pip 安装。
    command: str = Field(default="", max_length=1024)
    args: list[str] = Field(default_factory=list, max_length=32)
    env: list[McpEnvName] = Field(default_factory=list, max_length=32)
    cwd: str = Field(default="", max_length=1024)
    allowed_tools: list[McpToolName] = Field(
        default_factory=list,
        max_length=32,
        validation_alias=AliasChoices("allowedTools", "allowed_tools"),
    )
    # 只存环境变量名，令牌本身不进配置、不进日志、不进 redacted_summary。
    token_env: str = Field(
        default="",
        pattern=r"^$|^[A-Z][A-Z0-9_]{0,63}$",
        validation_alias=AliasChoices("tokenEnv", "token_env"),
    )

    @model_validator(mode="after")
    def validate_transport(self) -> McpServerSettings:
        if self.transport == "http":
            if not self.url:
                raise ValueError("url is required when transport=http")
            if self.command or self.args or self.env or self.cwd:
                raise ValueError("command/args/env/cwd are only valid when transport=stdio")
        else:
            if not self.command:
                raise ValueError("command is required when transport=stdio")
            if self.url:
                raise ValueError("url is only valid when transport=http")
            if self.token_env:
                raise ValueError("token_env is only valid when transport=http")
            if any("\x00" in arg for arg in self.args):
                raise ValueError("mcp stdio argument is invalid")
        return self


class Settings(BaseSettings):
    """Runtime configuration. Secrets are read from environment variables only."""

    model_config = SettingsConfigDict(
        env_prefix="DOCQA_AI_",
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
        # 声明了 validation_alias 的字段若不开这项，关键字传参会被静默丢弃，
        # 测试里的 pg_dsn="" 实际读到的仍是环境变量中的真实 DSN。
        populate_by_name=True,
    )

    service_name: str = "docqa-ai"
    environment: Literal["dev", "test", "prod"] = "dev"
    log_level: str = "INFO"
    # 默认只监听 loopback：Python 数据面不对公网暴露，需要跨主机时由部署显式放宽。
    api_host: str = "127.0.0.1"
    # 显式 validation_alias 不受 env_prefix 影响，这里必须写全名：配置契约用的是 DOCQA_AI_PORT
    # （网关的 DOCQA_PYTHON_BASE_URL 也指向同一端口），漏掉别名时字段只认 DOCQA_AI_API_PORT，
    # 数据面就永远绑 8000，网关按 18000 调用恒定 connection refused。
    api_port: int = Field(default=8000, ge=1, le=65535, validation_alias="DOCQA_AI_PORT")
    # 未配内部凭据时是否允许匿名访问数据面。默认拒绝；仅在 loopback 监听时
    # 可被部署显式开启用于本地联调。跨主机 + 无凭据的组合会在启动时被拒绝。
    allow_anonymous_api: bool = False

    redis_url: str = Field(
        default="redis://127.0.0.1:6379/0",
        validation_alias=AliasChoices("DOCQA_AI_REDIS_URL", "DOCQA_REDIS_URL"),
    )
    redis_socket_timeout_ms: int = Field(default=1000, ge=100, le=10000)
    kill_switch_key: str = "agent:kill-switch"
    kill_switch_cache_ms: int = Field(default=5000, ge=0, le=30000)
    budget_key_prefix: str = "agent:budget:"

    low_confidence_threshold: float = Field(default=0.55, ge=0.0, le=1.0)
    max_citations: int = Field(default=20, ge=1, le=20)
    retrieval_top_k: int = Field(default=6, ge=1, le=20)
    retrieval_min_score: float = Field(default=0.0, ge=0.0, le=1.0)
    lexical_recall_enabled: bool = True
    retrieval_lexical_candidate_limit: int = Field(default=50, ge=1, le=500)
    fusion_rrf_k: int = Field(default=60, ge=1, le=1000)
    lexical_min_query_chars: int = Field(default=2, ge=1, le=16)
    history_max_tokens: int = Field(default=2000, ge=1, le=8000)
    max_prompt_chars: int = Field(default=32000, ge=1000, le=100000)
    max_observation_chars: int = Field(default=6000, ge=1000, le=20000)

    # MCP 默认关闭。关闭时不构造任何客户端 ⇒ 结构上零外呼。
    mcp_enabled: bool = False
    mcp_servers: list[McpServerSettings] = Field(default_factory=list, max_length=8)
    mcp_call_timeout_ms: int = Field(default=3000, ge=100, le=15000)
    mcp_discovery_ttl_seconds: int = Field(default=300, ge=1, le=86400)
    mcp_max_response_bytes: int = Field(default=262144, ge=1024, le=1048576)
    mcp_max_tools: int = Field(default=20, ge=0, le=100)
    # 每用户每分钟工具调用上限（Redis 固定窗口）。
    tool_rate_limit_enabled: bool = True
    tool_rate_limit_per_minute: int = Field(default=20, ge=1, le=1000)
    # stdio 进程治理。启动超时与调用超时分开，冷启动不计入工具预算。
    mcp_startup_timeout_ms: int = Field(default=5000, ge=100, le=60000)
    mcp_max_concurrency: int = Field(default=4, ge=1, le=32)
    mcp_failure_threshold: int = Field(default=3, ge=1, le=20)
    mcp_circuit_open_seconds: int = Field(default=60, ge=1, le=3600)

    # 真实联网工具默认关闭。关闭时四个工具都不注册，客户端也不构造 ⇒ 结构上零外呼。
    real_tools_enabled: bool = False
    web_search_provider: Literal["none", "searxng", "tavily"] = "none"
    searxng_url: str = Field(default="http://127.0.0.1:8888", max_length=2048)
    # 只存环境变量名，令牌本身不进配置、不进日志（与 mcp 的 token_env 同形）。
    tavily_key_env: str = Field(default="TAVILY_API_KEY", pattern=r"^[A-Z][A-Z0-9_]{0,63}$")
    web_fetch_max_bytes: int = Field(default=524288, ge=1024, le=10485760)
    web_fetch_timeout_ms: int = Field(default=5000, ge=100, le=20000)
    # 空 = 允许任意公网主机（仍做 SSRF 校验）；非空时主机必须等于条目或以 `.{条目}` 结尾。
    web_fetch_allowed_hosts: list[str] = Field(default_factory=list, max_length=32)
    web_tool_max_chars: int = Field(default=4000, ge=200, le=20000)
    web_tool_max_results: int = Field(default=5, ge=1, le=10)
    web_robots_cache_seconds: int = Field(default=3600, ge=0, le=86400)
    academic_mailto: str = Field(default="", max_length=254)
    #  只开放中英文两个语言面，避免把模型输入直接拼进任意子域。
    wikipedia_language: Literal["zh", "en"] = "zh"

    embedding_provider: Literal["offline", "openai"] = "offline"
    embedding_model: str = Field(default="offline-hashing-1024", min_length=1, max_length=64)
    embedding_dimensions: int = Field(default=1024, ge=1, le=4096)
    embedding_base_url: str = ""
    embedding_api_key: SecretStr = SecretStr("")
    embedding_timeout_ms: int = Field(default=10000, ge=100, le=60000)

    llm_provider: Literal["offline", "openai"] = "offline"
    llm_model: str = Field(default="offline-extractive", min_length=1, max_length=64)
    llm_base_url: str = ""
    llm_api_key: SecretStr = SecretStr("")
    llm_timeout_ms: int = Field(default=10000, ge=100, le=30000)
    llm_max_output_tokens: int = Field(default=1024, ge=16, le=4096)
    # 工具调用协议：json=提示词约束的 JSON action（默认，行为不变）；
    # native=OpenAI 兼容 function tools（tool_calls），终答仍走 JSON 以保留引用治理。
    tool_protocol: Literal["json", "native"] = "json"

    # 界面配置的模型连接（契约 2.7.0）。关闭时行为与改动前逐字节一致：只用上面的 env provider。
    # 打开后按请求从 Redis 取生效连接的**密文**，本地解密后构造 planner；
    # 键不存在则回退 env provider，密文解不开则硬失败，绝不静默降级。
    model_connections_enabled: bool = False
    model_connection_key_prefix: str = "agent:model-connection:tenant:"
    model_connection_cache_ms: int = Field(default=5000, ge=0, le=30000)
    # 与网关读同一个变量名：两侧必须是同一把主密钥，否则密文解不开。
    # 用 SecretStr 保证它不进日志与 redacted_summary。
    cred_master_key: SecretStr = Field(
        default=SecretStr(""),
        validation_alias=AliasChoices("DOCQA_CRED_MASTER_KEY"),
    )
    # 轮换前的旧主密钥，逗号分隔（与 Java 侧同一取值形态）；只用于解封历史密文。
    cred_master_key_previous: str = Field(
        default="",
        validation_alias=AliasChoices("DOCQA_CRED_MASTER_KEY_PREVIOUS"),
    )
    # 出网白名单：逗号分隔的主机后缀，空 = 放行任意公网主机。刻意用 str 而不是 list[str]——
    # pydantic-settings 对 list 按 JSON 解析，而网关侧是逗号分隔，同一变量不能要求两种格式。
    llm_allowed_hosts: str = Field(
        default="",
        validation_alias=AliasChoices("DOCQA_LLM_ALLOWED_HOSTS"),
    )
    llm_allow_private: bool = Field(
        default=False,
        validation_alias=AliasChoices("DOCQA_LLM_ALLOW_PRIVATE"),
    )

    # rerank 的能力面与 embedding / 生成不同：实测它不在 OpenAI 兼容面上，
    # 请求与响应都是原生形态，因此取值命名为 dashscope 而不是 openai。
    rerank_provider: Literal["offline", "dashscope"] = "offline"
    rerank_model: str = Field(default="offline-noop", min_length=1, max_length=64)
    rerank_base_url: str = ""
    rerank_api_key: SecretStr = SecretStr("")
    rerank_timeout_ms: int = Field(default=3000, ge=100, le=15000)

    # 真实调用额度。默认 0 = 一次都不允许，且下面的启动期守卫会拒绝
    # 「Provider 已切真但额度仍为 0」的组合，避免配置遗漏变成静默放行。
    experiment_budget: int = Field(default=0, ge=0, le=100000)
    experiment_token_budget: int = Field(default=0, ge=0, le=20000000)

    pg_dsn: str = Field(
        default="",
        validation_alias=AliasChoices("DOCQA_AI_PG_DSN", "DOCQA_PG_DSN"),
    )
    pg_table: str = Field(default=OFFLINE_VECTOR_TABLE, pattern=r"^[a-z][a-z0-9_]{0,63}$")
    pg_connect_timeout_s: int = Field(default=3, ge=1, le=30)

    # LangGraph checkpoint 持久化与断点续跑。默认关闭：不构造 saver、不写任何 checkpoint 表，
    # 行为与改动前逐字节一致。checkpoint_dsn 为空时回退 pg_dsn（同库 public schema，不新建库）。
    checkpoint_enabled: bool = False
    checkpoint_dsn: str = ""
    # 人在回路：高风险工具执行前 interrupt 挂起，等 ask:resume 审批续跑。
    # 依赖 checkpoint 持久化挂起态，未开 checkpoint 时启动期拒绝。
    tool_approval_enabled: bool = False
    high_risk_tools: str = ""

    # 长期记忆（显式「记住：…」指令 + 管理页）。默认关闭：不构造 store、不写任何记忆表，
    # 问答路径与改动前逐字节一致。memory_dsn 为空时回退 pg_dsn（同库 public schema）。
    memory_enabled: bool = False
    memory_dsn: str = ""
    memory_max_chars: int = Field(default=800, ge=100, le=4000)

    mq_enabled: bool = False
    rocketmq_namesrv: str = Field(
        default="127.0.0.1:9876",
        validation_alias=AliasChoices("DOCQA_AI_ROCKETMQ_NAMESRV", "DOCQA_ROCKETMQ_NAMESRV"),
    )
    parse_consumer_group: str = "ai-python-parse-worker"
    parse_producer_group: str = "ai-python-parse-producer"
    vector_consumer_group: str = "ai-python-vector-worker"
    vector_producer_group: str = "ai-python-vector-producer"
    audit_producer_group: str = "ai-python-audit-fallback"
    parse_requested_topic: str = "parse-requested"
    parse_completed_topic: str = "parse-completed"
    parse_failed_topic: str = "parse-failed"
    vector_requested_topic: str = "vector-change-requested"
    vector_completed_topic: str = "vector-change-completed"
    audit_topic: str = "audit-event"
    mq_idempotency_ttl_seconds: int = Field(default=604800, ge=60)
    mq_send_timeout_ms: int = Field(default=3000, ge=100, le=30000)

    minio_enabled: bool = False
    minio_endpoint: str = Field(
        default="http://127.0.0.1:9000",
        validation_alias=AliasChoices("DOCQA_AI_MINIO_ENDPOINT", "DOCQA_MINIO_ENDPOINT"),
    )
    minio_access_key: SecretStr = Field(
        default=SecretStr(""),
        validation_alias=AliasChoices("DOCQA_AI_MINIO_ACCESS_KEY", "DOCQA_MINIO_ACCESS_KEY"),
    )
    minio_secret_key: SecretStr = Field(
        default=SecretStr(""),
        validation_alias=AliasChoices("DOCQA_AI_MINIO_SECRET_KEY", "DOCQA_MINIO_SECRET_KEY"),
    )
    minio_bucket: str = Field(
        default="docqa-documents",
        validation_alias=AliasChoices("DOCQA_AI_MINIO_BUCKET", "DOCQA_MINIO_BUCKET"),
    )
    minio_secure: bool | None = None
    worker_max_file_bytes: int = Field(default=52428800, ge=1024, le=524288000)
    parse_max_extracted_chars: int = Field(default=2_000_000, ge=10_000, le=20_000_000)
    parse_processing_ttl_seconds: int = Field(default=900, ge=60, le=86400)
    vector_processing_ttl_seconds: int = Field(default=120, ge=10, le=3600)

    chunk_size_chars: int = Field(default=800, ge=100, le=4000)
    chunk_overlap_chars: int = Field(default=100, ge=0, le=1000)
    embedding_batch_size: int = Field(default=32, ge=1, le=128)

    otel_enabled: bool = False
    otel_endpoint: str = ""
    otel_sample_ratio: float = Field(default=1.0, ge=0.0, le=1.0)

    # LangFuse v3 自托管追踪：默认关闭时不构造第二路导出，行为与改动前逐字节一致。
    # 打开后在既有 OTLP processor 之后追加一个独立的 BatchSpanProcessor，同 spans 同 sampler。
    langfuse_tracing_enabled: bool = False
    langfuse_otlp_endpoint: str = "http://127.0.0.1:13000/api/public/otel/v1/traces"
    # 与 compose 首启 bootstrap 的项目密钥对同一取值；SecretStr 保证不进日志与 redacted_summary。
    langfuse_public_key: SecretStr = SecretStr("")
    langfuse_secret_key: SecretStr = SecretStr("")
    # 非回环 endpoint 需要显式放行（照 llm_allow_private 的口径）；生产保持 false。
    langfuse_allow_private: bool = False

    # §4.2 的 9 项 Python 侧业务指标经独立端口的 /metrics 以 Prometheus 文本格式导出。
    # 默认关闭（生产默认 metrics_exposed=False 语义不变）：打开时 main() 才在
    # metrics_host:metrics_port（默认 loopback 9090）起一个只含 /metrics 的最小 app，
    # 主业务端口不再注册任何指标端点。
    metrics_exposed: bool = False
    metrics_host: str = "127.0.0.1"
    metrics_port: int = Field(default=9090, ge=1, le=65535)

    # 方案 4.2 步 3：内部鉴权升级为 HMAC 签名请求（时间戳 + body）。
    # 非空时以签名为唯一凭据，静态 X-Internal-Token 头不再单独放行；空时保留上一轮的
    # 静态 token 兼容期（Java WebClient 尚未接入签名，属跨服务业务边界，本轮不硬改）。
    # 生产 fail-fast 仍只校验 internal_token，语义不变。
    internal_hmac_secret: SecretStr = SecretStr("")

    # 网关 → 数据面共享密钥（跨项目协调：Java WebClient 在请求里带同名 X-Internal-Token 头）。
    # 空串 = 不启用校验，仅用于显式 dev/local/test 环境；一旦配置，除探针外缺失/错误头一律 401。
    # 生产判据默认最严（只有显式 dev/local/test 才放宽），生产且 token 为空时
    # create_app 的 lifespan 与 main() 都会 fail-fast 拒绝启动。
    # 用 SecretStr 避免出现在日志与 redacted_summary。
    internal_token: SecretStr = SecretStr("")

    @model_validator(mode="after")
    def validate_consistency(self) -> Settings:
        if self.chunk_overlap_chars >= self.chunk_size_chars:
            raise ValueError("chunk_overlap_chars must be smaller than chunk_size_chars")
        if self.retrieval_lexical_candidate_limit < self.retrieval_top_k:
            raise ValueError("retrieval_lexical_candidate_limit must not be smaller than retrieval_top_k")
        if self.embedding_dimensions != 1024:
            raise ValueError("this release only supports 1024-dimension embeddings")
        if self.embedding_provider == "openai" and not self.embedding_base_url:
            raise ValueError("embedding_base_url is required when embedding_provider=openai")
        if self.llm_provider == "openai" and not self.llm_base_url:
            raise ValueError("llm_base_url is required when llm_provider=openai")
        if self.rerank_provider == "dashscope" and not self.rerank_base_url:
            raise ValueError("rerank_base_url is required when rerank_provider=dashscope")
        if self.rerank_provider == "dashscope" and not self.rerank_api_key.get_secret_value():
            raise ValueError("rerank_api_key is required when rerank_provider=dashscope")
        real_provider_enabled = (
            self.embedding_provider == "openai" or self.llm_provider == "openai" or self.rerank_provider == "dashscope"
        )
        # 界面连接同样会真实外呼：开关打开时即使 env 全 offline 也必须先有额度，
        # 否则「界面配了真模型、账本根本没构造」就成了不受额度约束的裸奔出口。
        if (real_provider_enabled or self.model_connections_enabled) and (
            self.experiment_budget == 0 or self.experiment_token_budget == 0
        ):
            raise ValueError(
                "experiment_budget and experiment_token_budget must be positive when a real provider is enabled"
            )
        if self.model_connections_enabled and not self.cred_master_key.get_secret_value():
            raise ValueError("DOCQA_CRED_MASTER_KEY is required when model_connections_enabled=true")
        for host in self.llm_allowed_host_entries:
            if not _HOST_ENTRY.fullmatch(host):
                raise ValueError("llm_allowed_hosts entries must be bare hostnames")
        # 向量空间守卫。放在额度守卫之后——先问「这次能不能花钱」，再问「花完写到哪张表」。
        # 真实 embedding 写进 offline 表是**无信号**的（`metadata->>'embedding_model'` 没有任何读取点，
        # HNSW 按 vector_cosine_ops 算距离、不知道向量来自哪个模型），所以只能在启动期拦住。
        # 名字与模型是否匹配归运维面，本守卫只消掉最容易踩的那一种组合。
        if self.embedding_provider != "offline" and self.pg_table == OFFLINE_VECTOR_TABLE:
            raise ValueError(
                "pg_table must point at a dedicated vector space when embedding_provider is not offline; "
                f"{OFFLINE_VECTOR_TABLE} holds offline-hashing vectors and is not comparable"
            )
        if self.checkpoint_enabled and not (self.checkpoint_dsn or self.pg_dsn):
            raise ValueError("checkpoint_dsn or pg_dsn is required when checkpoint_enabled=true")
        if self.tool_approval_enabled and not self.checkpoint_enabled:
            raise ValueError("checkpoint_enabled=true is required when tool_approval_enabled=true")
        if self.memory_enabled and not (self.memory_dsn or self.pg_dsn):
            raise ValueError("memory_dsn or pg_dsn is required when memory_enabled=true")
        if self.minio_secure is None:
            self.minio_secure = self.minio_endpoint.lower().startswith("https://")
        if self.mq_enabled and not self.rocketmq_namesrv:
            raise ValueError("rocketmq_namesrv is required when mq_enabled=true")
        mcp_names = [server.name for server in self.mcp_servers]
        if len(mcp_names) != len(set(mcp_names)):
            raise ValueError("mcp_servers names must be unique")
        if self.web_search_provider == "searxng":
            parsed = urlparse(self.searxng_url)
            if parsed.scheme not in ("http", "https") or not parsed.hostname:
                raise ValueError("searxng_url must be an absolute http/https URL")
            if parsed.username or parsed.password:
                raise ValueError("searxng_url must not embed credentials")
        for host in self.web_fetch_allowed_hosts:
            if not _HOST_ENTRY.fullmatch(host):
                raise ValueError("web_fetch_allowed_hosts entries must be bare hostnames")
        # LangFuse 导出守卫：启用而无密钥会在运行期恒 401、追踪静默缺失，所以压回启动期。
        if self.langfuse_tracing_enabled:
            if not self.langfuse_public_key.get_secret_value() or not self.langfuse_secret_key.get_secret_value():
                raise ValueError(
                    "langfuse_public_key and langfuse_secret_key are required when langfuse_tracing_enabled=true"
                )
            parsed = urlparse(self.langfuse_otlp_endpoint)
            if parsed.scheme not in ("http", "https") or not parsed.hostname:
                raise ValueError("langfuse_otlp_endpoint must be an absolute http/https URL")
            host = parsed.hostname.lower()
            try:
                loopback = ipaddress.ip_address(host).is_loopback
            except ValueError:
                loopback = host == "localhost"
            if not loopback and not self.langfuse_allow_private:
                raise ValueError("non-loopback langfuse_otlp_endpoint requires langfuse_allow_private=true")
        return self

    @property
    def minio_secure_effective(self) -> bool:
        return bool(self.minio_secure)

    @property
    def llm_allowed_host_entries(self) -> tuple[str, ...]:
        """逗号分隔白名单的解析结果；空串表示不设白名单（仍拒私网）。"""
        return tuple(entry.strip().lower() for entry in self.llm_allowed_hosts.split(",") if entry.strip())

    @property
    def cred_previous_key_entries(self) -> tuple[str, ...]:
        return tuple(entry.strip() for entry in self.cred_master_key_previous.split(",") if entry.strip())

    def redacted_summary(self) -> dict[str, object]:
        """Safe to log at startup: never includes secrets or DSNs."""

        return {
            "environment": self.environment,
            "embedding_provider": self.embedding_provider,
            "embedding_model": self.embedding_model,
            "llm_provider": self.llm_provider,
            "llm_model": self.llm_model,
            "model_connections_enabled": self.model_connections_enabled,
            "cred_master_key_configured": bool(self.cred_master_key.get_secret_value()),
            "llm_allowed_host_count": len(self.llm_allowed_host_entries),
            "llm_allow_private": self.llm_allow_private,
            "rerank_provider": self.rerank_provider,
            "rerank_model": self.rerank_model,
            "rerank_timeout_ms": self.rerank_timeout_ms,
            "rerank_credentials_configured": bool(self.rerank_api_key.get_secret_value()),
            "experiment_budget": self.experiment_budget,
            "experiment_token_budget": self.experiment_token_budget,
            "mq_enabled": self.mq_enabled,
            "minio_enabled": self.minio_enabled,
            "mcp_enabled": self.mcp_enabled,
            "mcp_server_count": len(self.mcp_servers),
            "real_tools_enabled": self.real_tools_enabled,
            "web_search_provider": self.web_search_provider,
            "web_fetch_allowed_host_count": len(self.web_fetch_allowed_hosts),
            "web_tool_max_results": self.web_tool_max_results,
            "tool_rate_limit_enabled": self.tool_rate_limit_enabled,
            "tool_rate_limit_per_minute": self.tool_rate_limit_per_minute,
            "retrieval_top_k": self.retrieval_top_k,
            "lexical_recall_enabled": self.lexical_recall_enabled,
            "retrieval_lexical_candidate_limit": self.retrieval_lexical_candidate_limit,
            "fusion_rrf_k": self.fusion_rrf_k,
            "lexical_min_query_chars": self.lexical_min_query_chars,
            "otel_enabled": self.otel_enabled,
            "langfuse_tracing_enabled": self.langfuse_tracing_enabled,
            # 只暴露主机名：完整 endpoint 可能带路径/查询参数，密钥一律不进摘要。
            "langfuse_otlp_host": urlparse(self.langfuse_otlp_endpoint).hostname or "",
            "pg_configured": bool(self.pg_dsn),
            "pg_table": self.pg_table,
            "checkpoint_enabled": self.checkpoint_enabled,
            "checkpoint_dsn_configured": bool(self.checkpoint_dsn),
            "memory_enabled": self.memory_enabled,
            "memory_dsn_configured": bool(self.memory_dsn),
            "internal_token_configured": bool(self.internal_token.get_secret_value()),
        }
