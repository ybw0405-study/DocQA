from __future__ import annotations

import re
from dataclasses import dataclass

from lxml import etree, html

_CHARSET = re.compile(r"charset\s*=\s*[\"']?([a-zA-Z0-9._-]{1,32})", re.IGNORECASE)
_META_CHARSET = re.compile(rb"<meta[^>]+charset\s*=\s*[\"']?([a-zA-Z0-9._-]{1,32})", re.IGNORECASE)
_DROP_TAGS = frozenset(
    {
        "script",
        "style",
        "noscript",
        "template",
        "svg",
        "canvas",
        "iframe",
        "object",
        "embed",
        "form",
        "button",
        "select",
        "textarea",
        "input",
    }
)
_BLOCK_TAGS = frozenset(
    {
        "address",
        "article",
        "aside",
        "blockquote",
        "dd",
        "div",
        "dl",
        "dt",
        "fieldset",
        "figcaption",
        "figure",
        "footer",
        "form",
        "h1",
        "h2",
        "h3",
        "h4",
        "h5",
        "h6",
        "header",
        "hr",
        "li",
        "main",
        "nav",
        "ol",
        "p",
        "pre",
        "section",
        "table",
        "tbody",
        "td",
        "th",
        "thead",
        "tr",
        "ul",
    }
)
_MAX_SNIFF_BYTES = 4096


@dataclass(frozen=True)
class ExtractedPage:
    title: str
    text: str
    truncated: bool


def _decode(body: bytes, content_type: str) -> str:
    charset = ""
    match = _CHARSET.search(content_type)
    if match:
        charset = match.group(1)
    else:
        meta = _META_CHARSET.search(body[:_MAX_SNIFF_BYTES])
        if meta:
            charset = meta.group(1).decode("ascii", "ignore")
    for candidate in (charset, "utf-8"):
        if not candidate:
            continue
        try:
            return body.decode(candidate, "replace")
        except LookupError:
            continue
    return body.decode("utf-8", "replace")


def _parser() -> etree.HTMLParser:
    # no_network / resolve_entities=False：第三方页面不得触发任何外部实体解析。
    return html.HTMLParser(recover=True, no_network=True, huge_tree=False, encoding=None)


def _visible_text(root: etree._Element) -> str:
    parts: list[str] = []
    for element in root.iter():
        if not isinstance(element.tag, str):
            continue
        tag = element.tag.lower()
        if tag in _BLOCK_TAGS:
            parts.append("\n")
        elif tag == "br":
            parts.append("\n")
        if element.text:
            parts.append(element.text)
        if element.tail:
            parts.append(element.tail)
    text = "".join(parts)
    text = re.sub(r"[ \t\r\f\v\u00a0]+", " ", text)
    text = re.sub(r" *\n *", "\n", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def extract_html(body: bytes, content_type: str = "", *, max_chars: int) -> ExtractedPage:
    if not body:
        return ExtractedPage("", "", False)
    document = html.document_fromstring(_decode(body, content_type), parser=_parser())
    for node in list(document.iter()):
        if isinstance(node.tag, str) and node.tag.lower() in _DROP_TAGS:
            node.drop_tree()
    title = ""
    for candidate in (document.xpath("//article//h1"), document.xpath("//main//h1"), document.xpath("//h1")):
        if candidate and candidate[0].text_content().strip():
            title = candidate[0].text_content().strip()
            break
    if not title:
        title_node = document.find(".//title")
        if title_node is not None:
            title = title_node.text_content().strip()
    root = document
    for expression in ("//article", "//main", "//body"):
        found = document.xpath(expression)
        if found:
            root = found[0]
            break
    text = _visible_text(root)
    truncated = len(text) > max_chars
    if truncated:
        text = text[:max_chars].rstrip()
    return ExtractedPage(title=title[:300], text=text, truncated=truncated)


def extract_plain(body: bytes, content_type: str = "", *, max_chars: int) -> ExtractedPage:
    text = _decode(body, content_type)
    text = re.sub(r"[ \t\r\f\v\u00a0]+", " ", text)
    text = re.sub(r"[ \t]*\n[ \t]*", "\n", text)
    text = re.sub(r"\n{3,}", "\n\n", text).strip()
    truncated = len(text) > max_chars
    return ExtractedPage("", text[:max_chars].rstrip() if truncated else text, truncated)
