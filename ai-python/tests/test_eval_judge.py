"""自建 LLM judge 的离线单测：HTTP 全部 respx 拦截，不触网。

锁住 verdict 解析口径（含代码围栏剥离）、非法评分/上游故障必须响亮失败，
以及均值门禁的聚合语义（空集不通过）。
"""

from __future__ import annotations

import httpx
import pytest
import respx

from docqa_ai.eval.judge import JudgeError, LlmJudge, judge_summary

BASE_URL = "https://judge.example/v1"
ENDPOINT = BASE_URL + "/chat/completions"


def _completion(content: str) -> httpx.Response:
    return httpx.Response(200, json={"choices": [{"message": {"content": content}}]})


@respx.mock
async def test_verdict_parses_json_score() -> None:
    respx.post(ENDPOINT).mock(return_value=_completion('{"faithfulness": 0.9, "reason": "有资料支持"}'))
    judge = LlmJudge(BASE_URL, "key", "judge-model")
    try:
        verdict = await judge.verdict("问题", "资料", "回答")
        assert verdict == {"faithfulness": 0.9, "reason": "有资料支持"}
    finally:
        await judge.aclose()


@respx.mock
async def test_verdict_strips_code_fence_like_planner() -> None:
    respx.post(ENDPOINT).mock(return_value=_completion('```json\n{"faithfulness": 1.0, "reason": ""}\n```'))
    judge = LlmJudge(BASE_URL, "key", "judge-model")
    try:
        verdict = await judge.verdict("q", "c", "a")
        assert verdict["faithfulness"] == 1.0
    finally:
        await judge.aclose()


@pytest.mark.parametrize(
    "content", ['{"faithfulness": 1.5}', '{"faithfulness": "high"}', '{"faithfulness": true}', "{}"]
)
@respx.mock
async def test_invalid_verdict_fails_loudly(content: str) -> None:
    respx.post(ENDPOINT).mock(return_value=_completion(content))
    judge = LlmJudge(BASE_URL, "key", "judge-model")
    try:
        with pytest.raises(JudgeError):
            await judge.verdict("q", "c", "a")
    finally:
        await judge.aclose()


@respx.mock
async def test_upstream_http_error_is_judge_error() -> None:
    respx.post(ENDPOINT).mock(return_value=httpx.Response(500))
    judge = LlmJudge(BASE_URL, "key", "judge-model")
    try:
        with pytest.raises(JudgeError):
            await judge.verdict("q", "c", "a")
    finally:
        await judge.aclose()


@respx.mock
async def test_transport_timeout_is_judge_error() -> None:
    respx.post(ENDPOINT).mock(side_effect=httpx.ConnectTimeout("timeout"))
    judge = LlmJudge(BASE_URL, "key", "judge-model")
    try:
        with pytest.raises(JudgeError):
            await judge.verdict("q", "c", "a")
    finally:
        await judge.aclose()


@respx.mock
async def test_malformed_response_body_is_judge_error() -> None:
    respx.post(ENDPOINT).mock(return_value=httpx.Response(200, json={"unexpected": True}))
    judge = LlmJudge(BASE_URL, "key", "judge-model")
    try:
        with pytest.raises(JudgeError):
            await judge.verdict("q", "c", "a")
    finally:
        await judge.aclose()


def test_summary_gate_semantics() -> None:
    passing = judge_summary([{"faithfulness": 0.9}, {"faithfulness": 0.7}])
    assert passing["mean_faithfulness"] == 0.8
    assert passing["passed"] is True
    failing = judge_summary([{"faithfulness": 0.79}, {"faithfulness": 0.8}])
    assert failing["passed"] is False
    # 没有证据不等于通过门禁。
    assert judge_summary([])["passed"] is False
