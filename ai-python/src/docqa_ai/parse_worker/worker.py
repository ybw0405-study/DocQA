from __future__ import annotations

import asyncio
import hashlib
import logging
import time
import uuid
from collections.abc import Callable
from datetime import UTC, datetime
from enum import Enum

from opentelemetry import trace
from opentelemetry.propagate import extract
from opentelemetry.trace import Status, StatusCode, Tracer
from redis.asyncio import Redis

from docqa_ai.config import Settings
from docqa_ai.governance.experiment_budget import RedisExperimentBudgetStore
from docqa_ai.governance.experiment_egress import (
    BudgetedEmbeddingProvider,
    ExperimentBudgetExhausted,
)
from docqa_ai.logging_setup import log_event
from docqa_ai.parse_worker.chunker import chunk_document
from docqa_ai.parse_worker.errors import (
    INTERNAL,
    INVALID_DOCUMENT,
    TOO_LARGE,
    ParseJobError,
    PublishError,
)
from docqa_ai.parse_worker.idempotency import (
    BeginResult,
    IdempotencyStore,
    IdempotencyUnavailable,
)
from docqa_ai.parse_worker.models import (
    ParseCompletedEvent,
    ParsedChunk,
    ParseFailedEvent,
    ParseRequestedEvent,
    require_tenant_id,
)
from docqa_ai.parse_worker.parser import DocumentParser
from docqa_ai.parse_worker.publisher import ParseEventPublisher
from docqa_ai.parse_worker.storage import (
    ObjectNotFound,
    ObjectStorage,
    ObjectTooLarge,
    StorageUnavailable,
)
from docqa_ai.parse_worker.vector_writer import (
    VectorRecord,
    VectorWriteError,
    VectorWriter,
    make_vector_id,
)
from docqa_ai.pii.masker import mask_text
from docqa_ai.providers.embeddings import EmbeddingError, EmbeddingProvider
from docqa_ai.tracing import parse_traceparent

LOGGER = logging.getLogger(__name__)

_EMBEDDING_FAILED = "文档向量化失败，请稍后重试。"
_INDEX_FAILED = "文档索引写入失败，请稍后重试。"
_CHECKSUM_FAILED = "文件内容校验失败，请重新上传。"
_STORAGE_FAILED = "文档读取失败，请稍后重试。"


class HandleOutcome(Enum):
    PROCESSED = "PROCESSED"
    DUPLICATE = "DUPLICATE"
    INVALID = "INVALID"
    RETRY = "RETRY"


