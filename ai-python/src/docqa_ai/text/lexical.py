"""词法召回通道的切分单一来源（D-92 / D-95 / D-96 / D-97）。

本模块同时导出两样东西，它们必须永远同源：

1. ``LEXICAL_TSVECTOR_SQL`` —— 与 ``migrations/002_chunk_lexical_index.sql`` 的索引表达式
   逐字一致的 SQL 片段，``WHERE`` 与 ``ORDER BY`` 都引用这一个常量（§4.4.3 末段的红线）；
2. ``extract_phrases`` / ``build_lexical_query`` —— 把一条已掩码的用户问句编译成
   ``to_tsquery`` 入参。入参是**语法而不是文本**（E23），所以只允许「已校验词位 +
   ``<->`` / ``|``」这一种形态，其余字符一律当分隔符丢弃。

与 ``OfflineEmbeddingProvider._tokens``（``providers/embeddings.py:63-70``）的关系是
**口径关系**而非结果相等（D-92 已写明这条差别）：本模块做 ASCII 整段与 CJK 相邻二元组，
PG 侧不做二元组也不做权重，两边不可能逐字节等价，等价测试断言的是词位集合的包含关系。
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from docqa_ai.pii.masker import PLACEHOLDERS

CJK_CHAR_CLASS = "一-鿿"
"""索引与查询共用的汉字字符类**内容**（不含方括号），两个使用点各自包 `[...]`。

渲染后的 `[一-鿿]` 必须与 002 DDL 逐字相同；漏掉方括号不会报错，只会让正则去匹配字面
三字符序列（见 ``LEXICAL_TSVECTOR_SQL`` 的说明），所以这条由测试钉住而不是靠约定。

只覆盖 U+4E00–U+9FFF。扩展 A 区（U+3400–U+4DBF）与兼容表意区（U+F900–U+FAFF）在
``simple`` 下会被粘成多字词位（E18），按单字发出的查询永远不命中且没有任何报错，
所以类外字符一律当分隔符处理、绝不发成词位（D-96）。
"""

LEXICAL_TSVECTOR_SQL = (
    f"to_tsvector('simple'::regconfig, regexp_replace(content, '([{CJK_CHAR_CLASS}])', ' \\1 ', 'g'))"
)
"""词法通量的 tsvector 表达式：``WHERE`` 谓词与 ``ORDER BY`` 里的 ``ts_rank`` 共用它。

形态敏感（E8/E21）：``regexp_replace`` 少写第 4 个参数 ``'g'`` 就从 ``Bitmap Index Scan``
退化成 ``Seq Scan``；括号也一样是语义的一部分——`'(一-鿿)'` 作为正则匹配的是字面三字符
序列，不是任何汉字，插不进空格就等于索引表达式与 002 的 DDL 不同源。函数名、参数个数、
flags、``regconfig`` 由 ``tests/test_migrations.py`` 与 002 的 DDL 文本逐字对账。
"""

LEXICAL_TSQUERY_SQL = "to_tsquery('simple'::regconfig, %s)"
"""词法通量的 tsquery 表达式。``%s`` 只接受 :func:`build_lexical_query` 的产物。"""

MAX_PHRASES = 64
"""单次查询最多发出的词组数。

