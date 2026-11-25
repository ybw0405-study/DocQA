from __future__ import annotations

import json
import logging
import re
import uuid
from datetime import UTC, datetime
from hashlib import md5, sha256

from fakeredis.aioredis import FakeRedis
from minio.error import S3Error
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter

from docqa_ai.config import Settings
from docqa_ai.governance.experiment_budget import InMemoryExperimentBudgetStore
from docqa_ai.governance.experiment_egress import BudgetedEmbeddingProvider
from docqa_ai.parse_worker.errors import PublishError
from docqa_ai.parse_worker.idempotency import (
    BeginResult,
    IdempotencyUnavailable,
    InMemoryIdempotencyStore,
)
from docqa_ai.parse_worker.mq import _message_id, _property
from docqa_ai.parse_worker.parser import TEXT
from docqa_ai.parse_worker.publisher import InMemoryParsePublisher
from docqa_ai.parse_worker.storage import (
    InMemoryObjectStorage,
    MinioObjectStorage,
    ObjectNotFound,
    ObjectTooLarge,
)
from docqa_ai.parse_worker.vector_writer import (
    InMemoryVectorWriter,
    VectorWriteError,
    make_vector_id,
)
from docqa_ai.parse_worker.worker import HandleOutcome, ParseWorker, _build_embedder
from docqa_ai.providers.embeddings import OPENAI_COMPATIBLE_MAX_BATCH_ROWS, OfflineEmbeddingProvider
from docqa_ai.tracing import build_tracing, traceparent_for
from tests.conftest import DOCUMENT_ID, TENANT_ID, TRACE_ID, make_settings

USER_ID = "11111111-1111-4111-8111-111111111111"
OBJECT_KEY = "uploads/doc.txt"
TEXT_BODY = ("智能学习助手会把文档切分成可检索的片段。" * 20).encode()


def requested_event(data: bytes = TEXT_BODY, **overrides: object) -> dict[str, object]:
    payload: dict[str, object] = {
        "eventId": str(uuid.uuid4()),
        "traceId": TRACE_ID,
        "documentId": str(DOCUMENT_ID),
        "userId": USER_ID,
        "tenantId": str(TENANT_ID),
        "indexVersion": "v1",
        "objectKey": OBJECT_KEY,
        "fileName": "doc.txt",
        "contentType": TEXT,
        "sizeBytes": len(data),
        "md5": md5(data, usedforsecurity=False).hexdigest(),
        "retryCount": 0,
        "occurredAt": datetime.now(UTC).isoformat(),
    }
    payload.update(overrides)
    return payload


def body(data: bytes = TEXT_BODY, **overrides: object) -> bytes:
    return json.dumps(requested_event(data, **overrides)).encode()


def make_worker(
    settings: Settings | None = None,
    *,
    storage: object | None = None,
    embedder: object | None = None,
    writer: object | None = None,
    publisher: InMemoryParsePublisher | None = None,
    idempotency: object | None = None,
    tracer: object | None = None,
) -> ParseWorker:
    config = settings or make_settings(chunk_size_chars=100, chunk_overlap_chars=20)
    return ParseWorker(
        config,
        storage=storage or InMemoryObjectStorage({OBJECT_KEY: TEXT_BODY}),  # type: ignore[arg-type]
        embedder=embedder or OfflineEmbeddingProvider(),  # type: ignore[arg-type]
        writer=writer or InMemoryVectorWriter(),  # type: ignore[arg-type]
        publisher=publisher or InMemoryParsePublisher(),
        idempotency=idempotency or InMemoryIdempotencyStore(),  # type: ignore[arg-type]
        tracer=tracer,  # type: ignore[arg-type]
    )


