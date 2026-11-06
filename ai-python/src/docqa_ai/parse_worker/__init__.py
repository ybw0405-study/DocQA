"""RocketMQ document workers.

The parse worker consumes ``parse-requested``, parses PDF/PPTX/DOCX/TXT, writes vectors to PGVector
and publishes ``parse-completed`` / ``parse-failed`` back to the Java document service.
The vector worker consumes ``vector-change-requested``, deletes one document or index-version scope
from PGVector and publishes ``vector-change-completed``. Neither worker writes MySQL.
"""

from docqa_ai.parse_worker.chunker import TextChunk, chunk_document
from docqa_ai.parse_worker.parser import DocumentParser, ParsedDocument, ParsedPage
from docqa_ai.parse_worker.vector_worker import VectorChangeWorker
from docqa_ai.parse_worker.worker import HandleOutcome, ParseWorker

__all__ = [
    "DocumentParser",
    "HandleOutcome",
    "ParseWorker",
    "ParsedDocument",
    "ParsedPage",
    "TextChunk",
    "VectorChangeWorker",
    "chunk_document",
]
