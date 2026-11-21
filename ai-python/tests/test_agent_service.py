from __future__ import annotations

import uuid

import pytest

from docqa_ai.agent.graph import BUDGET_FALLBACK_ANSWER
from docqa_ai.agent.service import AgentAskService
from docqa_ai.config import Settings
from docqa_ai.governance.budget import BudgetSnapshot, BudgetUnavailable, Deadline, InMemoryBudgetStore
from docqa_ai.governance.experiment_budget import ExperimentBudgetStore, InMemoryExperimentBudgetStore
from docqa_ai.governance.kill_switch import StaticKillSwitch
from docqa_ai.pii.masker import mask_text
from docqa_ai.protocol.errors import AgentError
from docqa_ai.providers.embeddings import OfflineEmbeddingProvider
from docqa_ai.providers.planner import (
    OfflinePlanner,
    PlanContext,
    PlannerOutput,
    PlannerOutputInvalid,
    PlannerTimeout,
    PlannerUnavailable,
)
from docqa_ai.rag.lexical_repository import LexicalHit, LexicalStoreUnavailable
from docqa_ai.rag.repository import (
    ChunkRecord,
    InMemoryVectorRepository,
    VectorHit,
    VectorStoreUnavailable,
)
from docqa_ai.rag.service import RetrievalService
from docqa_ai.tools.base import ToolContext, ToolResult
from docqa_ai.tools.calculator import CalculatorTool
from docqa_ai.tools.registry import ToolRegistry
from tests.conftest import DOCUMENT_ID, TENANT_ID, make_request, make_settings


def budget_store(*, token_budget: int = 8000) -> InMemoryBudgetStore:
    return InMemoryBudgetStore(
        BudgetSnapshot(
            tokens_used=0,
            tool_calls_made=0,
            rounds_used=0,
            token_budget=token_budget,
            max_tool_calls=1,
            max_rounds=2,
            deadline_at="2026-10-27T10:00:10Z",
        )
    )


def empty_retrieval() -> RetrievalService:
    return RetrievalService(InMemoryVectorRepository(), OfflineEmbeddingProvider())


async def retrieval_for(content: str, lexical_repository: object | None = None) -> RetrievalService:
    embedder = OfflineEmbeddingProvider()
    vector = (await embedder.embed([content], 1.0))[0]
    repository = InMemoryVectorRepository(
        [
            ChunkRecord(
                vector_id=uuid.uuid4(),
                document_id=DOCUMENT_ID,
                chunk_index=0,
                content=content,
                embedding=tuple(vector),
                metadata={"index_version": "v1"},
                # 读侧会带真实 tenantId 下推；替身行必须标注同一租户，否则语义与真实 SQL 不同。
                tenant_id=str(TENANT_ID),
            )
        ]
    )
    return RetrievalService(
        repository,
        embedder,
        min_score=0.0,
        lexical_repository=lexical_repository,  # type: ignore[arg-type]
    )


class FailingLexicalRepository:
    """词法通道报错：整体检索仍必须成功，但退化要在审计与观测上可见（D-88）。"""

    async def search(self, lexical_query: str, scopes: object, limit: int) -> list[object]:
        raise LexicalStoreUnavailable("lexical index is down")


class EmptyLexicalRepository:
    async def search(self, lexical_query: str, scopes: object, limit: int) -> list[object]:
        return []


def service(
    settings: Settings | None = None,
    *,
    retrieval: RetrievalService | None = None,
    planner: object | None = None,
    budget: InMemoryBudgetStore | None = None,
    kill_switch: StaticKillSwitch | None = None,
    tools: ToolRegistry | None = None,
    experiment: ExperimentBudgetStore | None = None,
    mcp: object | None = None,
) -> AgentAskService:
    return AgentAskService(
        settings or make_settings(),
        budget or budget_store(),
        kill_switch or StaticKillSwitch(False),
        retrieval or empty_retrieval(),
        planner or OfflinePlanner(),
        tools or ToolRegistry([]),
        experiment=experiment,
        mcp=mcp,  # type: ignore[arg-type]
    )


