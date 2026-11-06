from __future__ import annotations

from collections.abc import Callable, Iterable, Mapping

from opentelemetry.exporter.otlp.proto.http.metric_exporter import OTLPMetricExporter
from opentelemetry.metrics import Meter, Observation
from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.export import MetricReader, PeriodicExportingMetricReader
from opentelemetry.sdk.resources import Resource

_ServerStates = Callable[[], Mapping[str, bool]]


class ToolMetrics:
    """D-143 工具指标面：调用量、耗时、MCP Server 可达性与缓存命中。

    指标属性只放工具名、来源与结果，不放查询原文、URL query、用户标识或入参。
    """

    def __init__(self, meter: Meter) -> None:
        self._calls = meter.create_counter(
            "tool_calls_total",
            unit="1",
            description="Tool executions by tool, source and outcome",
        )
        self._latency = meter.create_histogram(
            "tool_latency_seconds",
            unit="s",
            description="Tool execution latency",
        )
        self._cache_hits = meter.create_counter(
            "tool_cache_hits_total",
            unit="1",
            description="Tool-side cache hits that avoided an outbound request",
        )
        self._servers: _ServerStates = dict
        meter.create_observable_gauge(
            "mcp_server_up",
            callbacks=[self._observe_servers],
            unit="1",
            description="Whether the last MCP discovery for a server succeeded",
        )

    def bind_server_states(self, source: _ServerStates) -> None:
        self._servers = source

    def record_tool_call(self, tool: str, source: str, outcome: str) -> None:
        self._calls.add(1, {"tool": tool, "source": source, "outcome": outcome})

    def record_tool_latency(self, tool: str, source: str, seconds: float) -> None:
        self._latency.record(max(0.0, seconds), {"tool": tool, "source": source})

    def record_cache_hit(self) -> None:
        self._cache_hits.add(1, {})

    def _observe_servers(self, options: object) -> Iterable[Observation]:
        return [Observation(1 if up else 0, {"server": name}) for name, up in self._servers().items()]


def build_metrics(
    settings: object,
    *,
    reader: MetricReader | None = None,
) -> tuple[ToolMetrics, MeterProvider]:
    """No reader ⇒ instruments still record but export nothing (local/tests stay offline)."""

    resource = Resource.create(
        {
            "service.name": getattr(settings, "service_name", "docqa-ai"),
            "deployment.environment": getattr(settings, "environment", "dev"),
        }
    )
    readers: list[MetricReader] = []
    if reader is not None:
        readers = [reader]
    elif getattr(settings, "otel_enabled", False):
        endpoint = getattr(settings, "otel_endpoint", "") or None
        readers = [PeriodicExportingMetricReader(OTLPMetricExporter(endpoint=endpoint))]
    provider = MeterProvider(resource=resource, metric_readers=readers)
    return ToolMetrics(provider.get_meter("docqa_ai")), provider
