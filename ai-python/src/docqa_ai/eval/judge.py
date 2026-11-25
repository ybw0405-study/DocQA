"""nightly 真模型 faithfulness 评测（自建 LLM judge）。

流程：真实 embedding/rerank 检索 → 真实 planner 生成回答 → LLM judge 对每个
(question, context, answer) 输出 JSON verdict（faithfulness ∈ [0,1] + reason），
均值低于门禁（默认 0.8）即非零退出。JSON 报告供 workflow 上传 artifact。

口径：检索在全语料 scope（含跨文档 hard-negative 干扰文档）下进行——
干扰块可能挤入 top3 时仍要求回答忠实，与召回评测同难度，不抽单文档易场景。
judge 默认与 planner 不同源（qwen-max vs qwen-plus），避免 self-judging 宽容偏差；
可用 DOCQA_EVAL_JUDGE_MODEL 覆盖。

刻意不引 RAGAS：它会把 langchain-community/datasets/nest-asyncio 拖进
--require-hashes 依赖锁，哈希面与版本 churn 太大；这里的 judge 复用 providers/
的 OpenAI 兼容 HTTP 形态与 JSON 解析，百行实现、可审计、无新依赖。

入口：
    DASHSCOPE_API_KEY=... python -m docqa_ai.eval.judge --json > judge-report.json
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import sys
from collections.abc import Sequence
from typing import Any

import httpx

from docqa_ai.eval.corpus import load_corpus
from docqa_ai.eval.runner import _build_embedder, _build_reranker, index_corpus
from docqa_ai.providers.planner import ContextBlock, OpenAIPlanner, PlanContext, PlannerError
from docqa_ai.rag.repository import InMemoryVectorRepository
from docqa_ai.rag.service import RetrievalService

_DEFAULT_GATE = 0.8
_DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
_DIMENSIONS = 1024
_TIMEOUT_S = 60.0

_JUDGE_SYSTEM_PROMPT = """你是 RAG 回答忠实度评审。给定问题、检索到的资料与回答，判断回答中的事实性陈述
是否全部能从资料中得到支持（不评价文采，不要求资料之外的知识）。
只输出一个 JSON 对象，不要 Markdown 代码块：
{"faithfulness": 0.0, "reason": "简短说明"}
faithfulness 取值 0 到 1：1 表示全部陈述有资料支持，0 表示关键陈述与资料矛盾或凭空编造。"""


class JudgeError(Exception):
    pass


class LlmJudge:
    """OpenAI 兼容 /chat/completions 的评审客户端；HTTP 形态与 OpenAIPlanner 一致。"""

    def __init__(
        self,
        base_url: str,
        api_key: str,
        model: str,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self.model = model
        self._base_url = base_url.rstrip("/")
        self._api_key = api_key
        self._client = client if client is not None else httpx.AsyncClient()
        self._owns_client = client is None

    async def verdict(
        self, question: str, context: str, answer: str, *, timeout_s: float = _TIMEOUT_S
    ) -> dict[str, Any]:
        user_prompt = (
            f"<question>\n{question}\n</question>\n\n<context>\n{context}\n</context>\n\n<answer>\n{answer}\n</answer>"
        )
        payload = {
            "model": self.model,
            "messages": [
                {"role": "system", "content": _JUDGE_SYSTEM_PROMPT},
                {"role": "user", "content": user_prompt},
            ],
            "temperature": 0,
            "max_tokens": 300,
        }
        headers = {"Content-Type": "application/json"}
        if self._api_key:
            headers["Authorization"] = f"Bearer {self._api_key}"
        try:
            response = await self._client.post(
                f"{self._base_url}/chat/completions",
                json=payload,
                headers=headers,
                timeout=httpx.Timeout(max(0.1, timeout_s), connect=min(3.0, max(0.1, timeout_s))),
            )
        except httpx.HTTPError as exc:
            raise JudgeError(f"judge request failed: {type(exc).__name__}") from exc
        if response.status_code >= 400:
            raise JudgeError(f"judge provider returned {response.status_code}")
        try:
            content = response.json()["choices"][0]["message"]["content"]
        except (KeyError, IndexError, TypeError, ValueError) as exc:
            raise JudgeError("judge response is malformed") from exc
        # 复用生产 planner 的 JSON 提取（含代码围栏剥离），评审与回答同一解析口径。
        parsed = OpenAIPlanner._parse_content(content)
        score = parsed.get("faithfulness")
        if isinstance(score, bool) or not isinstance(score, (int, float)) or not 0.0 <= score <= 1.0:
            raise JudgeError("judge verdict faithfulness is invalid")
        return {"faithfulness": float(score), "reason": str(parsed.get("reason", ""))[:500]}

    async def aclose(self) -> None:
        if self._owns_client:
            await self._client.aclose()


def judge_summary(
    per_query: Sequence[dict[str, Any]],
    gate: float = _DEFAULT_GATE,
    *,
    planner_model: str = "",
    judge_model: str = "",
) -> dict[str, Any]:
    """汇总逐查询 verdict；空集视为失败（没有证据不等于通过门禁）。"""
    mean = sum(item["faithfulness"] for item in per_query) / len(per_query) if per_query else 0.0
    return {
        "suite": "judge",
        "query_count": len(per_query),
        "mean_faithfulness": round(mean, 4),
        "gate": gate,
        "passed": bool(per_query) and mean >= gate,
        "planner_model": planner_model,
        "judge_model": judge_model,
        "retrieval_scope": "all-corpus（含跨文档 hard-negative）",
        "per_query": list(per_query),
    }


async def run_judge_eval(gate: float = _DEFAULT_GATE) -> dict[str, Any]:
    api_key = os.getenv("DOCQA_LLM_API_KEY") or os.getenv("DASHSCOPE_API_KEY", "")
    if not api_key:
        raise SystemExit("nightly judge 需要 DASHSCOPE_API_KEY（或 DOCQA_LLM_API_KEY），不写任何默认密钥")
    base_url = os.getenv("DOCQA_LLM_BASE_URL", _DEFAULT_BASE_URL)
    planner_model = os.getenv("DOCQA_EVAL_PLANNER_MODEL", "qwen-plus")
    judge_model = os.getenv("DOCQA_EVAL_JUDGE_MODEL", "qwen-max")

    embedder = _build_embedder()
    reranker = _build_reranker()
    repository = InMemoryVectorRepository()
    corpus_docs = load_corpus()
    indexed = await index_corpus(embedder, repository, corpus_docs)
    all_scopes = [idx.scope for idx in indexed.values()]
    service = RetrievalService(
        repository,
        embedder,
        top_k=6,
        min_score=0.0,
        lexical_enabled=False,
        reranker=reranker,
        embedding_dimensions=_DIMENSIONS,
    )
    planner = OpenAIPlanner(base_url, api_key, planner_model)
    judge = LlmJudge(base_url, api_key, judge_model)
    per_query: list[dict[str, Any]] = []
    try:
        for doc in corpus_docs:
            for query in doc.queries:
                # 全语料 scope：与召回评测同难度——干扰块挤入 top3 时仍要求忠实，
                # 不抽单文档易场景（那里检索必然命中黄金块，测不出忠实度边界）。
                retrieved = await service.retrieve(query.question, all_scopes)
                blocks = tuple(
                    ContextBlock(
                        index=position,
                        document_id=chunk.document_id,
                        chunk_index=chunk.chunk_index,
                        content=chunk.content,
                        score=chunk.score,
                    )
                    for position, chunk in enumerate(retrieved.chunks[:3])
                )
                context = PlanContext(
                    question=query.question,
                    history=(),
                    context_blocks=blocks,
                    observations=(),
                    allowed_tools=(),
                    round_number=1,
                    max_rounds=1,
                    max_output_tokens=512,
                    model=planner_model,
                )
                output = await planner.plan(context, _TIMEOUT_S)
                verdict = await judge.verdict(
                    query.question,
                    "\n\n".join(block.content for block in blocks),
                    output.answer,
                )
                per_query.append(
                    {
                        "query_id": query.query_id,
                        "question": query.question,
                        "answer": output.answer,
                        "citations": list(output.citation_indexes),
                        **verdict,
                    }
                )
    finally:
        await planner.aclose()
        await judge.aclose()
        await service.aclose()
    return judge_summary(per_query, gate, planner_model=planner_model, judge_model=judge_model)


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="docqa_ai.eval.judge", description="nightly 真模型 faithfulness 门禁")
    parser.add_argument("--json", action="store_true", dest="as_json", help="输出机器可读 JSON 报告")
    parser.add_argument("--gate", type=float, default=_DEFAULT_GATE, help="mean faithfulness 门禁下限")
    args = parser.parse_args(argv)
    try:
        payload = asyncio.run(run_judge_eval(args.gate))
    except (JudgeError, PlannerError) as exc:
        print(f"judge 评测失败: {type(exc).__name__}: {exc}", file=sys.stderr)
        return 1
    if args.as_json:
        print(json.dumps(payload, ensure_ascii=False, indent=2))
    else:
        print(f"judge: queries={payload['query_count']} mean_faithfulness={payload['mean_faithfulness']}")
        for item in payload["per_query"]:
            print(f"  {item['query_id']:<14} faithfulness={item['faithfulness']:.2f}  {item['reason']}")
        print(f"gate={payload['gate']} passed={payload['passed']}")
    return 0 if payload["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
