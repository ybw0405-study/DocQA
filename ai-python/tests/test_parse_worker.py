from __future__ import annotations

import io
import json
import uuid
import zipfile
from datetime import UTC, datetime
from hashlib import md5, sha256

import pytest
from docx import Document
from pydantic import ValidationError
from pypdf import PdfWriter

from docqa_ai.parse_worker.chunker import chunk_document
from docqa_ai.parse_worker.errors import (
    ENCRYPTED,
    INVALID_DOCUMENT,
    TOO_LARGE,
    UNSUPPORTED_TYPE,
    ParseJobError,
)
from docqa_ai.parse_worker.idempotency import (
    BeginResult,
    IdempotencyUnavailable,
    RedisIdempotencyStore,
)
from docqa_ai.parse_worker.models import (
    ParseCompletedEvent,
    ParsedChunk,
    ParseFailedEvent,
    ParseRequestedEvent,
)
from docqa_ai.parse_worker.parser import DOCX, PDF, PPTX, TEXT, DocumentParser, ParsedPage
from tests.conftest import DOCUMENT_ID

TRACE = "a" * 32


def requested_payload(data: bytes = b"hello", **overrides: object) -> dict[str, object]:
    payload: dict[str, object] = {
        "eventId": str(uuid.uuid4()),
        "traceId": TRACE,
        "documentId": str(DOCUMENT_ID),
        "userId": "11111111-1111-4111-8111-111111111111",
        "indexVersion": "v1",
        "objectKey": "uploads/doc.txt",
        "fileName": "doc.txt",
        "contentType": TEXT,
        "sizeBytes": len(data),
        "md5": md5(data, usedforsecurity=False).hexdigest(),
        "retryCount": 0,
        "occurredAt": datetime.now(UTC).isoformat(),
    }
    payload.update(overrides)
    return payload


def validate(payload: dict[str, object]) -> ParseRequestedEvent:
    return ParseRequestedEvent.model_validate_json(json.dumps(payload))


def test_parse_requested_event_accepts_java_camel_case_payload() -> None:
    event = validate(requested_payload())
    assert event.document_id == DOCUMENT_ID
    assert event.trace_id == TRACE
    assert event.retry_count == 0


@pytest.mark.parametrize(
    "overrides",
    [
        {"objectKey": "../etc/passwd"},
        {"objectKey": "/absolute"},
        {"objectKey": "uploads//doc.txt"},
        {"md5": "XYZ"},
        {"traceId": "A" * 32},
        {"indexVersion": "v 1"},
        {"sizeBytes": -1},
        {"retryCount": -1},
    ],
)
def test_parse_requested_event_rejects_invalid_fields(overrides: dict[str, object]) -> None:
    with pytest.raises(ValidationError):
        validate(requested_payload(**overrides))


def test_parse_requested_event_forbids_unknown_fields() -> None:
    with pytest.raises(ValidationError):
        validate(requested_payload(extra="x"))


def test_completed_event_requires_unique_indexes_and_vector_ids() -> None:
    chunk = ParsedChunk(
        chunk_index=0,
        vector_id=uuid.uuid4(),
        content_hash="a" * 64,
        token_count=10,
    )
    event = ParseCompletedEvent(
        event_id=uuid.uuid4(),
        trace_id=TRACE,
        document_id=DOCUMENT_ID,
        index_version="v1",
        chunks=[chunk],
        occurred_at=datetime.now(UTC),
    )
    assert event.status == "COMPLETED"
    with pytest.raises(ValidationError):
        ParseCompletedEvent(
            event_id=uuid.uuid4(),
            trace_id=TRACE,
            document_id=DOCUMENT_ID,
            index_version="v1",
            chunks=[chunk, chunk],
            occurred_at=datetime.now(UTC),
        )
    with pytest.raises(ValidationError):
        ParseCompletedEvent(
            event_id=uuid.uuid4(),
            trace_id=TRACE,
            document_id=DOCUMENT_ID,
            index_version="v1",
            chunks=[],
            occurred_at=datetime.now(UTC),
        )


def test_completed_event_only_accepts_completed_status() -> None:
    with pytest.raises(ValidationError):
        ParseCompletedEvent(
            event_id=uuid.uuid4(),
            trace_id=TRACE,
            document_id=DOCUMENT_ID,
            index_version="v1",
            status="FAILED",
            chunks=[
                ParsedChunk(
                    chunk_index=0,
                    vector_id=uuid.uuid4(),
                    content_hash="a" * 64,
                    token_count=1,
                )
            ],
            occurred_at=datetime.now(UTC),
        )


def test_failed_event_enforces_error_code_shape() -> None:
    event = ParseFailedEvent(
        event_id=uuid.uuid4(),
        trace_id=TRACE,
        document_id=DOCUMENT_ID,
        index_version="v1",
        error_code="DOC-VALIDATION-004",
        error_message="failure",
        retryable=False,
        occurred_at=datetime.now(UTC),
    )
    assert event.retryable is False
    with pytest.raises(ValidationError):
        ParseFailedEvent(
            event_id=uuid.uuid4(),
            trace_id=TRACE,
            document_id=DOCUMENT_ID,
            index_version="v1",
            error_code="doc-bad",
            error_message="failure",
            retryable=False,
            occurred_at=datetime.now(UTC),
        )


def test_text_parser_handles_utf8_and_gb18030() -> None:
    parser = DocumentParser()
    utf8 = parser.parse("智能学习助手".encode(), TEXT, "a.txt")
    assert utf8.pages[0].text == "智能学习助手"
    gb = parser.parse("智能学习助手".encode("gb18030"), TEXT, "a.txt")
    assert gb.pages[0].text == "智能学习助手"