这是**解析安全边界**而不是调优值：2000 字连写生成的 1999 个二元词组（27969 字符）被
``to_tsquery`` 接受，20000 个裸词位（119997 字符）报 ``ERROR: stack depth limit
exceeded``（E36）。截断规则确定——同一输入永远截在同一处，否则 RRF 名次不可复现。
"""

_TOKEN_RE = re.compile(
    f"[{CJK_CHAR_CLASS}]+|[a-z0-9]+(?:[._-][a-z0-9]+)*",
    re.UNICODE,
)

# 允许出现在 ASCII 词内部的连接符只有 `.` `_` `-` 三种，且两侧必须是字母数字：
# `config.py` 在 simple 解析器下是一个词位（点号不切分，E13），拆开发出的
# `'config' & 'py'` 在 5000 行含 config.py 的语料上命中 0 而真值是 5000（E14/D-95）；
# `vector_id` 与 `3.5-alpha` 整段交给 PG 时，PG 会把引号段拆成与正文侧同一套词位
# （E23/E28），所以「保留整段」在三种连接符上都成立。`+` 只实测过它作为正文侧分隔符
# （`C++ vector` → `'c':1 'vector':2`），本期不作为词内字符发出。
_ASCII_RUN_RE = re.compile(r"[a-z0-9]+(?:[._-][a-z0-9]+)*")

# 进入本模块的问句已经过 PII 掩码，因此可能字面含有 `[PHONE]` 这类占位符。它们是我们
# 自己插入的标记而不是用户意图，若不剔除就会发出 `'phone'` / `'id_card'` 这样的词位，
# 每个多余分支都在放大 OR 通道的假阳性。只复用 PLACEHOLDERS 这一个常量，
# 不复用 mask_text / normalize——后者做 NFKC，与查询侧口径冲突（E29）。
_MASK_PLACEHOLDER_RE = re.compile(
    "|".join(re.escape(value.lower()) for value in PLACEHOLDERS.values()),
    re.IGNORECASE,
)


@dataclass(frozen=True)
class LexicalPhrase:
    """一个词组：``surface`` 是小写化后问句里的连续片段，``tsquery`` 是交给 PG 的形态。

    ``surface`` 不是原文逐字切片（入参先经过 ``lower()``），只用于观测与调试展示。
    """

    surface: str
    tsquery: str


def extract_phrases(text: str, *, max_phrases: int = MAX_PHRASES) -> tuple[LexicalPhrase, ...]:
    """把问句编译成词组序列，按首次出现顺序去重并截断。

    词形口径（D-97）：ASCII 段整段保留，CJK 连写段取**相邻二元组**，全部用 `` | `` 连接。
    不使用 `` & ``——掺进一个语料中不存在的词位就能把 AND 通道整条判死（E25）。
    多字 CJK 裸词一律不发出：正文侧逐字插空格后不存在多字词位，发出即恒 0 命中（E26）。
    PII 掩码占位符先整体替换为空格再切分，占位符不贡献任何词位。
    """
    if not text:
        return ()
    # lower() 是**必要步骤**而非可选归一：_TOKEN_RE 只认小写，不折叠就会把 `Config` 当分隔符丢掉；
    # 而 PG 的 simple 两侧都做小写折叠，所以小写词位能命中混合大小写的正文（E38）。
    lowered = _MASK_PLACEHOLDER_RE.sub(" ", text.lower())
    phrases: list[LexicalPhrase] = []
    seen: set[str] = set()
    for match in _TOKEN_RE.finditer(lowered):
        token = match.group()
        for surface, tsquery in _forms(token):
            if tsquery in seen:
                continue
            seen.add(tsquery)
            phrases.append(LexicalPhrase(surface=surface, tsquery=tsquery))
            if len(phrases) >= max_phrases:
                return tuple(phrases)
    return tuple(phrases)


def build_lexical_query(text: str, *, max_phrases: int = MAX_PHRASES) -> str | None:
    """返回 ``to_tsquery`` 的入参；问句里没有任何可用词组时返回 ``None``。

    ``None`` 让调用方**跳过**整条词法查询，而不是发一个空串（空串合法但必然 0 命中，E23）。
    """
    phrases = extract_phrases(text, max_phrases=max_phrases)
    if not phrases:
        return None
    return " | ".join(phrase.tsquery for phrase in phrases)


def _forms(token: str) -> tuple[tuple[str, str], ...]:
    if _ASCII_RUN_RE.fullmatch(token):
        return ((token, _quote(token)),)
    if len(token) == 1:
        return ((token, _quote(token)),)
    return tuple((token[i : i + 2], f"{_quote(token[i])} <-> {_quote(token[i + 1])}") for i in range(len(token) - 1))


def _quote(lexeme: str) -> str:
    # 词位里的引号与反斜杠在 _TOKEN_RE 阶段就已被当作分隔符丢掉，引号包裹只是让 PG
    # 按与正文侧同一个解析器解释整段 ASCII（E23）；这里不做转义，因为不存在需要转义的字符。
    return f"'{lexeme}'"


__all__ = [
    "CJK_CHAR_CLASS",
    "LEXICAL_TSQUERY_SQL",
    "LEXICAL_TSVECTOR_SQL",
    "MAX_PHRASES",
    "LexicalPhrase",
    "build_lexical_query",
    "extract_phrases",
]
