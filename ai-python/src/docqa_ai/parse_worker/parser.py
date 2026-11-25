from __future__ import annotations

import io
import re
import zipfile
from dataclasses import dataclass
from pathlib import PurePath
from xml.etree import ElementTree

from docx import Document
from pypdf import PdfReader
from pypdf.errors import PdfReadError

from docqa_ai.parse_worker.errors import (
    ENCRYPTED,
    INVALID_DOCUMENT,
    TOO_LARGE,
    UNSUPPORTED_TYPE,
    ParseJobError,
)
from docqa_ai.parse_worker.ocr import NoopOcrProvider, OcrProvider

PDF = "application/pdf"
DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
TEXT = "text/plain"

_SUFFIXES = {".pdf": PDF, ".docx": DOCX, ".pptx": PPTX, ".txt": TEXT, ".md": TEXT}
_SLIDE = re.compile(r"^ppt/slides/slide([0-9]+)\.xml$")

_EMPTY = "文档中没有可提取的文本。"
_CORRUPT = "文档内容无法解析，请重新上传。"


@dataclass(frozen=True)
class ParsedPage:
    text: str
    page_no: int | None


@dataclass(frozen=True)
class ParsedDocument:
    pages: tuple[ParsedPage, ...]

    @property
    def char_count(self) -> int:
        return sum(len(page.text) for page in self.pages)