class FixedPlanner:
    def __init__(self, output: PlannerOutput) -> None:
        self.output = output

    async def plan(self, plan_context: PlanContext, timeout_s: float) -> PlannerOutput:
        return self.output


class FailingPlanner:
    def __init__(self, error: Exception) -> None:
        self.error = error

    async def plan(self, plan_context: PlanContext, timeout_s: float) -> PlannerOutput:
        raise self.error


class BrokenRepository:
    async def search(self, embedding: object, scopes: object, top_k: int) -> list[object]:
        raise VectorStoreUnavailable("down")


def event_types(response: object) -> list[str]:
    return [event.eventType for event in response.audit_events]  # type: ignore[attr-defined]


async def test_answer_with_context_returns_citation_and_ok_status() -> None:
    question = "Python 的 GIL 是什么？"
    result = await service(retrieval=await retrieval_for(question)).ask(make_request())
    assert result.citations[0].document_id == DOCUMENT_ID
    assert result.citations[0].chunk_index == 0
    assert result.confidence >= 0.55
    assert result.budget_status == "OK"
    assert result.citations[0].snippet == mask_text(question).masked
    assert "RAG_RETRIEVED" in event_types(result)
    assert "AGENT_COMPLETED" in event_types(result)


async def test_no_context_returns_low_confidence_without_citations() -> None:
    result = await service().ask(make_request())
    assert result.citations == []
    assert result.confidence == 0.0
    assert result.budget_status == "LOW_CONFIDENCE"
    assert "无法" in result.answer or "不足" in result.answer


async def test_budget_exhaustion_has_priority_and_no_citations() -> None:
    question = "Python 的 GIL 是什么？"
    result = await service(
        retrieval=await retrieval_for(question),
        budget=budget_store(token_budget=1),
    ).ask(make_request())
    assert result.budget_status == "BUDGET_EXHAUSTED"
    assert result.citations == []
    assert result.token_used == 0


async def test_kill_switch_returns_503_before_reading_budget() -> None:
    with pytest.raises(AgentError) as captured:
        await service(kill_switch=StaticKillSwitch(True)).ask(make_request())
    assert captured.value.code == "AGT-UPSTREAM-003"
    assert captured.value.status == 503
    assert [event.eventType for event in captured.value.audit_events] == ["AGENT_KILL_SWITCH"]


async def test_pii_in_masked_question_is_blocked() -> None:
    request = make_request(
        question="联系 a@b.com",
        piiMaskedQuestion="联系 a@b.com",
    )
    with pytest.raises(AgentError) as captured:
        await service().ask(request)
    assert captured.value.code == "AGT-VALIDATION-003"
    types = [event.eventType for event in captured.value.audit_events]
    assert types == ["PII_DIFFERENTIAL_CHECK", "PII_LEAK_BLOCKED"]
    assert "a@b.com" not in str([event.payloadRedacted for event in captured.value.audit_events])


async def test_pii_in_user_history_is_blocked() -> None:
    request = make_request(history=[{"role": "USER", "piiMaskedContent": "我的手机 13800138000"}])
    with pytest.raises(AgentError) as captured:
        await service().ask(request)
    assert captured.value.code == "AGT-VALIDATION-003"
    assert [event.eventType for event in captured.value.audit_events] == ["PII_LEAK_BLOCKED"]


async def test_assistant_history_pii_is_masked_and_audited() -> None:
    request = make_request(history=[{"role": "ASSISTANT", "piiMaskedContent": "邮箱 a@b.com"}])
    result = await service().ask(request)
    assert "PII_MASKED_IN_HISTORY" in event_types(result)
    assert result.answer


async def test_output_pii_is_masked_before_returning() -> None:
    planner = FixedPlanner(PlannerOutput(kind="final", answer="请拨打 13800138000", token_in=5, token_out=5))
    result = await service(planner=planner).ask(make_request())
    assert "13800138000" not in result.answer
    assert "[PHONE]" in result.answer
    assert "PII_MASKED_IN_OUTPUT" in event_types(result)


