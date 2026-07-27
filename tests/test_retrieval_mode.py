from __future__ import annotations

import pytest

from services.meguri_core.memory import FakeMemoryProvider
from services.meguri_core.runtime import TurnOrchestrator
from services.meguri_core.schemas import LlmResponse, TurnRequest
from services.meguri_core.weather import WeatherContext


class TrackingRag:
    def __init__(self) -> None:
        self.calls = 0

    def search(self, _query, _state):
        self.calls += 1
        return ["local lore"]


class TrackingMemory(FakeMemoryProvider):
    def __init__(self) -> None:
        super().__init__()
        self.search_calls = 0

    async def search(self, request):
        self.search_calls += 1
        return await super().search(request)


class TrackingWeather:
    def __init__(self) -> None:
        self.calls = 0

    async def context_for(self, _request):
        self.calls += 1
        return WeatherContext(intent="none", briefing=None)


class StubLlm:
    provider_name = "retrieval-mode-test"

    async def respond(self, *_args, **_kwargs):
        return LlmResponse(reply="ok")


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("mode", "expected_rag", "expected_memory", "expected_weather"),
    [
        ("NONE", 0, 0, 0),
        ("fast", 1, 1, 0),
        ("SLOW", 1, 1, 1),
    ],
)
async def test_retrieval_mode_controls_python_runtime_lanes(
    mode: str,
    expected_rag: int,
    expected_memory: int,
    expected_weather: int,
) -> None:
    memory = TrackingMemory()
    weather = TrackingWeather()
    rag = TrackingRag()
    orchestrator = TurnOrchestrator(
        stream_interval=0,
        memory_provider=memory,
        llm_provider=StubLlm(),
        weather_service=weather,
    )
    orchestrator.rag = rag
    request = TurnRequest(
        user_id="retrieval-user",
        client_id="website",
        session_id=f"session-{mode.lower()}",
        message="天气和近况",
        formal_memory_allowed=True,
        retrieval_mode=mode,
    )

    result = await orchestrator.run_inline(request)

    assert result.response.reply == "ok"
    assert request.retrieval_mode == mode.upper()
    assert rag.calls == expected_rag
    assert memory.search_calls == expected_memory
    assert weather.calls == expected_weather
    retrieval_event = next(
        event
        for event in orchestrator.events[request.session_id]
        if event.type == "retrieval.completed"
    )
    assert retrieval_event.data["retrieval_mode"] == mode.upper()