async def test_valid_message_is_indexed_and_published() -> None:
    publisher = InMemoryParsePublisher()
    writer = InMemoryVectorWriter()
    worker = make_worker(
        writer=writer,
        publisher=publisher,
        storage=InMemoryObjectStorage({OBJECT_KEY: TEXT_BODY}),
    )
    outcome = await worker.handle("msg-1", body())

    assert outcome is HandleOutcome.PROCESSED
    assert len(publisher.completed) == 1
    assert publisher.failed == []
    event = publisher.completed[0]
    assert event.document_id == DOCUMENT_ID
    assert event.index_version == "v1"
    assert event.status == "COMPLETED"

    records = writer.records(DOCUMENT_ID, "v1")
    assert len(records) == len(event.chunks) > 1
    assert event.vector_rows == len(records), "vectorRows must come from the writer, not from len(chunks)"
    assert [chunk.chunk_index for chunk in event.chunks] == list(range(len(records)))
    for chunk, record in zip(event.chunks, records, strict=True):
        assert record.vector_id == chunk.vector_id == make_vector_id(DOCUMENT_ID, "v1", chunk.chunk_index)
        assert record.metadata["content_hash"] == chunk.content_hash
        assert record.metadata["embedding_model"] == "offline-hashing-1024"
        assert record.metadata["file_name"] == "doc.txt"
        assert len(record.embedding) == 1024
        assert chunk.content_hash == sha256(record.content.encode("utf-8")).hexdigest()

    payload = json.loads(event.model_dump_json(by_alias=True))
    assert payload["status"] == "COMPLETED"
    assert payload["traceId"] == TRACE_ID
    assert payload["vectorRows"] == len(records)
    assert payload["chunks"][0]["chunkIndex"] == 0
    assert re.fullmatch(r"^[a-f0-9]{64}$", payload["chunks"][0]["contentHash"])
    uuid.UUID(payload["chunks"][0]["vectorId"])


async def test_completed_message_is_not_processed_twice() -> None:
    publisher = InMemoryParsePublisher()
    worker = make_worker(publisher=publisher)
    assert await worker.handle("msg-2", body()) is HandleOutcome.PROCESSED
    assert await worker.handle("msg-2", body()) is HandleOutcome.DUPLICATE
    assert len(publisher.completed) == 1


async def test_checksum_mismatch_is_a_non_retryable_failure() -> None:
    publisher = InMemoryParsePublisher()
    worker = make_worker(publisher=publisher)
    outcome = await worker.handle("msg-3", body(md5="0" * 32))

    assert outcome is HandleOutcome.PROCESSED
    assert publisher.completed == []
    assert len(publisher.failed) == 1
    failed = publisher.failed[0]
    assert failed.error_code == "DOC-VALIDATION-004"
    assert failed.retryable is False
    assert await worker.handle("msg-3", body()) is HandleOutcome.DUPLICATE


async def test_size_mismatch_is_rejected_even_when_md5_matches() -> None:
    publisher = InMemoryParsePublisher()
    worker = make_worker(publisher=publisher)
    await worker.handle("msg-4", body(sizeBytes=len(TEXT_BODY) - 1))
    assert publisher.failed[0].error_code == "DOC-VALIDATION-004"


async def test_missing_object_is_retryable_and_released() -> None:
    publisher = InMemoryParsePublisher()
    worker = make_worker(publisher=publisher, storage=InMemoryObjectStorage())
    outcome = await worker.handle("msg-5", body())

    assert outcome is HandleOutcome.PROCESSED
    assert publisher.failed[0].error_code == "COM-INTERNAL-001"
    assert publisher.failed[0].retryable is True


async def test_minio_storage_maps_s3_errors_and_size_caps() -> None:
    class FakeResponse:
        def __init__(self, data: bytes) -> None:
            self._data = data
            self.closed = False

        def read(self, size: int) -> bytes:
            return self._data[:size]

        def close(self) -> None:
            self.closed = True

        def release_conn(self) -> None: ...

    class FakeMinio:
        def __init__(self, response: object | None = None, error: Exception | None = None) -> None:
            self.response = response
            self.error = error

        def get_object(self, bucket: str, key: str) -> object:
            if self.error is not None:
                raise self.error
            return self.response

    missing = S3Error(None, "NoSuchKey", "missing", "res", "req", "host")
    storage = MinioObjectStorage("localhost:9000", "key", "secret", "bucket", client=FakeMinio(error=missing))
    try:
        storage.get("uploads/a.txt", max_bytes=10)
    except ObjectNotFound:
        pass
    else:  # pragma: no cover - defensive
        raise AssertionError("NoSuchKey must map to ObjectNotFound")

    response = FakeResponse(b"x" * 20)
    storage = MinioObjectStorage("localhost:9000", "key", "secret", "bucket", client=FakeMinio(response=response))
    try:
        storage.get("uploads/a.txt", max_bytes=10)
    except ObjectTooLarge:
        pass
    else:  # pragma: no cover - defensive
        raise AssertionError("oversized object must map to ObjectTooLarge")
    assert response.closed is True


async def test_event_declared_size_over_limit_is_rejected_without_reading() -> None:
    settings = make_settings(worker_max_file_bytes=1024)
    publisher = InMemoryParsePublisher()
    data = b"x" * 2048
    worker = make_worker(settings=settings, publisher=publisher, storage=InMemoryObjectStorage())
    outcome = await worker.handle("msg-6", body(data))

    assert outcome is HandleOutcome.PROCESSED
    assert publisher.failed[0].error_code == "DOC-VALIDATION-001"
    assert publisher.failed[0].retryable is False