async def test_retrieval_pii_is_masked_and_audited() -> None:
    content = "Python 的 GIL 是什么？联系 13800138000"
    result = await service(retrieval=await retrieval_for(content)).ask(make_request())
    assert "13800138000" not in str(result.model_dump())
    assert "PII_MASKED_IN_RETRIEVAL" in event_types(result)


async def test_lexical_fallback_is_audited_as_warn_without_new_governance_surfaces() -> None:
    """D-88：退化必须可见，但既不新增 audit_event 类型，也不新增两侧闭集白名单里的 payload 键。"""

    question = "Python 的 GIL 是什么？"
    result = await service(retrieval=await retrieval_for(question, FailingLexicalRepository())).ask(make_request())
    retrieved = next(event for event in result.audit_events if event.eventType == "RAG_RETRIEVED")
    assert retrieved.severity == "WARN"
    assert len(result.citations) == 1
    seen = event_types(result)
    assert "RETRIEVAL_FAILED" not in seen
    assert "AUDIT_PAYLOAD_REJECTED" not in seen


async def test_healthy_lexical_channel_keeps_the_retrieval_event_at_info() -> None:
    question = "Python 的 GIL 是什么？"
    result = await service(retrieval=await retrieval_for(question, EmptyLexicalRepository())).ask(make_request())
    retrieved = next(event for event in result.audit_events if event.eventType == "RAG_RETRIEVED")
    assert retrieved.severity == "INFO"


async def test_history_truncation_is_reported() -> None:
    settings = make_settings(history_max_tokens=10)
    request = make_request(history=[{"role": "USER", "piiMaskedContent": "x" * 100}])
    result = await service(settings=settings).ask(request)
    completed = next(event for event in result.audit_events if event.eventType == "AGENT_COMPLETED")
    assert completed.payloadRedacted["historyTruncated"] is True


async def test_retrieval_unavailable_is_mapped_to_503() -> None:
    retrieval = RetrievalService(BrokenRepository(), OfflineEmbeddingProvider())  # type: ignore[arg-type]
    with pytest.raises(AgentError) as captured:
        await service(retrieval=retrieval).ask(make_request())
    assert captured.value.code == "AGT-UPSTREAM-001"
    assert "RETRIEVAL_FAILED" in [event.eventType for event in captured.value.audit_events]


async def test_planner_unavailable_is_mapped_to_503() -> None:
    with pytest.raises(AgentError) as captured:
        await service(planner=FailingPlanner(PlannerUnavailable("down"))).ask(make_request())
    assert captured.value.code == "AGT-UPSTREAM-001"
    assert captured.value.status == 503


async def test_planner_protocol_violation_is_mapped_to_503() -> None:
    with pytest.raises(AgentError) as captured:
        await service(planner=FailingPlanner(PlannerOutputInvalid("bad"))).ask(make_request())
    assert captured.value.code == "AGT-UPSTREAM-001"


async def test_planner_timeout_after_deadline_is_mapped_to_504() -> None:
    now = [0.0]

    class TimeoutPlanner:
        async def plan(self, plan_context: PlanContext, timeout_s: float) -> PlannerOutput:
            now[0] = 0.2
            raise PlannerTimeout("timeout")

    deadline = Deadline(100, clock=lambda: now[0])
    with pytest.raises(AgentError) as captured:
        await service(planner=TimeoutPlanner()).ask(make_request(deadlineMs=100), deadline=deadline)
    assert captured.value.code == "AGT-UPSTREAM-002"
    assert captured.value.status == 504


