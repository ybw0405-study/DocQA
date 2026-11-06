from __future__ import annotations

from collections.abc import Iterator
from typing import Any

from opentelemetry.sdk.metrics.export import InMemoryMetricReader

from docqa_ai.metrics import build_metrics
from tests.conftest import make_settings


def points(reader: InMemoryMetricReader, name: str) -> Iterator[Any]:
    data = reader.get_metrics_data()
    if data is None:
        return
    for resource_metrics in data.resource_metrics:
        for scope_metrics in resource_metrics.scope_metrics:
            for metric in scope_metrics.metrics:
                if metric.name == name:
                    yield from metric.data.data_points


def test_tool_metrics_record_calls_latency_and_cache_hits() -> None:
    reader = InMemoryMetricReader()
    metrics, provider = build_metrics(make_settings(), reader=reader)
    try:
        metrics.record_tool_call("calculator", "builtin", "success")
        metrics.record_tool_call("mcp__dict__lookup", "mcp", "rate_limited")
        metrics.record_tool_latency("calculator", "builtin", 0.25)
        metrics.record_cache_hit()

        calls = list(points(reader, "tool_calls_total"))
        assert sum(point.value for point in calls) == 2
        recorded = {
            (point.attributes["tool"], point.attributes["source"], point.attributes["outcome"]) for point in calls
        }
        assert recorded == {
            ("calculator", "builtin", "success"),
            ("mcp__dict__lookup", "mcp", "rate_limited"),
        }

        latency = list(points(reader, "tool_latency_seconds"))
        assert len(latency) == 1
        assert latency[0].count == 1
        assert latency[0].sum == 0.25
        assert latency[0].attributes == {"tool": "calculator", "source": "builtin"}

        assert sum(point.value for point in points(reader, "tool_cache_hits_total")) == 1
    finally:
        provider.shutdown()


def test_mcp_server_up_reports_the_last_discovery_state() -> None:
    reader = InMemoryMetricReader()
    metrics, provider = build_metrics(make_settings(), reader=reader)
    try:
        metrics.bind_server_states(lambda: {"time": True, "filesystem": False})
        states = {point.attributes["server"]: point.value for point in points(reader, "mcp_server_up")}
        assert states == {"time": 1, "filesystem": 0}
    finally:
        provider.shutdown()


def test_metrics_without_a_reader_stay_local() -> None:
    metrics, provider = build_metrics(make_settings())
    try:
        metrics.record_tool_call("calculator", "builtin", "success")
    finally:
        provider.shutdown()