class ParseWorker:
    """One message at a time; the broker adapter decides ack/retry from ``HandleOutcome``."""

    def __init__(
        self,
        settings: Settings,
        *,
        storage: ObjectStorage,
        embedder: EmbeddingProvider,
        writer: VectorWriter,
        publisher: ParseEventPublisher,
        idempotency: IdempotencyStore,
        parser: DocumentParser | None = None,
        tracer: Tracer | None = None,
        clock: Callable[[], datetime] | None = None,
    ) -> None:
        self._settings = settings
        self._storage = storage
        self._embedder = embedder
        self._writer = writer
        self._publisher = publisher
        self._idempotency = idempotency
        self._parser = parser or DocumentParser(max_chars=settings.parse_max_extracted_chars)
        self._tracer = tracer or trace.get_tracer("docqa_ai")
        self._clock = clock or (lambda: datetime.now(UTC))

    async def handle(
        self,
        message_id: str,
        body: bytes,
        *,
        traceparent: str | None = None,
    ) -> HandleOutcome:
        parent = parse_traceparent(traceparent)
        parent_context = extract({"traceparent": traceparent}) if parent else None
        with self._tracer.start_as_current_span("document.parse", context=parent_context) as span:
            span.set_attribute("trace_continuation", parent is not None)
            try:
                begin = await self._idempotency.begin(message_id)
            except IdempotencyUnavailable:
                log_event(LOGGER, logging.WARNING, "Parse worker cannot acquire idempotency key", step="parse")
                return HandleOutcome.RETRY
            if begin is BeginResult.DONE:
                return HandleOutcome.DUPLICATE
            if begin is BeginResult.BUSY:
                return HandleOutcome.RETRY

            try:
                event = ParseRequestedEvent.model_validate_json(body)
            except ValueError:
                span.set_attribute("parse.status", "INVALID")
                log_event(LOGGER, logging.WARNING, "Ignoring invalid parse-requested event", step="parse")
                await self._finish(message_id)
                return HandleOutcome.INVALID

            # J1：租户信封缺失的消息必须拒收，不能进入写路径落库为空串（004 回归根因）。
            try:
                require_tenant_id(event.tenant_id)
            except ValueError:
                span.set_attribute("parse.status", "REJECTED_NO_TENANT")
                log_event(
                    LOGGER,
                    logging.ERROR,
                    "Rejected parse-requested event without tenantId",
                    step="parse",
                    errorCode="DOC-TENANT-001",
                )
                await self._finish(message_id)
                return HandleOutcome.INVALID

            span.set_attribute("document.tenant_id", str(event.tenant_id))
            span.set_attribute("document.id", str(event.document_id))
            span.set_attribute("document.index_version", event.index_version)
            span.set_attribute("document.retry_count", event.retry_count)
            if parent is None:
                span.set_attribute("expected_trace_id", event.trace_id)
            started = time.monotonic()
            try:
                chunks, vector_rows = await self._process(event)
                await self._publisher.publish_completed(
                    ParseCompletedEvent(
                        event_id=uuid.uuid4(),
                        trace_id=event.trace_id,
                        document_id=event.document_id,
                        index_version=event.index_version,
                        chunks=chunks,
                        vector_rows=vector_rows,
                        occurred_at=self._clock(),
                    )
                )
            except ParseJobError as exc:
                span.set_attribute("parse.status", "FAILED")
                span.set_attribute("error.code", exc.code)
                span.set_status(Status(StatusCode.ERROR, exc.code))
                log_event(
                    LOGGER,
                    logging.WARNING,
                    "Document parse failed",
                    step="parse",
                    traceId=event.trace_id,
                    documentId=str(event.document_id),
                    errorCode=exc.code,
                )
                try:
                    await self._publisher.publish_failed(
                        ParseFailedEvent(
                            event_id=uuid.uuid4(),
                            trace_id=event.trace_id,
                            document_id=event.document_id,
                            index_version=event.index_version,
                            error_code=exc.code,
                            error_message=exc.message,
                            retryable=exc.retryable,
                            occurred_at=self._clock(),
                        )
                    )
                except PublishError:
                    await self._release(message_id)
                    return HandleOutcome.RETRY
                await self._finish(message_id)
                return HandleOutcome.PROCESSED
            except PublishError:
                span.set_attribute("parse.status", "RETRY")
                span.set_status(Status(StatusCode.ERROR, "publish-failed"))
                await self._release(message_id)
                return HandleOutcome.RETRY
            except Exception:  # noqa: BLE001 - return the message to the broker without leaking details
                span.set_attribute("parse.status", "RETRY")
                span.set_status(Status(StatusCode.ERROR, "unexpected"))
                log_event(
                    LOGGER,
                    logging.ERROR,
                    "Parse worker failed unexpectedly",
                    step="parse",
                    traceId=event.trace_id,
                    documentId=str(event.document_id),
                    errorCode=INTERNAL,
                )
                await self._release(message_id)
                return HandleOutcome.RETRY

            span.set_attribute("parse.status", "COMPLETED")
            span.set_attribute("parse.chunk_count", len(chunks))
            span.set_attribute("parse.duration_ms", int((time.monotonic() - started) * 1000))
            await self._finish(message_id)
            log_event(
                LOGGER,
                logging.INFO,
                "Document parsed and indexed",
                step="parse",
                traceId=event.trace_id,
                documentId=str(event.document_id),
                chunkCount=len(chunks),
            )
            return HandleOutcome.PROCESSED

    async def _process(self, event: ParseRequestedEvent) -> tuple[list[ParsedChunk], int]:
        max_bytes = self._settings.worker_max_file_bytes
        if event.size_bytes > max_bytes:
            raise ParseJobError(TOO_LARGE, "文件不能超过 50MiB。", retryable=False)
        data = await self._read(event.object_key, max_bytes)
        if len(data) != event.size_bytes or hashlib.md5(data, usedforsecurity=False).hexdigest() != event.md5:
            raise ParseJobError(INVALID_DOCUMENT, _CHECKSUM_FAILED, retryable=False)

        # 解析是 CPU/IO 密集的同步调用，直接放在事件循环里会冻结信号与超时控制；
        # 与上面的 _read 保持一致，交给线程池执行。
        document = await asyncio.to_thread(self._parser.parse, data, event.content_type, event.file_name)
        text_chunks = chunk_document(
            document.pages,
            chunk_size=self._settings.chunk_size_chars,
            overlap=self._settings.chunk_overlap_chars,
        )
        if not text_chunks:
            raise ParseJobError(INVALID_DOCUMENT, "文档中没有可提取的文本。", retryable=False)

        vectors = await self._embed([chunk.content for chunk in text_chunks])
        records: list[VectorRecord] = []
        parsed: list[ParsedChunk] = []
        for chunk, embedding in zip(text_chunks, vectors, strict=True):
            content_hash = hashlib.sha256(chunk.content.encode("utf-8")).hexdigest()
            vector_id = make_vector_id(event.document_id, event.index_version, chunk.index)
            records.append(
                VectorRecord(
                    vector_id=vector_id,
                    chunk_index=chunk.index,
                    content=chunk.content,
                    embedding=embedding,
                    metadata={
                        "page_no": chunk.page_no,
                        "token_count": chunk.token_count,
                        "content_hash": content_hash,
                        "embedding_model": self._settings.embedding_model,
                        "file_name": event.file_name,
                    },
                )
            )
            parsed.append(
                ParsedChunk(
                    chunk_index=chunk.index,
                    vector_id=vector_id,
                    content_hash=content_hash,
                    token_count=chunk.token_count,
                )
            )
        try:
            rows_written = await self._writer.replace(
                event.document_id,
                event.index_version,
                records,
                tenant_id=require_tenant_id(event.tenant_id),
            )
        except VectorWriteError as exc:
            raise ParseJobError(INTERNAL, _INDEX_FAILED, retryable=True) from exc
        return parsed, rows_written

    async def _read(self, object_key: str, max_bytes: int) -> bytes:
        try:
            return await asyncio.to_thread(self._storage.get, object_key, max_bytes=max_bytes)
        except ObjectTooLarge as exc:
            raise ParseJobError(TOO_LARGE, "文件不能超过 50MiB。", retryable=False) from exc
        except ObjectNotFound as exc:
            raise ParseJobError(INTERNAL, _STORAGE_FAILED, retryable=True) from exc
        except StorageUnavailable as exc:
            raise ParseJobError(INTERNAL, _STORAGE_FAILED, retryable=True) from exc

    async def _embed(self, texts: list[str]) -> list[tuple[float, ...]]:
        timeout = self._settings.embedding_timeout_ms / 1000.0
        #  ②：掩码放在这个唯一的出口上，而不是调用点——「哪条路径忘了掩」正是需要结构上不可能发生的错误。
        # 与查询侧同源同规则（`pii.masker.VERSION = pii-v2`，与 Java 一致），因此两侧的向量口径对称。
        # 落库的 content 与 content_hash 仍是原文：读侧掩码在召回之后（`rag/service.py`），本条只管离开本机的字节。
        payload = [mask_text(text).masked for text in texts]
        # 配置值表达「一次愿意提交多少行」，Provider 自报值表达「端点最多收多少行」，
        # 取二者较小值：实测真实端点 11 行即 400，而默认配置是 32。
        batch_size = min(self._settings.embedding_batch_size, self._embedder.max_batch_rows)
        vectors: list[tuple[float, ...]] = []
        for start in range(0, len(payload), batch_size):
            batch = payload[start : start + batch_size]
            try:
                embedded = await asyncio.wait_for(
                    self._embedder.embed(batch, timeout),
                    timeout=timeout,
                )
            except ExperimentBudgetExhausted as exc:
                #  ②：超限沿用同一个 retryable 形态，错误码零新增；结构化日志把「额度拦下」与「端点失败」分开。
                log_event(
                    LOGGER,
                    logging.WARNING,
                    "Parse embedding refused by experiment budget",
                    step="parse",
                    operation="embedding",
                    status="degraded",
                )
                raise ParseJobError(INTERNAL, _EMBEDDING_FAILED, retryable=True) from exc
            except (TimeoutError, EmbeddingError) as exc:
                raise ParseJobError(INTERNAL, _EMBEDDING_FAILED, retryable=True) from exc
            except Exception as exc:  # noqa: BLE001 - provider transports raise varied errors
                raise ParseJobError(INTERNAL, _EMBEDDING_FAILED, retryable=True) from exc
            if len(embedded) != len(batch) or any(
                len(vector) != self._settings.embedding_dimensions for vector in embedded
            ):
                raise ParseJobError(INTERNAL, _EMBEDDING_FAILED, retryable=True)
            vectors.extend(tuple(float(value) for value in vector) for vector in embedded)
        return vectors

    async def _finish(self, message_id: str) -> None:
        try:
            await self._idempotency.complete(message_id)
        except IdempotencyUnavailable:
            log_event(LOGGER, logging.WARNING, "Unable to mark parse message as done", step="parse")

    async def _release(self, message_id: str) -> None:
        await self._idempotency.release(message_id)