async def test_unexpected_failure_is_sanitized_to_500() -> None:
    with pytest.raises(AgentError) as captured:
        await service(planner=FailingPlanner(RuntimeError("secret dsn"))).ask(make_request())
    assert captured.value.code == "AGT-INTERNAL-001"
    assert captured.value.status == 500
    assert "secret" not in captured.value.detail
    event_types_seen = [event.eventType for event in captured.value.audit_events]
    assert event_types_seen[-1] == "AGENT_FAILED"
    assert "PII_LEAK_BLOCKED" not in event_types_seen


async def test_calculator_tool_loop_returns_tool_calls_and_rounds() -> None:
    result = await service(tools=ToolRegistry([CalculatorTool()])).ask(
        make_request(
            question="计算 2+3 等于多少",
            piiMaskedQuestion="计算 2+3 等于多少",
            allowedTools=["calculator"],
        )
    )
    assert result.tool_calls_made == 1
    assert result.rounds_used == 2
    assert "5" in result.answer
    assert result.budget_status == "LOW_CONFIDENCE"
    assert "TOOL_EXECUTED" in event_types(result)


class EchoMcpTool:
    """只回答它收到的入参：用来证明装配面把 MCP 工具接进了同一条执行链。"""

    name = "mcp__dict__lookup"
    source = "mcp"
    description = "查询词典释义"
    input_hint = "JSON 对象参数，必需键: word"

    def __init__(self) -> None:
        self.calls: list[str] = []

    async def run(self, argument: str, context: ToolContext) -> ToolResult:
        self.calls.append(argument)
        return ToolResult(observation=f"释义：{argument}")


class FakeMcpProvider:
    def __init__(self, tools: tuple[EchoMcpTool, ...] = ()) -> None:
        self._tools = tools
        self.calls: list[bool] = []
        self.closed = 0

    async def tools(self, *, force: bool = False) -> tuple[EchoMcpTool, ...]:
        self.calls.append(force)
        return self._tools

    async def aclose(self) -> None:
        self.closed += 1


class ScriptedPlanner:
    def __init__(self, *outputs: PlannerOutput) -> None:
        self.outputs = list(outputs)

    async def plan(self, plan_context: PlanContext, timeout_s: float) -> PlannerOutput:
        return self.outputs.pop(0)


def mcp_round() -> tuple[PlannerOutput, PlannerOutput]:
    return (
        PlannerOutput(
            kind="tool",
            tool_name="mcp__dict__lookup",
            tool_input='{"word": "hi"}',
            token_in=5,
            token_out=5,
        ),
        PlannerOutput(kind="final", answer="词典答案是 hi", token_in=5, token_out=5),
    )


async def test_mcp_tool_runs_when_the_request_allows_it_and_is_audited_as_mcp() -> None:
    tool = EchoMcpTool()
    provider = FakeMcpProvider((tool,))
    result = await service(planner=ScriptedPlanner(*mcp_round()), mcp=provider).ask(
        make_request(allowedTools=["mcp__dict__lookup"])
    )
    assert tool.calls == ['{"word": "hi"}']
    assert result.tool_calls_made == 1
    assert result.rounds_used == 2
    assert result.answer == "词典答案是 hi"
    executed = next(event for event in result.audit_events if event.eventType == "TOOL_EXECUTED")
    assert executed.payloadRedacted == {"toolName": "mcp__dict__lookup", "toolSource": "mcp"}


async def test_mcp_tool_outside_the_request_allowlist_is_rejected() -> None:
    tool = EchoMcpTool()
    with pytest.raises(AgentError) as captured:
        await service(planner=ScriptedPlanner(*mcp_round()), mcp=FakeMcpProvider((tool,))).ask(
            make_request(allowedTools=[])
        )
    assert captured.value.code == "AGT-UPSTREAM-001"
    assert tool.calls == []


async def test_mcp_tool_that_was_not_discovered_is_rejected() -> None:
    with pytest.raises(AgentError) as captured:
        await service(planner=ScriptedPlanner(*mcp_round()), mcp=FakeMcpProvider(())).ask(
            make_request(allowedTools=["mcp__dict__lookup"])
        )
    assert captured.value.code == "AGT-UPSTREAM-001"


