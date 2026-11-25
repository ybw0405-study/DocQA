from __future__ import annotations

import base64
import re
from collections.abc import Mapping
from dataclasses import dataclass

from opentelemetry import trace
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
from opentelemetry.propagate import extract
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor, SimpleSpanProcessor, SpanExporter
from opentelemetry.sdk.trace.sampling import ParentBased, TraceIdRatioBased
from opentelemetry.trace import SpanKind, Status, StatusCode, Tracer
from opentelemetry.trace.propagation.tracecontext import TraceContextTextMapPropagator

_TRACEPARENT = re.compile(r"^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$")
_TRACE_ID = re.compile(r"^[0-9a-f]{32}$")


@dataclass(frozen=True)
class TraceContext:
    trace_id: str
    span_id: str
    sampled: bool


def parse_traceparent(value: str | None) -> TraceContext | None:
    if not value:
        return None
    match = _TRACEPARENT.match(value.strip())
    if match is None:
        return None
    trace_id, span_id, flags = match.groups()
    if trace_id == "0" * 32 or span_id == "0" * 16:
        return None
    return TraceContext(trace_id=trace_id, span_id=span_id, sampled=bool(int(flags, 16) & 0x01))


def is_trace_id(value: str | None) -> bool:
    return bool(value) and bool(_TRACE_ID.fullmatch(value)) and value != "0" * 32


def traceparent_for(trace_id: str, span_id: str | None = None, sampled: bool = True) -> str:
    span = span_id or "1" * 16
    flags = "01" if sampled else "00"
    return f"00-{trace_id}-{span}-{flags}"


def current_traceparent() -> str | None:
    context = trace.get_current_span().get_span_context()
    if not context.is_valid:
        return None
    flags = "01" if context.trace_flags.sampled else "00"
    return f"00-{context.trace_id:032x}-{context.span_id:016x}-{flags}"


def _secret_text(value: object) -> str:
    """SecretStr 与裸 str 都接受：测试注入的 settings 替身可能不带 pydantic 类型。"""
    getter = getattr(value, "get_secret_value", None)
    if callable(getter):
        return str(getter())
    return str(value) if value is not None else ""


def build_tracing(settings: object, exporter: SpanExporter | None = None) -> tuple[Tracer, TracerProvider]:
    """Build a tracer. Tests inject an in-memory exporter; production may use OTLP."""

    resource = Resource.create(
        {
            "service.name": getattr(settings, "service_name", "docqa-ai"),
            "deployment.environment": getattr(settings, "environment", "dev"),
        }
    )
    sampler = ParentBased(TraceIdRatioBased(getattr(settings, "otel_sample_ratio", 1.0)))
    provider = TracerProvider(resource=resource, sampler=sampler)
    if exporter is not None:
        provider.add_span_processor(SimpleSpanProcessor(exporter))
    elif getattr(settings, "otel_enabled", False):
        endpoint = getattr(settings, "otel_endpoint", "") or None
        provider.add_span_processor(BatchSpanProcessor(OTLPSpanExporter(endpoint=endpoint)))
    # LangFuse 第二路导出：挂在既有 processor 之后，同 spans 同 sampler，既有管道不动。
    # 开关关闭时不构造任何 exporter ⇒ 结构上零外呼，行为与改动前逐字节一致。
    # 密钥缺失/非回环未放行等配置错误由 Settings 的启动守卫拦下，这里不做静默降级。
    if getattr(settings, "langfuse_tracing_enabled", False):
        credentials = base64.b64encode(
            f"{_secret_text(getattr(settings, 'langfuse_public_key', ''))}:"
            f"{_secret_text(getattr(settings, 'langfuse_secret_key', ''))}".encode()
        ).decode()
        provider.add_span_processor(
            BatchSpanProcessor(
                OTLPSpanExporter(
                    endpoint=getattr(settings, "langfuse_otlp_endpoint", ""),
                    headers={"Authorization": f"Basic {credentials}"},
                )
            )
        )
    return provider.get_tracer("docqa_ai"), provider


class TraceContextMiddleware:
    """ASGI middleware that continues a W3C trace from the traceparent header."""

    def __init__(self, app: object, tracer: Tracer) -> None:
        self.app = app
        self.tracer = tracer

    async def __call__(self, scope: dict, receive: object, send: object) -> None:
        if scope.get("type") != "http":
            await self.app(scope, receive, send) # type: ignore[misc]
            return

        headers = {key.decode("latin-1").lower(): value.decode("latin-1") for key, value in scope.get("headers", [])}
        parsed = parse_traceparent(headers.get("traceparent"))
        parent_context = extract({"traceparent": headers["traceparent"]}) if parsed else None

        span_cm = self.tracer.start_as_current_span(
            "http.server.agent",
            context=parent_context,
            kind=SpanKind.SERVER,
        )
        span = span_cm.__enter__()
        span.set_attribute("http.request.method", scope.get("method", ""))
        span.set_attribute("http.route", scope.get("path", ""))
        span.set_attribute("trace_continuation", parsed is not None)

        state = scope.setdefault("state", {})
        state["header_trace_id"] = parsed.trace_id if parsed else None
        state["trace_continuation"] = parsed is not None

        async def wrapped_send(message: dict) -> None:
            if message.get("type") == "http.response.start":
                status = message.get("status")
                span.set_attribute("http.response.status_code", status)
                if status and status >= 500:
                    span.set_status(Status(StatusCode.ERROR))
            await send(message) # type: ignore[misc]

        try:
            await self.app(scope, receive, wrapped_send) # type: ignore[misc]
        except Exception as exc: # noqa: BLE001 - re-raised after marking the span
            span.set_attribute("error.type", type(exc).__name__)
            span.set_status(Status(StatusCode.ERROR))
            raise
        finally:
            span_cm.__exit__(None, None, None)


def annotate_expected_trace(trace_id: str, continuation: bool) -> None:
    span = trace.get_current_span()
    if not span.is_recording():
        return
    span.set_attribute("trace_continuation", continuation)
    if not continuation:
        span.set_attribute("expected_trace_id", trace_id)


# 方案 4.3：显式使用 W3C TraceContext 传播器，不依赖全局 propagator 是否被配置。
_PROPAGATOR = TraceContextTextMapPropagator()


def propagation_headers(carrier: dict[str, str] | None = None) -> dict[str, str]:
    """把当前 span 的 W3C 上下文注入出站载体（MQ 消息头 / HTTP 头）。

    调用方可无条件把返回值 merge 进 envelope：没有活跃 span 时返回空 dict，
    不会写出无效 traceparent。Java 侧对应 {@code com.docqa.common.trace.TracePropagation}。
    """
    target: dict[str, str] = carrier if carrier is not None else {}
    _PROPAGATOR.inject(target)
    return target


def context_from_headers(headers: Mapping[str, str]) -> object:
    """从入站载体提取父 context；无有效 traceparent 时返回空 context（不是 None）。"""
    return _PROPAGATOR.extract({key.lower(): value for key, value in headers.items()})