def _build_embedder(settings: Settings, redis_client: Redis) -> EmbeddingProvider:
    """写侧只有一个外呼点：离线分支原样返回（不构造账本、一个 Redis 键都不写），切真即套额度门禁。"""

    if settings.embedding_provider == "offline":
        from docqa_ai.providers.embeddings import OfflineEmbeddingProvider

        return OfflineEmbeddingProvider(settings.embedding_model, settings.embedding_dimensions)
    from docqa_ai.providers.embeddings import OpenAIEmbeddingProvider

    provider = OpenAIEmbeddingProvider(
        settings.embedding_base_url,
        settings.embedding_api_key.get_secret_value(),
        settings.embedding_model,
        settings.embedding_dimensions,
    )
    return BudgetedEmbeddingProvider(
        provider,
        RedisExperimentBudgetStore(
            redis_client,
            settings.experiment_budget,
            settings.experiment_token_budget,
            settings.budget_key_prefix,
        ),
    )


def main() -> int:
    import signal
    import sys

    from docqa_ai.logging_setup import configure_logging
    from docqa_ai.parse_worker.idempotency import RedisIdempotencyStore
    from docqa_ai.parse_worker.publisher import RocketMqParsePublisher
    from docqa_ai.parse_worker.storage import MinioObjectStorage
    from docqa_ai.parse_worker.vector_writer import PgVectorWriter
    from docqa_ai.rag.pg_connection import build_pg_provider
    from docqa_ai.tracing import build_tracing

    settings = Settings()
    configure_logging(settings.log_level, settings.service_name)
    if not settings.mq_enabled:
        print("parse worker requires DOCQA_AI_MQ_ENABLED=true", file=sys.stderr)
        return 2
    if not settings.minio_enabled:
        print("parse worker requires DOCQA_AI_MINIO_ENABLED=true", file=sys.stderr)
        return 2
    if not settings.pg_dsn:
        print("parse worker requires DOCQA_AI_PG_DSN", file=sys.stderr)
        return 2

    tracer, provider = build_tracing(settings)
    # 单一组装根：worker 进程只建一次连接池，writer 共用；关闭时统一释放。
    pg_provider = build_pg_provider(settings.pg_dsn, settings.pg_connect_timeout_s)
    LOGGER.info("Parse worker starting: %s", settings.redacted_summary())
    redis_client = Redis.from_url(
        settings.redis_url,
        socket_timeout=settings.redis_socket_timeout_ms / 1000.0,
        socket_connect_timeout=settings.redis_socket_timeout_ms / 1000.0,
        decode_responses=True,
    )
    storage = MinioObjectStorage(
        settings.minio_endpoint,
        settings.minio_access_key.get_secret_value(),
        settings.minio_secret_key.get_secret_value(),
        settings.minio_bucket,
        secure=settings.minio_secure_effective,
    )
    publisher = RocketMqParsePublisher(
        settings.rocketmq_namesrv,
        settings.parse_producer_group,
        completed_topic=settings.parse_completed_topic,
        failed_topic=settings.parse_failed_topic,
        timeout_ms=settings.mq_send_timeout_ms,
    )
    worker = ParseWorker(
        settings,
        storage=storage,
        embedder=_build_embedder(settings, redis_client),
        writer=PgVectorWriter(
            settings.pg_dsn,
            settings.pg_table,
            dimensions=settings.embedding_dimensions,
            connect_timeout_s=settings.pg_connect_timeout_s,
            connection_provider=pg_provider,
        ),
        publisher=publisher,
        idempotency=RedisIdempotencyStore(
            redis_client,
            processing_ttl_s=settings.parse_processing_ttl_seconds,
            done_ttl_s=settings.mq_idempotency_ttl_seconds,
        ),
        tracer=tracer,
    )

    from docqa_ai.parse_worker.mq import ParseRequestedConsumer

    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    consumer = ParseRequestedConsumer(
        worker,
        namesrv=settings.rocketmq_namesrv,
        consumer_group=settings.parse_consumer_group,
        topic=settings.parse_requested_topic,
        loop=loop,
    )

    def stop(*_: object) -> None:
        loop.call_soon_threadsafe(loop.stop)

    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            signal.signal(sig, stop)
        except ValueError:
            pass

    try:
        consumer.start()
        loop.run_forever()
    except KeyboardInterrupt:
        pass
    finally:
        # loop.stop() 只停调度；mq.py 用 run_coroutine_threadsafe 投递的解析任务可能仍在途。
        # 先给在途任务一个收口窗口（带超时），再按序关闭共享资源，避免幽灵任务访问已关闭的
        # publisher/redis 并打出 "Task was destroyed but it is pending!"。
        consumer.shutdown()
        pending = [task for task in asyncio.all_tasks(loop) if not task.done()]
        if pending:
            try:
                loop.run_until_complete(asyncio.wait_for(asyncio.gather(*pending, return_exceptions=True), timeout=10))
            except TimeoutError:
                LOGGER.warning("Timed out draining %d in-flight parse tasks", len(pending))
        publisher.shutdown()
        loop.run_until_complete(pg_provider.aclose())
        loop.run_until_complete(redis_client.aclose())
        loop.close()
        provider.shutdown()
    return 0