async def test_requests_without_mcp_tools_do_not_consult_the_provider() -> None:
    provider = FakeMcpProvider((EchoMcpTool(),))
    await service(mcp=provider).ask(make_request(allowedTools=["calculator"]))
    assert provider.calls == []


async def test_prewarm_and_close_forward_to_the_mcp_provider() -> None:
    provider = FakeMcpProvider((EchoMcpTool(),))
    agent = service(mcp=provider)
    await agent.prewarm_tools()
    await agent.aclose()
    assert provider.calls == [True]
    assert provider.closed == 1


async def test_without_mcp_lifecycle_calls_are_noops() -> None:
    agent = service()
    await agent.prewarm_tools()
    await agent.aclose()


class FixedVectorRepository:
    """把向量侧分数写死：置信度的耦合关系必须可断言，不能靠 offline 哈希碰运气（D-94）。"""

    def __init__(self, hits: list[VectorHit]) -> None:
        self._hits = hits

    async def search(self, embedding: object, scopes: object, top_k: int) -> list[VectorHit]:
        return self._hits[:top_k]


class FixedLexicalRepository:
    def __init__(self, hits: list[LexicalHit]) -> None:
        self._hits = hits

    async def search(self, lexical_query: str, scopes: object, limit: int) -> list[LexicalHit]:
        return self._hits[:limit]


def scored_service(
    vector_scores: tuple[float, ...] = (),
    *,
    lexical_count: int = 0,
    citation_indexes: tuple[int, ...] = (0,),
) -> AgentAskService:
    hits = [
        VectorHit(
            document_id=DOCUMENT_ID,
            chunk_index=index,
            content=f"知识块{index}",
            score=score,
        )
        for index, score in enumerate(vector_scores)
    ]
    lexical_hits = [
        LexicalHit(document_id=DOCUMENT_ID, chunk_index=50 + index, content=f"词法块{index}")
        for index in range(lexical_count)
    ]
    retrieval = RetrievalService(
        FixedVectorRepository(hits),
        OfflineEmbeddingProvider(),
        min_score=0.0,
        lexical_repository=FixedLexicalRepository(lexical_hits),
        lexical_enabled=bool(lexical_hits),
    )
    planner = FixedPlanner(
        PlannerOutput(
            kind="final",
            answer="依据引用块给出结论",
            citation_indexes=citation_indexes,
            token_in=5,
            token_out=5,
        )
    )
    return service(retrieval=retrieval, planner=planner)


async def test_confidence_is_the_max_cited_score_not_an_average() -> None:
    result = await scored_service((0.9, 0.4, 0.2), citation_indexes=(0, 1, 2)).ask(make_request())
    assert len(result.citations) == 3
    assert result.confidence == pytest.approx(0.9)
    assert result.budget_status == "OK"


async def test_confidence_follows_which_chunk_is_cited_not_how_many_exist() -> None:
    """高相似块没被引用时不能抬置信度——引用条数与 confidence 之间没有直接关系（§2.2）。"""

    result = await scored_service((0.9, 0.4), citation_indexes=(1,)).ask(make_request())
    assert len(result.citations) == 1
    assert result.citations[0].score == pytest.approx(0.4)
    assert result.confidence == pytest.approx(0.4)
    assert result.budget_status == "LOW_CONFIDENCE"


async def test_lexical_only_citation_stays_visible_with_zero_confidence() -> None:
    """D-84 的对外后果：引用非空、score 恒 0.0、必然 LOW_CONFIDENCE，但回答不因此被阻断。"""

    result = await scored_service(lexical_count=1, citation_indexes=(0,)).ask(make_request())
    assert len(result.citations) == 1
    assert result.citations[0].score == 0.0
    assert result.confidence == 0.0
    assert result.budget_status == "LOW_CONFIDENCE"
    assert result.answer == "依据引用块给出结论"
    assert "RAG_RETRIEVED" in event_types(result)
    assert "RETRIEVAL_FAILED" not in event_types(result)