async def test_object_larger_than_declared_size_is_capped() -> None:
    settings = make_settings(worker_max_file_bytes=1024)
    publisher = InMemoryParsePublisher()
    data = b"x" * 2048
    worker = make_worker(
        settings=settings,
        publisher=publisher,
        storage=InMemoryObjectStorage({OBJECT_KEY: data}),
    )
    outcome = await worker.handle("msg-7", body(data, sizeBytes=100))

    assert outcome is HandleOutcome.PROCESSED
    assert publisher.failed[0].error_code == "DOC-VALIDATION-001"


async def test_unsupported_type_is_non_retryable() -> None:
    publisher = InMemoryParsePublisher()
    worker = make_worker(publisher=publisher)
    await worker.handle(
        "msg-8",
        body(contentType="image/png", fileName="a.png"),
    )
    assert publisher.failed[0].error_code == "DOC-VALIDATION-002"
    assert publisher.failed[0].retryable is False


async def test_empty_document_is_non_retryable() -> None:
    publisher = InMemoryParsePublisher()
    worker = make_worker(publisher=publisher, storage=InMemoryObjectStorage({OBJECT_KEY: b""}))
    await worker.handle("msg-9", body(b""))
    assert publisher.failed[0].error_code == "DOC-VALIDATION-004"


async def test_malformed_event_is_acknowledged_without_side_effects() -> None:
    publisher = InMemoryParsePublisher()
    idempotency = InMemoryIdempotencyStore()
    worker = make_worker(publisher=publisher, idempotency=idempotency)

    assert await worker.handle("msg-10", b"{not json") is HandleOutcome.INVALID
    assert publisher.completed == []
    assert publisher.failed == []
    assert await worker.handle("msg-10", body()) is HandleOutcome.DUPLICATE


async def test_busy_message_returns_retry_without_publishing() -> None:
    publisher = InMemoryParsePublisher()
    idempotency = InMemoryIdempotencyStore()
    assert await idempotency.begin("msg-11") is BeginResult.ACQUIRED
    worker = make_worker(publisher=publisher, idempotency=idempotency)

    assert await worker.handle("msg-11", body()) is HandleOutcome.RETRY
    assert publisher.completed == []
    assert publisher.failed == []


async def test_idempotency_unavailable_returns_retry() -> None:
    class UnavailableIdempotency:
        async def begin(self, message_id: str) -> BeginResult:
            raise IdempotencyUnavailable("down")

        async def complete(self, message_id: str) -> None: ...

        async def release(self, message_id: str) -> None: ...

    publisher = InMemoryParsePublisher()
    worker = make_worker(publisher=publisher, idempotency=UnavailableIdempotency())
    assert await worker.handle("msg-12", body()) is HandleOutcome.RETRY
    assert publisher.completed == []


async def test_publish_failure_releases_message_for_redelivery() -> None:
    class FlakyPublisher(InMemoryParsePublisher):
        def __init__(self, failures: int) -> None:
            super().__init__()
            self.failures = failures

        async def publish_completed(self, event: object) -> None:  # type: ignore[override]
            if self.failures:
                self.failures -= 1
                raise PublishError("broker down")
            await super().publish_completed(event)  # type: ignore[arg-type]

    publisher = FlakyPublisher(failures=1)
    writer = InMemoryVectorWriter()
    worker = make_worker(publisher=publisher, writer=writer)

    assert await worker.handle("msg-13", body()) is HandleOutcome.RETRY
    assert publisher.completed == []
    assert writer.records(DOCUMENT_ID, "v1")

    assert await worker.handle("msg-13", body()) is HandleOutcome.PROCESSED
    assert len(publisher.completed) == 1


async def test_vector_write_failure_publishes_retryable_failed_event() -> None:
    class FailingWriter:
        async def replace(
            self,
            document_id: object,
            index_version: str,
            records: object,
            tenant_id: object | None = None,
        ) -> None:
            raise VectorWriteError("db down")

    publisher = InMemoryParsePublisher()
    worker = make_worker(publisher=publisher, writer=FailingWriter())
    outcome = await worker.handle("msg-14", body())

    assert outcome is HandleOutcome.PROCESSED
    assert publisher.completed == []
    assert publisher.failed[0].error_code == "COM-INTERNAL-001"
    assert publisher.failed[0].retryable is True