class DocumentParser:
    """PDF/PPTX/DOCX/TXT text extraction with sanitized, contract-ready failures."""

    def __init__(
        self,
        *,
        max_chars: int = 2_000_000,
        max_pages: int = 500,
        ocr: OcrProvider | None = None,
    ) -> None:
        self._max_chars = max_chars
        self._max_pages = max_pages
        self._max_uncompressed_bytes = 100 * 1024 * 1024
        self._ocr = ocr or NoopOcrProvider()

    def parse(self, data: bytes, content_type: str, file_name: str) -> ParsedDocument:
        if not data:
            raise ParseJobError(INVALID_DOCUMENT, _EMPTY, retryable=False)
        kind = self._detect(content_type, file_name)
        if kind == PDF:
            document = self._parse_pdf(data)
        elif kind == DOCX:
            document = self._parse_docx(data)
        elif kind == PPTX:
            document = self._parse_pptx(data)
        else:
            document = self._parse_text(data)
        if not any(page.text.strip() for page in document.pages):
            raise ParseJobError(INVALID_DOCUMENT, _EMPTY, retryable=False)
        return document

    @staticmethod
    def _detect(content_type: str, file_name: str) -> str:
        normalized = (content_type or "").split(";", 1)[0].strip().lower()
        if normalized in {PDF, DOCX, PPTX, TEXT}:
            return normalized
        suffix = PurePath(file_name or "").suffix.lower()
        if suffix in _SUFFIXES:
            return _SUFFIXES[suffix]
        raise ParseJobError(UNSUPPORTED_TYPE, "仅支持 PDF、PPTX、DOCX 和 TXT。", retryable=False)

    def _parse_pdf(self, data: bytes) -> ParsedDocument:
        try:
            reader = PdfReader(io.BytesIO(data), strict=False)
        except (PdfReadError, ValueError, OSError) as exc:
            raise ParseJobError(INVALID_DOCUMENT, _CORRUPT, retryable=False) from exc
        if reader.is_encrypted:
            try:
                unlocked = reader.decrypt("")
            except Exception as exc:  # noqa: BLE001 - cryptography backends raise many types
                raise ParseJobError(ENCRYPTED, "无法解析加密的 PDF，请先解除密码保护。", retryable=False) from exc
            if getattr(unlocked, "value", unlocked) == 0:
                raise ParseJobError(ENCRYPTED, "无法解析加密的 PDF，请先解除密码保护。", retryable=False)
        if len(reader.pages) > self._max_pages:
            raise ParseJobError(TOO_LARGE, "文档页数过多，无法建立索引。", retryable=False)
        pages: list[ParsedPage] = []
        total = 0
        for page_no, page in enumerate(reader.pages, start=1):
            try:
                text = page.extract_text() or ""
            except Exception as exc:  # noqa: BLE001 - malformed PDF internals vary by parser
                raise ParseJobError(INVALID_DOCUMENT, _CORRUPT, retryable=False) from exc
            total += len(text)
            self._check_size(total)
            pages.append(ParsedPage(text=text, page_no=page_no))

        # 扫描件 / 图片型 PDF：文本层为空时走 OCR 兜底（默认 Noop，行为与旧版一致）。
        if not any(page.text.strip() for page in pages) and self._ocr.enabled:
            pages = self._ocr_pdf(data, page_count=len(pages))
        return ParsedDocument(pages=tuple(pages))

    def _ocr_pdf(self, data: bytes, *, page_count: int) -> list[ParsedPage]:
        """文本层全空时，把整份 PDF 交给 OCR provider 识别，逐页重建 ParsedPage。"""
        try:
            recognized = self._ocr.recognize(data)
        except Exception as exc:  # noqa: BLE001 - OCR 引擎异常不得外泄为解析契约
            raise ParseJobError(INVALID_DOCUMENT, _EMPTY, retryable=False) from exc
        if not isinstance(recognized, list) or len(recognized) != page_count:
            # OCR 返回页数与 PDF 实际页数不一致视为识别失败，退回「无文本」。
            return [ParsedPage(text="", page_no=i) for i in range(1, page_count + 1)]
        total = 0
        pages: list[ParsedPage] = []
        for page_no, text in enumerate(recognized, start=1):
            text = text or ""
            total += len(text)
            self._check_size(total)
            pages.append(ParsedPage(text=text, page_no=page_no))
        return pages

    def _parse_docx(self, data: bytes) -> ParsedDocument:
        self._check_zip(data)
        try:
            document = Document(io.BytesIO(data))
        except Exception as exc:  # noqa: BLE001 - python-docx wraps zip/xml failures inconsistently
            raise ParseJobError(INVALID_DOCUMENT, _CORRUPT, retryable=False) from exc
        lines = [paragraph.text for paragraph in document.paragraphs if paragraph.text.strip()]
        for table in document.tables:
            for row in table.rows:
                cells = [cell.text.strip() for cell in row.cells if cell.text.strip()]
                if cells:
                    lines.append(" | ".join(cells))
        text = "\n".join(lines)
        self._check_size(len(text))
        return ParsedDocument(pages=(ParsedPage(text=text, page_no=None),))

    def _parse_pptx(self, data: bytes) -> ParsedDocument:
        try:
            archive = zipfile.ZipFile(io.BytesIO(data))
        except zipfile.BadZipFile as exc:
            raise ParseJobError(INVALID_DOCUMENT, _CORRUPT, retryable=False) from exc
        with archive:
            total_uncompressed = sum(info.file_size for info in archive.infolist())
            if total_uncompressed > self._max_uncompressed_bytes:
                raise ParseJobError(TOO_LARGE, "文档内容过大，无法建立索引。", retryable=False)
            slides: list[tuple[int, str]] = []
            for name in archive.namelist():
                match = _SLIDE.fullmatch(name)
                if match:
                    slides.append((int(match.group(1)), name))
            slides.sort()
            if len(slides) > self._max_pages:
                raise ParseJobError(TOO_LARGE, "文档页数过多，无法建立索引。", retryable=False)
            pages: list[ParsedPage] = []
            total = 0
            for slide_no, name in slides:
                try:
                    root = ElementTree.fromstring(archive.read(name))
                except (ElementTree.ParseError, KeyError, RuntimeError) as exc:
                    raise ParseJobError(INVALID_DOCUMENT, _CORRUPT, retryable=False) from exc
                lines = [node.text for node in root.iter() if node.tag.endswith("}t") and node.text]
                text = "\n".join(line for line in lines if line.strip())
                total += len(text)
                self._check_size(total)
                pages.append(ParsedPage(text=text, page_no=slide_no))
        return ParsedDocument(pages=tuple(pages))

    def _parse_text(self, data: bytes) -> ParsedDocument:
        text: str | None = None
        for encoding in ("utf-8-sig", "gb18030"):
            try:
                text = data.decode(encoding)
                break
            except UnicodeDecodeError:
                continue
        if text is None or "\x00" in text:
            raise ParseJobError(INVALID_DOCUMENT, "文档编码无法识别，请转换为 UTF-8 后重新上传。", retryable=False)
        self._check_size(len(text))
        return ParsedDocument(pages=(ParsedPage(text=text, page_no=None),))

    def _check_zip(self, data: bytes) -> None:
        try:
            with zipfile.ZipFile(io.BytesIO(data)) as archive:
                compressed = sum(info.file_size for info in archive.infolist())
        except zipfile.BadZipFile as exc:
            raise ParseJobError(INVALID_DOCUMENT, _CORRUPT, retryable=False) from exc
        if compressed > self._max_uncompressed_bytes:
            raise ParseJobError(TOO_LARGE, "文档内容过大，无法建立索引。", retryable=False)

    def _check_size(self, total_chars: int) -> None:
        if total_chars > self._max_chars:
            raise ParseJobError(TOO_LARGE, "文档文本内容过大，无法建立索引。", retryable=False)
