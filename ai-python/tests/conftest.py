from __future__ import annotations

import json
import uuid
from collections.abc import Callable
from typing import Any

import pytest

from docqa_ai.config import Settings
from docqa_ai.protocol.models import AgentAskRequest

TRACE_ID = "0123456789abcdef0123456789abcdef"
USER_ID = uuid.UUID("11111111-1111-4111-8111-111111111111")
CONVERSATION_ID = uuid.UUID("22222222-2222-4222-8222-222222222222")
DOCUMENT_ID = uuid.UUID("33333333-3333-4333-8333-333333333333")


@pytest.fixture
def settings() -> Settings:
    return Settings(
        _env_file=None,
        environment="test",
        low_confidence_threshold=0.55,
        retrieval_top_k=6,
        history_max_tokens=2000,
        embedding_provider="offline",
        embedding_model="offline-hashing-1024",
        embedding_dimensions=1024,
        llm_provider="offline",
        llm_model="offline-extractive",
        mq_enabled=False,
        minio_enabled=False,
        pg_dsn="",
        otel_enabled=False,
        chunk_size_chars=800,
        chunk_overlap_chars=100,
    )


def request_payload(**overrides: Any) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "protocolVersion": "1.0",
        "userId": str(USER_ID),
        "traceId": TRACE_ID,
        "conversationId": str(CONVERSATION_ID),
        "question": "Python 的 GIL 是什么？",
        "piiMaskedQuestion": "Python 的 GIL 是什么？",
        "piiMaskingVersion": "pii-v1",
        "tokenBudget": 8000,
        "maxToolCalls": 1,
        "maxRounds": 2,
        "allowedTools": [],
        "deadlineMs": 2000,
        "documentScopes": [{"documentId": str(DOCUMENT_ID), "indexVersion": "v1"}],
        "history": [],
    }
    payload.update(overrides)
    return payload


def make_request(**overrides: Any) -> AgentAskRequest:
    return AgentAskRequest.model_validate_json(json.dumps(request_payload(**overrides)))


def make_settings(**overrides: Any) -> Settings:
    base = {
        "_env_file": None,
        "environment": "test",
        "embedding_provider": "offline",
        "llm_provider": "offline",
        "mq_enabled": False,
        "minio_enabled": False,
        "pg_dsn": "",
        "otel_enabled": False,
    }
    base.update(overrides)
    return Settings(**base)


def call_counter() -> Callable[[], int]:
    count = 0

    def increment() -> int:
        nonlocal count
        count += 1
        return count

    return increment
