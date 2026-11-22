"""OCR 兜底 provider（可插拔，默认关闭）。

扫描件 / 图片型 PDF 没有文本层，pypdf 的 ``extract_text`` 只会得到空串。OCR 是
这类文档进入检索的唯一途径，但它依赖重（渲染 PDF 页为图像 + OCR 引擎，如
pypdfium2/paddleocr/tesseract/云 OCR，都非纯 Python），不能作为硬依赖塞进解析链路。

因此这里只定义协议与 Noop 兜底，把「渲染 + 识别」的整套重活封装在实现方内部：

- ``OcrProvider``：输入原始 PDF 字节与页数，输出每页文本；
- ``NoopOcrProvider``：默认实现，``enabled=False``，parser 检测到空文本层时直接走
  「无文本」失败路径，行为与旧版逐字节一致（结构上零外呼，不引入任何依赖）。

接入真实 OCR 时由调用方注入具体实现（例如在 worker 装配层按配置构建），本模块
不 import 任何渲染库或 OCR 引擎，保持解析 worker 的依赖面不变。
"""

from __future__ import annotations

from typing import Protocol


class OcrProvider(Protocol):
    """把整份 PDF 渲染并逐页 OCR。实现方负责渲染 + 引擎调用，parser 只传原始字节。"""

    @property
    def enabled(self) -> bool: ...

    def recognize(self, pdf_bytes: bytes) -> list[str]:
        """返回每页识别文本（list 长度 == 页数）；任一页失败返回空串，不抛异常。

        不抛异常的原因：OCR 引擎差异（缺库、语言包、云服务抖动）不该变成解析契约
        的破坏，失败页静默为空，最终由 parser 统一按「无文本」判定。
        """
        ...


class NoopOcrProvider:
    """默认实现：不启用 OCR，空文本层的 PDF 走既有失败路径。"""

    @property
    def enabled(self) -> bool:
        return False

    def recognize(self, pdf_bytes: bytes) -> list[str]:
        return []