async def test_embedding_shape_failure_is_retryable() -> None:
    class ShortEmbedder:
        model = "short"
        dimensions = 1024
        max_batch_rows = 1024

        async def embed(self, texts: list[str], timeout_s: float) -> list[list[float]]:
            return [[0.0, 0.0] for _ in texts]

    publisher = InMemoryParsePublisher()
    worker = make_worker(publisher=publisher, embedder=ShortEmbedder())
    await worker.handle("msg-15", body())

    failed = publisher.failed[0]
    assert failed.error_code == "COM-INTERNAL-001"
    assert failed.retryable is True
    assert "向量化" in failed.error_message


class RecordingEmbedder:
    """记录每批行数**与收到的原文**的 Provider 替身。

    `max_batch_rows` 模拟端点自报上限；`texts` 是「掩码是否生效」的判据载体——「Provider 没见过未掩码文本」
    只有把实收文本记下来才可断言，否则只能靠读代码相信它。
    """

    model = "recording"
    dimensions = 1024

    def __init__(self, max_batch_rows: int) -> None:
        self.max_batch_rows = max_batch_rows
        self.batches: list[int] = []
        self.texts: list[str] = []

    async def embed(self, texts: list[str], timeout_s: float) -> list[list[float]]:
        self.batches.append(len(texts))
        self.texts.extend(texts)
        return [[0.0] * 1024 for _ in texts]


async def test_embedding_batch_size_is_capped_by_the_provider_limit() -> None:
    """实测真实端点 11 行即 400，而 embedding_batch_size 默认 32：
    批大小必须取 min(配置, 自报上限)，否则 ≥11 chunk 的文档第一批就失败，
    且 worker.py 的 retryable 会把这笔花费再付一次。"""

    embedder = RecordingEmbedder(3)
    worker = make_worker(make_settings(embedding_batch_size=32), embedder=embedder)
    vectors = await worker._embed([f"段落 {index}" for index in range(8)])

    assert embedder.batches == [3, 3, 2]
    assert len(vectors) == 8


async def test_embedding_batch_size_is_not_raised_by_the_provider_limit() -> None:
    """自报上限只是天花板，不能把运维显式调小的批次抬高。"""

    embedder = RecordingEmbedder(1024)
    worker = make_worker(make_settings(embedding_batch_size=2), embedder=embedder)
    await worker._embed([f"段落 {index}" for index in range(5)])

    assert embedder.batches == [2, 2, 1]


async def test_traceparent_continues_the_document_parse_span() -> None:
    settings = make_settings(chunk_size_chars=100, chunk_overlap_chars=20)
    exporter = InMemorySpanExporter()
    tracer, provider = build_tracing(settings, exporter=exporter)
    worker = make_worker(settings=settings, tracer=tracer)

    outcome = await worker.handle(
        "msg-16",
        body(),
        traceparent=traceparent_for(TRACE_ID, "1" * 16),
    )

    assert outcome is HandleOutcome.PROCESSED
    span = next(span for span in exporter.get_finished_spans() if span.name == "document.parse")
    assert f"{span.context.trace_id:032x}" == TRACE_ID
    assert span.attributes["trace_continuation"] is True
    assert span.attributes["parse.status"] == "COMPLETED"
    assert span.attributes["document.id"] == str(DOCUMENT_ID)
    provider.shutdown()


async def test_embedding_input_is_masked_before_the_provider() -> None:
    """②：写侧的掩码在 `_embed` 这个唯一出口上，与 Provider 是 offline 还是真实无关。"""

    embedder = RecordingEmbedder(1024)
    worker = make_worker(embedder=embedder)
    original = "联系方式：13800138000，邮箱 zhang@example.com，身份证 11010119900307123X。"
    await worker._embed([original])

    assert len(embedder.texts) == 1
    sent = embedder.texts[0]
    for secret in ("13800138000", "zhang@example.com", "11010119900307123X"):
        assert secret not in sent, secret
    assert "[PHONE]" in sent
    assert "[EMAIL]" in sent
    assert "[ID_CARD]" in sent


async def test_stored_content_stays_original_while_egress_is_masked() -> None:
    """边界两侧都要钉住：出本机的字节一定掩过，落库的正文与 content_hash 一定还是原文。

    第二半句不是「忘了掩」，而是设计如此——读侧掩码发生在召回之后（`rag/service.py`），
    词法通道也按原文命中；把这条写成断言，是为了让下一个人改的时候必须显式表态。
    """

    pii_text = "我的手机号是13800138000，备用邮箱 li@example.com。"
    data = pii_text.encode("utf-8")
    embedder = RecordingEmbedder(1024)
    writer = InMemoryVectorWriter()
    worker = make_worker(
        storage=InMemoryObjectStorage({OBJECT_KEY: data}),
        embedder=embedder,
        writer=writer,
    )

    assert await worker.handle("msg-17", body(data=data)) is HandleOutcome.PROCESSED
    records = writer.records(DOCUMENT_ID, "v1")
    assert records
    assert "13800138000" in "".join(record.content for record in records)
    assert records[0].metadata["content_hash"] == sha256(records[0].content.encode("utf-8")).hexdigest()
    assert "13800138000" not in "".join(embedder.texts)
    assert "li@example.com" not in "".join(embedder.texts)