class ForbiddenRepository:
    """预检命中后必须零外呼：检索一旦被调用，这条替身就把「静默花钱」变成失败断言。"""

    async def search(self, embedding: object, scopes: object, top_k: int) -> list[object]:
        raise AssertionError("retrieval must not run once the experiment quota is spent")


async def test_experiment_exhaustion_soft_degrades_before_any_retrieval() -> None:
    """D-119 ②：额度用尽呈现为既有的 200 软降级，而不是新错误码——响应面一个字段都没新增。"""

    ledger = InMemoryExperimentBudgetStore(1, 200_000)
    assert await ledger.authorize_call(1) is True
    retrieval = RetrievalService(ForbiddenRepository(), OfflineEmbeddingProvider())  # type: ignore[arg-type]
    result = await service(retrieval=retrieval, experiment=ledger).ask(make_request())

    assert result.budget_status == "BUDGET_EXHAUSTED"
    assert result.answer == BUDGET_FALLBACK_ANSWER
    assert result.citations == []
    assert result.confidence == 0.0
    assert result.token_used == 0
    assert result.rounds_used == 0
    assert result.tool_calls_made == 0
    seen = event_types(result)
    assert "RAG_RETRIEVED" not in seen
    assert seen[-1] == "AGENT_COMPLETED"
    assert (await ledger.snapshot()).calls_used == 1


async def test_experiment_ledger_read_failure_fails_closed_to_503() -> None:
    """读不到账本就是不知道还能不能花钱，此时不软降级也不裸奔：沿用既有 503。"""

    class Unreadable:
        async def snapshot(self):
            raise BudgetUnavailable("redis experiment budget read failed")

        async def authorize_call(self, estimated_tokens: int) -> bool:
            raise AssertionError("the pre-check reads the snapshot first")

        async def settle_call(self, reserved: int, actual: int):
            raise AssertionError("no call may follow a refused pre-check")

    retrieval = RetrievalService(ForbiddenRepository(), OfflineEmbeddingProvider())  # type: ignore[arg-type]
    with pytest.raises(AgentError) as captured:
        await service(retrieval=retrieval, experiment=Unreadable()).ask(make_request())
    assert captured.value.code == "AGT-UPSTREAM-001"
    assert captured.value.status == 503
    assert event_types(captured.value)[-1] == "AGENT_FAILED"


class PublishingLedger(InMemoryExperimentBudgetStore):
    """记录启动期是否发布过上限；其余语义沿用内存账本，避免污染其它断言。"""

    def __init__(self) -> None:
        super().__init__(10, 1000)
        self.published = 0

    async def publish_limits(self) -> None:
        self.published += 1


class UnpublishableLedger(InMemoryExperimentBudgetStore):
    async def publish_limits(self) -> None:
        raise BudgetUnavailable("redis experiment budget limit publish failed")


async def test_startup_publishes_the_experiment_limits_through_the_service() -> None:
    """S9-5e：lifespan 调用的就是这个入口，管理面的 `limitsKnown` 靠它变成 true。"""

    ledger = PublishingLedger()
    await service(experiment=ledger).publish_experiment_limits()
    assert ledger.published == 1


async def test_startup_without_a_ledger_never_publishes() -> None:
    """离线组合（experiment=None）必须在触碰 Redis 之前返回，否则 D-113 的零写键就破了。"""

    await service(experiment=None).publish_experiment_limits()


async def test_startup_publish_failure_only_degrades_the_echo(caplog: pytest.LogCaptureFixture) -> None:
    """Redis 抖动不能让问答进程起不来：上限仍由配置在准入时强制执行，回显缺失只降级。"""

    caplog.set_level("WARNING")
    ledger = UnpublishableLedger(10, 1000)

    await service(experiment=ledger).publish_experiment_limits()

    assert any("not published" in record.message for record in caplog.records)
    assert await ledger.snapshot() == await InMemoryExperimentBudgetStore(10, 1000).snapshot()