def test_text_parser_rejects_binary_content() -> None:
    with pytest.raises(ParseJobError) as captured:
        DocumentParser().parse(b"hello\x00world", TEXT, "a.txt")
    assert captured.value.code == INVALID_DOCUMENT
    assert captured.value.retryable is False


def test_unsupported_type_is_non_retryable() -> None:
    with pytest.raises(ParseJobError) as captured:
        DocumentParser().parse(b"data", "image/png", "a.png")
    assert captured.value.code == UNSUPPORTED_TYPE
    assert captured.value.retryable is False


def test_empty_document_is_rejected() -> None:
    with pytest.raises(ParseJobError) as captured:
        DocumentParser().parse(b"", TEXT, "a.txt")
    assert captured.value.code == INVALID_DOCUMENT


def test_max_chars_limit_is_enforced() -> None:
    with pytest.raises(ParseJobError) as captured:
        DocumentParser(max_chars=5).parse(b"0123456789", TEXT, "a.txt")
    assert captured.value.code == TOO_LARGE


def test_docx_parser_reads_paragraphs_and_tables() -> None:
    document = Document()
    document.add_paragraph("第一段")
    table = document.add_table(rows=1, cols=2)
    table.rows[0].cells[0].text = "左"
    table.rows[0].cells[1].text = "右"
    buffer = io.BytesIO()
    document.save(buffer)
    parsed = DocumentParser().parse(buffer.getvalue(), DOCX, "a.docx")
    assert "第一段" in parsed.pages[0].text
    assert "左 | 右" in parsed.pages[0].text


def test_corrupt_docx_is_rejected() -> None:
    with pytest.raises(ParseJobError) as captured:
        DocumentParser().parse(b"not a zip", DOCX, "a.docx")
    assert captured.value.code == INVALID_DOCUMENT


def test_pptx_parser_reads_slides_in_numeric_order() -> None:
    namespace = (
        'xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" '
        'xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"'
    )
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        archive.writestr(
            "ppt/slides/slide2.xml",
            f"<p:sld {namespace}><a:t>Second</a:t></p:sld>",
        )
        archive.writestr(
            "ppt/slides/slide1.xml",
            f"<p:sld {namespace}><a:t>First</a:t></p:sld>",
        )
    parsed = DocumentParser().parse(buffer.getvalue(), PPTX, "a.pptx")
    assert [(page.page_no, page.text) for page in parsed.pages] == [(1, "First"), (2, "Second")]


def test_encrypted_pdf_is_rejected_as_non_retryable() -> None:
    writer = PdfWriter()
    writer.add_blank_page(width=72, height=72)
    writer.encrypt("secret")
    buffer = io.BytesIO()
    writer.write(buffer)
    with pytest.raises(ParseJobError) as captured:
        DocumentParser().parse(buffer.getvalue(), PDF, "a.pdf")
    assert captured.value.code == ENCRYPTED
    assert captured.value.retryable is False


def test_blank_pdf_has_no_extractable_text() -> None:
    writer = PdfWriter()
    writer.add_blank_page(width=72, height=72)
    buffer = io.BytesIO()
    writer.write(buffer)
    with pytest.raises(ParseJobError) as captured:
        DocumentParser().parse(buffer.getvalue(), PDF, "a.pdf")
    assert captured.value.code == INVALID_DOCUMENT


def test_chunker_honors_size_overlap_and_page_numbers() -> None:
    text = "段落一。" * 40
    pages = [ParsedPage(text=text, page_no=3), ParsedPage(text="", page_no=4), ParsedPage(text="尾巴", page_no=5)]
    chunks = chunk_document(pages, chunk_size=100, overlap=20)
    assert len(chunks) > 1
    assert [chunk.index for chunk in chunks] == list(range(len(chunks)))
    assert all(len(chunk.content) <= 100 for chunk in chunks)
    assert chunks[0].page_no == 3
    assert chunks[-1].page_no == 5
    assert all(chunk.token_count > 0 for chunk in chunks)


def test_chunker_makes_progress_with_large_overlap() -> None:
    chunks = chunk_document([ParsedPage(text="x" * 200, page_no=None)], chunk_size=100, overlap=99)
    assert len(chunks) < 20
    assert all(len(chunk.content) <= 100 for chunk in chunks)


def test_chunker_rejects_invalid_configuration() -> None:
    with pytest.raises(ValueError):
        chunk_document([], chunk_size=100, overlap=100)


async def test_redis_idempotency_state_machine() -> None:
    from fakeredis.aioredis import FakeRedis
    from redis.exceptions import RedisError

    redis = FakeRedis(decode_responses=False)
    store = RedisIdempotencyStore(redis, processing_ttl_s=60, done_ttl_s=600)
    assert await store.begin("m1") is BeginResult.ACQUIRED
    assert await store.begin("m1") is BeginResult.BUSY
    await store.complete("m1")
    assert await store.begin("m1") is BeginResult.DONE
    await store.release("m1")
    assert await store.begin("m1") is BeginResult.ACQUIRED
    ttl = await redis.ttl("mq:idem:parse-requested:m1")
    assert 0 < ttl <= 60
    await redis.aclose()

    class FailingRedis:
        async def set(self, *args: object, **kwargs: object) -> bool:
            raise RedisError("down")

    with pytest.raises(IdempotencyUnavailable):
        await RedisIdempotencyStore(FailingRedis()).begin("m2")  # type: ignore[arg-type]


def test_content_hash_shape_matches_java_expectations() -> None:
    digest = sha256("内容".encode()).hexdigest()
    assert len(digest) == 64
    assert digest == digest.lower()
