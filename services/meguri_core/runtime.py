from __future__ import annotations

from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

import yaml

from .config import BUILD_ID, DATA_ROOT, DEFAULT_TIMEZONE
from .providers import FakeMemoryProvider, MockLLMProvider, MockRagProvider
from .schemas import (
    ChatResponse, EventEnvelope, EventMetadata, LlmResponse, ResolvedExpression, RuntimeOverride,
    RuntimeState, TurnRequest, new_id,
)


class RuntimeStateMachine:
    tags = ["affectionate", "angry", "confused", "embarrassed", "excited", "happy", "neutral", "sad", "sleepy", "surprised", "teasing", "worried"]

    def __init__(self):
        self.overrides: dict[str, tuple[RuntimeOverride, datetime | None]] = {}

    def set_override(self, scope: str, override: RuntimeOverride) -> None:
        self.overrides[scope] = (override, override.expires_at)

    def clear_override(self, scope: str) -> None:
        self.overrides.pop(scope, None)

    def state_for(self, request: TurnRequest) -> RuntimeState:
        now = datetime.now(ZoneInfo(DEFAULT_TIMEZONE))
        is_holiday = now.weekday() >= 5
        hour = now.hour + now.minute / 60
        if hour >= 22 or hour < 8:
            outfit, mode = "04", "sleep"
        elif hour < 18:
            outfit, mode = ("02", "private") if is_holiday else ("01", "work")
        else:
            outfit, mode = "03", "private"
        relationship = "lover" if mode in {"private", "sleep"} else "sibling"
        override_entry = self.overrides.get(request.user_id) or self.overrides.get(f"{request.user_id}:{request.client_id}")
        if override_entry:
            override, expires_at = override_entry
            if expires_at and expires_at < now:
                self.overrides.pop(request.user_id, None)
            else:
                outfit = override.outfit_code or outfit
                mode = override.mode or mode
                relationship = override.relationship_profile or relationship
        if request.relationship_profile:
            relationship = request.relationship_profile
        return RuntimeState(
            client_id=request.client_id,
            mode=mode,
            relationship_profile=relationship,
            outfit_code=outfit,
            local_time=now.isoformat(),
            is_holiday=is_holiday,
            voice_enabled=request.client_capabilities.voice and request.client_id == "desktop_pet",
            screen_context_enabled=request.client_capabilities.screen_context and request.client_id == "desktop_pet",
            allowed_expression_tags=self.tags,
        )


class ExpressionResolver:
    def __init__(self, data_root: Path = DATA_ROOT):
        self.map: dict = {}
        for path in (data_root / "exports" / "expression_map" / "expression_map.json", data_root / "aligned_v1" / "catalogs" / "expression_asset_map.yaml"):
            if path.exists():
                try:
                    self.map = yaml.safe_load(path.read_text(encoding="utf-8")) if path.suffix == ".yaml" else __import__("json").loads(path.read_text(encoding="utf-8"))
                except Exception:
                    self.map = {}
                if self.map:
                    break

    def resolve(self, response: LlmResponse, state: RuntimeState) -> ResolvedExpression:
        tag = response.expression_tag if response.expression_tag in state.allowed_expression_tags else "neutral"
        outfit = state.outfit_code
        code = sprite_file = None
        if isinstance(self.map, list):
            for candidate_tag in (tag, "neutral"):
                matches = [
                    row for row in self.map
                    if row.get("outfit_code") == outfit
                    and row.get("expression_tag") == candidate_tag
                    and not row.get("excluded_default", False)
                    and row.get("size") == "l"
                ]
                exact = [row for row in matches if row.get("expression_intensity") == response.expression_intensity]
                chosen = (exact or matches or [None])[0]
                if chosen:
                    row_build_id = chosen.get("build_id")
                    if row_build_id and row_build_id != BUILD_ID:
                        raise RuntimeError(f"expression map build_id mismatch: expected {BUILD_ID}, got {row_build_id}")
                    code = chosen.get("expression_code")
                    sprite_file = Path(str(chosen.get("project_path", ""))).name or None
                    tag = candidate_tag
                    break
        elif isinstance(self.map, dict):
            variants = self.map.get("expressions", {}).get(tag, {}).get("variants", {})
            choices = variants.get(response.expression_intensity, {}).get(outfit, []) if isinstance(variants, dict) else []
            if not choices and isinstance(variants, dict):
                choices = variants.get("medium", {}).get(outfit, []) or variants.get("low", {}).get(outfit, [])
            if choices:
                code = choices[0]
                sprite_file = f"ce{outfit}{code}l.png"
        return ResolvedExpression(expression_tag=tag, expression_intensity=response.expression_intensity, outfit_code=outfit, expression_code=code, sprite_file=sprite_file)


class TurnOrchestrator:
    def __init__(self):
        self.state_machine = RuntimeStateMachine()
        self.rag = MockRagProvider(DATA_ROOT)
        self.memory = FakeMemoryProvider()
        self.llm = MockLLMProvider()
        self.resolver = ExpressionResolver(DATA_ROOT)
        self.events: dict[str, list[EventEnvelope]] = {}
        self.cancelled: set[str] = set()

    def _event(self, turn_id: str, request: TurnRequest, kind: str, data: dict, trace_id: str) -> EventEnvelope:
        stream = self.events.setdefault(request.session_id, [])
        event = EventEnvelope(type=kind, turn_id=turn_id, session_id=request.session_id, sequence=len(stream) + 1, data=data, metadata=EventMetadata(trace_id=trace_id, build_id=BUILD_ID))
        stream.append(event)
        return event

    def run(self, request: TurnRequest) -> ChatResponse:
        turn_id, trace_id = new_id("turn"), new_id("trace")
        state = self.state_machine.state_for(request)
        self._event(turn_id, request, "turn.started", {"runtime_state": state.model_dump(mode="json")}, trace_id)
        canon = self.rag.search(request.message, state)
        memories = self.memory.search(request.user_id, request.message)
        response = self.llm.respond(request, state, canon, memories)
        expression = self.resolver.resolve(response, state)
        for index, delta in enumerate(_chunks(response.reply), start=1):
            if turn_id in self.cancelled:
                self._event(turn_id, request, "turn.cancelled", {}, trace_id)
                return ChatResponse(turn_id=turn_id, session_id=request.session_id, response=response, runtime_state=state, expression=expression, memory_status="unavailable", build_id=BUILD_ID)
            self._event(turn_id, request, "text.delta", {"delta": delta, "index": index}, trace_id)
        self._event(turn_id, request, "text.completed", {"text": response.reply}, trace_id)
        self._event(turn_id, request, "expression.cue", expression.model_dump(mode="json"), trace_id)
        memory_status = self.memory.review_and_store(request.user_id, response.memory_candidates)
        for candidate in response.memory_candidates:
            self._event(turn_id, request, "memory.candidate.created", candidate.model_dump(), trace_id)
        self._event(turn_id, request, "turn.completed", {"reply": response.reply}, trace_id)
        return ChatResponse(turn_id=turn_id, session_id=request.session_id, response=response, runtime_state=state, expression=expression, memory_status=memory_status, build_id=BUILD_ID)

    def cancel(self, turn_id: str) -> bool:
        self.cancelled.add(turn_id)
        return True


def _chunks(text: str, size: int = 18):
    for start in range(0, len(text), size):
        yield text[start : start + size]