async def test_without_traceparent_the_event_trace_id_is_annotated() -> None:
    settings = make_settings(chunk_size_chars=100, chunk_overlap_chars=20)
    exporter = InMemorySpanExporter()
    tracer, provider = build_tracing(settings, exporter=exporter)
    worker = make_worker(settings=settings, tracer=tracer)

    await worker.handle("msg-17", body())

    span = next(span for span in exporter.get_finished_spans() if span.name == "document.parse")
    assert span.attributes["trace_continuation"] is False
    assert span.attributes["expected_trace_id"] == TRACE_ID
    provider.shutdown()


async def test_experiment_budget_refusal_stays_a_retryable_embedding_failure(caplog) -> None:
    """②：超限沿用既有 retryable 形态，错误码零新增；日志要能把「额度拦下」与「端点失败」分开。

    注意既有语义：`ParseJobError` 一律走「发布 retryable 的 `parse-failed` 并 ack」
    （`HandleOutcome.PROCESSED`），重投由 Java 依据 `retryable` 决定；返回 `RETRY` 会
    把同一条消息交给 broker 再投一次，等于开第二条重试通道。
    """

    inner = RecordingEmbedder(1024)
    embedder = BudgetedEmbeddingProvider(inner, InMemoryExperimentBudgetStore(0, 0))
    publisher = InMemoryParsePublisher()
    worker = make_worker(publisher=publisher, embedder=embedder)

    with caplog.at_level(logging.WARNING, logger="docqa_ai.parse_worker.worker"):
        outcome = await worker.handle("msg-18", body())

    assert outcome is HandleOutcome.PROCESSED
    failed = publisher.failed[0]
    assert failed.error_code == "COM-INTERNAL-001"
    assert failed.retryable is True
    assert "向量化" in failed.error_message
    assert inner.batches == []
    record = next(item for item in caplog.records if getattr(item, "operation", None) == "embedding")
    assert record.step == "parse"
    assert record.status == "degraded"
    assert "experiment budget" in record.getMessage()


def test_worker_assembly_wraps_only_the_real_embedding_provider() -> None:
    """离线装配不构造账本也不写 Redis 键；切真必须套门禁，并且把端点自报的批量上限透传出去。"""

    offline = _build_embedder(make_settings(), FakeRedis(decode_responses=True))
    assert isinstance(offline, OfflineEmbeddingProvider)

    real = make_settings(
        embedding_provider="openai",
        embedding_base_url="https://example.invalid/compatible-mode/v1",
        pg_table="chunk_embedding_real",
        experiment_budget=200,
        experiment_token_budget=200_000,
    )
    wrapped = _build_embedder(real, FakeRedis(decode_responses=True))
    assert isinstance(wrapped, BudgetedEmbeddingProvider)
    assert wrapped.dimensions == real.embedding_dimensions
    assert wrapped.max_batch_rows == OPENAI_COMPATIBLE_MAX_BATCH_ROWS


def test_message_id_prefers_the_broker_id_and_never_uses_document_keys() -> None:
    class Message:
        msg_id = "broker-1"
        id = "fallback"
        keys = str(DOCUMENT_ID)

    assert _message_id(Message(), b"body") == "broker-1"

    class KeysOnly:
        keys = str(DOCUMENT_ID)

    hashed = _message_id(KeysOnly(), b"body")
    assert hashed == sha256(b"body").hexdigest()
    assert hashed != str(DOCUMENT_ID)
    assert hashed == _message_id(KeysOnly(), b"body")


def test_message_properties_support_both_client_shapes() -> None:
    class GetterMessage:
        def get_property(self, name: str) -> str:
            return "00-" + TRACE_ID + "-" + "1" * 16 + "-01"

    assert _property(GetterMessage(), "traceparent") == "00-" + TRACE_ID + "-" + "1" * 16 + "-01"
    assert _property(type("Message", (), {"properties": {"traceparent": "value"}})(), "traceparent") == "value"
    assert _property(object(), "traceparent") is None
