"""`observability.md` §4.2 中发生在 Python 进程内的 9 项业务指标。

为什么必须自建 Prometheus 端点：LLM 调用、Agent 轮次、token、PII 阻断、检索与向量查询
耗时这九项**只存在于 Python 进程内**，Java 侧即使包一层也看不到真实的分段耗时与 token。
原有实现只有 OTel 仪表（meter 名 `docqa_ai`，序列为 `tool_calls_total` 等），名称与
标签都和 §4.2 不一致，因此 §4.3 的告警表达式在 Prometheus 里没有对应序列。

命名与 Java 侧严格对齐（这是跨语言最关键的一处约定）：

- 计数：Micrometer 会为不以 `_total` 结尾的 Counter 补 `_total`；`prometheus_client` 反之会
  把 `_total` 后缀规范化。两边都写成文档里的完整名（带 `_total`）后，导出结果一致。
- 计时：Java 的 Timer 是「meter 名 + `_seconds`」，Python 的 Histogram 不做后缀改写。
  所以 Python 这边的名字必须**自己带** `_seconds`，两边才对得上。
- `agent_rounds` 是 DistributionSummary：Python 用 Histogram 表达同一语义（`_bucket/_sum/_count`）。

注册表按实例隔离而不是用全局 `REGISTRY`：测试会反复创建应用，全局注册表在第二次
`Counter(...)` 时直接抛 `Duplicated timeseries`，那会让「指标测试」变成「测试顺序敏感」。
"""

from __future__ import annotations

from prometheus_client import CollectorRegistry, Counter, Histogram, generate_latest

# 与 Java 侧 BusinessMetrics 的 SLO 边界保持一致（§4.3 的 8s / 12s 及其两侧）。
_LATENCY_BUCKETS = (0.05, 0.1, 0.2, 0.5, 1.0, 2.0, 4.0, 8.0, 12.0)

# 标签缺省值：与 Java 侧 tag(null) -> "NULL" 对齐，避免同一指标裂成「无标签」与「空标签」。
_NULL = "NULL"


def _tag(value: str | None) -> str:
    return value if value and value.strip() else _NULL


class BusinessMetrics:
    """§4.2 的 Python 侧指标集合。线程安全由 prometheus_client 自身保证。"""

    def __init__(self, registry: CollectorRegistry | None = None) -> None:
        self.registry = registry if registry is not None else CollectorRegistry(auto_describe=True)

        self._llm_calls = Counter(
            "docqa_llm_calls_total",
            "LLM 调用次数",
            ["status", "model", "error_code"],
            registry=self.registry,
        )
        self._llm_latency = Histogram(
            "docqa_llm_latency_seconds",
            "单次 LLM 调用耗时（秒）",
            ["model", "status"],
            buckets=_LATENCY_BUCKETS,
            registry=self.registry,
        )
        # DistributionSummary 的对应物：轮次不是时间，但分布形态一致，用同一套 bucket 表达。
        self._agent_rounds = Histogram(
            "docqa_agent_rounds",
            "每次问答实际使用的 Agent 轮次",
            ["status"],
            buckets=(1, 2, 3, 4, 6, 8, 12),
            registry=self.registry,
        )
        self._agent_tool_calls = Counter(
            "docqa_agent_tool_calls_total",
            "工具调用次数",
            ["tool", "status"],
            registry=self.registry,
        )
        self._agent_tokens = Counter(
            "docqa_agent_tokens_total",
            "LLM token 总量",
            ["direction", "model"],
            registry=self.registry,
        )
        self._agent_budget_exhausted = Counter(
            "docqa_agent_budget_exhausted_total",
            "预算耗尽次数",
            ["reason"],
            registry=self.registry,
        )
        self._rag_retrieval_latency = Histogram(
            "docqa_rag_retrieval_latency_seconds",
            "RAG 检索总耗时（秒）",
            ["result", "index_version"],
            buckets=_LATENCY_BUCKETS,
            registry=self.registry,
        )
        self._vector_search_latency = Histogram(
            "docqa_vector_search_latency_seconds",
            "PGVector 查询耗时（秒）",
            ["collection", "result"],
            buckets=_LATENCY_BUCKETS,
            registry=self.registry,
        )
        self._pii_blocked = Counter(
            "docqa_pii_blocked_total",
            "PII 阻断次数",
            ["stage", "category"],
            registry=self.registry,
        )

    # ------------------------------------------------------------ LLM

    def llm_call(self, *, status: str, model: str, error_code: str | None, seconds: float) -> None:
        """一次 LLM 外呼的终态。

        `status=success/error/timeout` 三者之和构成失败率分母（§4.2 补充说明），所以调用方
        必须**无论如何都调用一次**——超时和协议错误不能因为抛异常就漏计。
        """

        self._llm_calls.labels(status=_tag(status), model=_tag(model), error_code=_tag(error_code)).inc()
        self._llm_latency.labels(model=_tag(model), status=_tag(status)).observe(max(0.0, seconds))

    # ------------------------------------------------------------ Agent

    def agent_round(self, rounds: int, status: str) -> None:
        self._agent_rounds.labels(status=_tag(status)).observe(max(0, rounds))

    def agent_tool_call(self, tool: str, status: str) -> None:
        self._agent_tool_calls.labels(tool=_tag(tool), status=_tag(status)).inc()

    def agent_tokens(self, *, token_in: int, token_out: int, model: str) -> None:
        if token_in > 0:
            self._agent_tokens.labels(direction="input", model=_tag(model)).inc(token_in)
        if token_out > 0:
            self._agent_tokens.labels(direction="output", model=_tag(model)).inc(token_out)

    def agent_budget_exhausted(self, reason: str) -> None:
        self._agent_budget_exhausted.labels(reason=_tag(reason)).inc()

    # ------------------------------------------------------------ 检索

    def rag_retrieval(self, *, seconds: float, result: str, index_version: str) -> None:
        self._rag_retrieval_latency.labels(result=_tag(result), index_version=_tag(index_version)).observe(
            max(0.0, seconds)
        )

    def vector_search(self, *, seconds: float, collection: str, result: str) -> None:
        self._vector_search_latency.labels(collection=_tag(collection), result=_tag(result)).observe(max(0.0, seconds))

    # ------------------------------------------------------------ 安全

    def pii_blocked(self, stage: str, category: str) -> None:
        self._pii_blocked.labels(stage=_tag(stage), category=_tag(category)).inc()

    # ------------------------------------------------------------ 导出

    def render(self) -> bytes:
        """Prometheus 文本格式（text/plain; version=0.0.4）。"""

        return generate_latest(self.registry)
