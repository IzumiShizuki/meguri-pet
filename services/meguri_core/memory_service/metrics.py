from __future__ import annotations

from collections import defaultdict
from threading import Lock


REQUIRED_COUNTERS = (
    "memory_candidate_created_total",
    "memory_candidate_approved_total",
    "memory_candidate_rejected_total",
    "memory_embedding_failure_total",
    "memory_conflict_total",
    "memory_false_recall_feedback_total",
    "memory_provider_failure_total",
)
REQUIRED_GAUGES = (
    "memory_active_total",
    "memory_search_latency_ms",
    "memory_search_result_count",
    "memory_embedding_queue_depth",
)


class MemoryMetrics:
    """Small dependency-free Prometheus text collector with no dynamic labels."""

    def __init__(self) -> None:
        self._lock = Lock()
        self._counters = defaultdict(float, {name: 0.0 for name in REQUIRED_COUNTERS})
        self._gauges = defaultdict(float, {name: 0.0 for name in REQUIRED_GAUGES})

    def inc(self, name: str, amount: float = 1.0) -> None:
        if name not in REQUIRED_COUNTERS:
            raise KeyError(f"unknown memory counter: {name}")
        with self._lock:
            self._counters[name] += amount

    def set_gauge(self, name: str, value: float) -> None:
        if name not in REQUIRED_GAUGES:
            raise KeyError(f"unknown memory gauge: {name}")
        with self._lock:
            self._gauges[name] = value

    def add_gauge(self, name: str, amount: float) -> None:
        if name not in REQUIRED_GAUGES:
            raise KeyError(f"unknown memory gauge: {name}")
        with self._lock:
            self._gauges[name] = max(0.0, self._gauges[name] + amount)

    def render(self) -> str:
        with self._lock:
            counters = dict(self._counters)
            gauges = dict(self._gauges)
        lines: list[str] = []
        for name in REQUIRED_COUNTERS:
            lines.extend((f"# TYPE {name} counter", f"{name} {counters[name]:g}"))
        for name in REQUIRED_GAUGES:
            lines.extend((f"# TYPE {name} gauge", f"{name} {gauges[name]:g}"))
        return "\n".join(lines) + "\n"


memory_metrics = MemoryMetrics()
