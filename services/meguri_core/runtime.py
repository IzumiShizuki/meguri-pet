from __future__ import annotations

import asyncio
import inspect
import json
import re
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Callable, Literal
from zoneinfo import ZoneInfo

import yaml

from .config import BUILD_ID, CONFIG_ROOT, DATA_ROOT, DEFAULT_TIMEZONE
from .memory import (
    CompanionMemoryPolicy,
    MemoryExtractionInput,
    MemoryProvider,
    MemorySearchInput,
    SessionContextStore,
    SessionMessage,
)
from .memory_provider_factory import create_memory_provider_from_env
from .providers import LlmProvider, create_llm_provider_from_env
from .rag_api import create_rag_provider_from_env
from .schemas import (
    ChatResponse, EventEnvelope, EventMetadata, LlmResponse, ResolvedExpression, RuntimeOverride,
    RuntimeState, TurnRequest, new_id,
)
from .weather import WeatherContext, WeatherService


TurnStatus = Literal["accepted", "running", "completed", "failed", "cancelled"]


@dataclass
class TurnRecord:
    turn_id: str
    trace_id: str
    request: TurnRequest
    status: TurnStatus = "accepted"
    result: ChatResponse | None = None
    error: str | None = None
    cancel_requested: asyncio.Event = field(default_factory=asyncio.Event)
    done: asyncio.Event = field(default_factory=asyncio.Event)


class RuntimeStateMachine:
    tags = ["affectionate", "angry", "confused", "embarrassed", "excited", "happy", "neutral", "sad", "sleepy", "surprised", "teasing", "worried"]

    def __init__(self, now: Callable[[], datetime] | None = None):
        self.overrides: dict[str, tuple[RuntimeOverride, datetime | None]] = {}
        self._now = now or (lambda: datetime.now(ZoneInfo(DEFAULT_TIMEZONE)))

    def set_override(self, scope: str, override: RuntimeOverride) -> None:
        self.overrides[scope] = (override, override.expires_at)

    def clear_override(self, scope: str) -> None:
        self.overrides.pop(scope, None)

    def state_for(self, request: TurnRequest) -> RuntimeState:
        now = self._now()
        is_holiday = now.weekday() >= 5
        hour = now.hour + now.minute / 60
        if hour >= 22 or hour < 8:
            outfit, mode = "04", "sleep"
        elif hour < 18:
            outfit, mode = ("02", "private") if is_holiday else ("01", "work")
        else:
            outfit, mode = "03", "private"
        relationship = "sibling"
        user_entry = self._active_override(request.user_id, now)
        client_entry = self._active_override(
            f"{request.user_id}:{request.client_id}", now
        )
        user_override = user_entry[0] if user_entry else None
        client_override = client_entry[0] if client_entry else None
        outfit = (
            (user_override.outfit_code if user_override else None)
            or (client_override.outfit_code if client_override else None)
            or outfit
        )
        mode = (
            (user_override.mode if user_override else None)
            or (client_override.mode if client_override else None)
            or mode
        )
        if user_override and user_override.relationship_profile:
            relationship = user_override.relationship_profile
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

    def _active_override(
        self, scope: str, now: datetime
    ) -> tuple[RuntimeOverride, datetime | None] | None:
        entry = self.overrides.get(scope)
        if entry is None:
            return None
        _, expires_at = entry
        if expires_at is not None and expires_at <= now:
            if self.overrides.get(scope) is entry:
                self.overrides.pop(scope, None)
            return None
        return entry


class ExpressionResolver:
    def __init__(
        self,
        data_root: Path = DATA_ROOT,
        curated_map_path: Path = CONFIG_ROOT / "meguri_sprite_runtime_map.json",
    ):
        self.map: dict = {}
        self.curated_map: dict = {}
        try:
            loaded = json.loads(curated_map_path.read_text(encoding="utf-8"))
            self.curated_map = loaded if isinstance(loaded, dict) else {}
        except (OSError, json.JSONDecodeError):
            self.curated_map = {}
        for path in (data_root / "exports" / "expression_map" / "expression_map.json", data_root / "aligned_v1" / "catalogs" / "expression_asset_map.yaml"):
            if path.exists():
                try:
                    self.map = yaml.safe_load(path.read_text(encoding="utf-8")) if path.suffix == ".yaml" else json.loads(path.read_text(encoding="utf-8"))
                except Exception:
                    self.map = {}
                if self.map:
                    break

    def resolve(self, response: LlmResponse, state: RuntimeState) -> ResolvedExpression:
        tag = response.expression_tag if response.expression_tag in state.allowed_expression_tags else "neutral"
        outfit = state.outfit_code
        code = sprite_file = None
        curated = self.curated_map.get("expressions", {})
        curated_tag = curated.get(tag, {}) if isinstance(curated, dict) else {}
        curated_code = curated_tag.get(response.expression_intensity) if isinstance(curated_tag, dict) else None
        if isinstance(curated_code, str) and re.fullmatch(r"\d{3}", curated_code):
            # This hand-reviewed runtime map takes precedence over the source export,
            # whose expression labels are largely heuristic placeholders.
            code = curated_code
            sprite_file = f"ce{outfit}{code}m.png"
        elif isinstance(self.map, list):
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


_DEFAULT_EXPRESSION_CUES: tuple[tuple[str, re.Pattern[str], str], ...] = (
    ("sleepy", re.compile(r"困|睡|晚安|失眠|眠い|寝る|おやすみ|\bsleep", re.IGNORECASE), "low"),
    ("embarrassed", re.compile(r"害羞|脸红|夸奖|恥ず|照れ|\bembarrass", re.IGNORECASE), "medium"),
    ("sad", re.compile(r"难过|伤心|失望|哭|辛い|悲しい|泣|\bsad|\bdisappoint", re.IGNORECASE), "medium"),
    ("worried", re.compile(r"担心|不安|焦虑|心配|大丈夫|\bworr", re.IGNORECASE), "medium"),
    ("angry", re.compile(r"生气|愤怒|烦|ムカつ|怒って|\bangry", re.IGNORECASE), "medium"),
    ("surprised", re.compile(r"惊讶|吓到|没想到|びっくり|まさか|\bsurpris", re.IGNORECASE), "medium"),
    ("happy", re.compile(r"开心|高兴|好消息|合格|恭喜|嬉しい|楽しい|おめで|\bhappy|\bgood news", re.IGNORECASE), "medium"),
)


def apply_default_expression_cue(response: LlmResponse, message: str) -> LlmResponse:
    """Only replace an LLM's neutral placeholder when the user signal is unambiguous."""

    if response.expression_tag != "neutral" or response.expression_intensity != "low":
        return response
    for tag, pattern, intensity in _DEFAULT_EXPRESSION_CUES:
        if pattern.search(message):
            return response.model_copy(
                update={"expression_tag": tag, "expression_intensity": intensity}
            )
    return response


class TurnOrchestrator:
    terminal_statuses = {"completed", "failed", "cancelled"}

    def __init__(
        self,
        stream_interval: float = 0.01,
        memory_provider: MemoryProvider | None = None,
        llm_provider: LlmProvider | None = None,
        weather_service: WeatherService | None = None,
    ):
        self.state_machine = RuntimeStateMachine()
        self.rag = create_rag_provider_from_env(DATA_ROOT)
        self.memory: MemoryProvider = memory_provider or create_memory_provider_from_env()
        self.memory_policy = CompanionMemoryPolicy()
        self.sessions = SessionContextStore()
        self.llm = llm_provider or create_llm_provider_from_env()
        self.resolver = ExpressionResolver(DATA_ROOT)
        self.weather = weather_service or WeatherService()
        self.events: dict[str, list[EventEnvelope]] = {}
        self.turns: dict[str, TurnRecord] = {}
        self.idempotency: dict[tuple[str, str, str, str], str] = {}
        self.conditions: dict[str, asyncio.Condition] = {}
        self.tasks: set[asyncio.Task] = set()
        self.stream_interval = stream_interval

    async def _event(self, turn_id: str, request: TurnRequest, kind: str, data: dict, trace_id: str) -> EventEnvelope:
        stream = self.events.setdefault(request.session_id, [])
        required = kind in {
            "turn.started",
            "text.completed",
            "turn.completed",
            "turn.cancelled",
            "turn.failed",
        }
        event = EventEnvelope(
            required=required,
            type=kind,
            turn_id=turn_id,
            session_id=request.session_id,
            sequence=len(stream) + 1,
            data=data,
            metadata=EventMetadata(trace_id=trace_id, build_id=BUILD_ID),
        )
        stream.append(event)
        condition = self.conditions.setdefault(request.session_id, asyncio.Condition())
        async with condition:
            condition.notify_all()
        return event

    async def start(self, request: TurnRequest, idempotency_key: str | None = None) -> TurnRecord:
        if idempotency_key:
            key = (request.user_id, request.client_id, request.session_id, idempotency_key)
            existing_id = self.idempotency.get(key)
            if existing_id:
                return self.turns[existing_id]
        record = TurnRecord(turn_id=new_id("turn"), trace_id=new_id("trace"), request=request)
        self.turns[record.turn_id] = record
        if idempotency_key:
            self.idempotency[(request.user_id, request.client_id, request.session_id, idempotency_key)] = record.turn_id
        task = asyncio.create_task(self._run_record(record), name=f"meguri-{record.turn_id}")
        self.tasks.add(task)
        task.add_done_callback(self.tasks.discard)
        return record

    async def run_inline(self, request: TurnRequest) -> ChatResponse:
        record = TurnRecord(turn_id=new_id("turn"), trace_id=new_id("trace"), request=request)
        self.turns[record.turn_id] = record
        await self._run_record(record)
        if record.result is None:
            raise RuntimeError(record.error or f"turn ended with status {record.status}")
        return record.result

    async def _run_record(self, record: TurnRecord) -> None:
        request = record.request
        turn_id, trace_id = record.turn_id, record.trace_id
        record.status = "running"
        state = self.state_machine.state_for(request)
        try:
            await self._event(turn_id, request, "turn.started", {"runtime_state": state.model_dump(mode="json")}, trace_id)
            local_retrieval = request.retrieval_mode != "NONE"
            open_retrieval = request.retrieval_mode == "SLOW"
            if local_retrieval:
                canon_result = self.rag.search(request.message, state)
                canon = await canon_result if inspect.isawaitable(canon_result) else canon_result
            else:
                canon = []
            memory_available = request.formal_memory_allowed and local_retrieval
            if memory_available:
                try:
                    memory_hits = await self.memory.search(MemorySearchInput(user_id=request.user_id, query=request.message))
                except Exception:
                    memory_hits = []
                    memory_available = False
            else:
                memory_hits = []
            recent_messages = self.sessions.recent(request.user_id, request.client_id, request.session_id)
            weather = (
                await self.weather.context_for(request)
                if open_retrieval
                else WeatherContext(intent="none", briefing=None)
            )
            await self._event(
                turn_id,
                request,
                "retrieval.completed",
                {
                    "retrieval_mode": request.retrieval_mode,
                    "lanes": {
                        "lore": "enabled" if local_retrieval else "disabled",
                        "memory": "enabled" if memory_available else "disabled",
                        "weather": "enabled" if open_retrieval else "disabled",
                        "web": "not_configured" if open_retrieval else "disabled",
                    },
                },
                trace_id,
            )
            recent_context = [f"{message.role}: {message.content}" for message in recent_messages]
            weather_prompt_context = weather.prompt_context()
            if weather_prompt_context:
                recent_context.append(weather_prompt_context)
            response = await self.llm.respond(
                request,
                state,
                canon,
                [hit.record.canonical_text for hit in memory_hits],
                recent_context,
            )
            response = apply_default_expression_cue(response, request.message)
            response = weather.decorate_response(response, request.reply_format)
            self.sessions.append(
                request.user_id,
                request.client_id,
                request.session_id,
                SessionMessage(role="user", content=request.message),
            )
            try:
                expression = self.resolver.resolve(response, state)
            except Exception:
                expression = ResolvedExpression(
                    expression_tag="neutral",
                    expression_intensity="low",
                    outfit_code=state.outfit_code,
                )
            await self._event(turn_id, request, "semantic.completed", response.model_dump(mode="json"), trace_id)
            for index, delta in enumerate(_chunks(response.reply), start=1):
                if record.cancel_requested.is_set():
                    record.status = "cancelled"
                    await self._event(turn_id, request, "turn.cancelled", {"reason": "client_requested"}, trace_id)
                    return
                await self._event(turn_id, request, "text.delta", {"delta": delta, "index": index}, trace_id)
                await asyncio.sleep(self.stream_interval)
            await self._event(turn_id, request, "text.completed", {"text": response.reply}, trace_id)
            self.sessions.append(
                request.user_id,
                request.client_id,
                request.session_id,
                SessionMessage(role="assistant", content=response.reply),
            )
            await self._event(turn_id, request, "expression.cue", expression.model_dump(mode="json"), trace_id)
            await self._event(turn_id, request, "sprite.resolved", expression.model_dump(mode="json"), trace_id)
            memory_status = "unavailable" if not memory_available else "written"
            memory_write_data: dict = {"status": memory_status, "written_ids": [], "decisions": []}
            if memory_available:
                try:
                    candidates = list(response.memory_candidates)
                    if not candidates:
                        candidates = await self.memory.extract_candidates(
                            MemoryExtractionInput(
                                user_id=request.user_id,
                                content=request.message,
                                source_client=request.client_id,
                                source_session=request.session_id,
                            )
                        )
                    existing = await self.memory.list_records(request.user_id)
                    decisions = self.memory_policy.review(
                        user_id=request.user_id,
                        source_client=request.client_id,
                        source_session=request.session_id,
                        candidates=candidates,
                        existing=existing,
                    )
                    submit_runtime_candidate = getattr(
                        self.memory, "submit_runtime_candidate", None
                    )
                    memory_write_data["candidate_ids"] = []
                    authoritative_pending = False
                    for index, decision in enumerate(decisions):
                        authoritative_candidate = None
                        if (
                            callable(submit_runtime_candidate)
                            and decision.status != "duplicate"
                        ):
                            authoritative_candidate = await submit_runtime_candidate(
                                decision.candidate,
                                user_id=request.user_id,
                                source_client=request.client_id,
                                source_session=request.session_id,
                                source_turn_id=turn_id,
                                request_id=f"{trace_id}:candidate:{index}",
                            )
                            memory_write_data["candidate_ids"].append(
                                str(authoritative_candidate.candidate_id)
                            )
                            authoritative_pending = (
                                authoritative_pending
                                or authoritative_candidate.status.value
                                == "pending_review"
                            )
                        await self._event(
                            turn_id,
                            request,
                            "memory.candidate.created",
                            {
                                "candidate": decision.candidate.model_dump(mode="json"),
                                "review_status": decision.status,
                                "reason": decision.reason,
                                "authoritative_candidate_id": (
                                    str(authoritative_candidate.candidate_id)
                                    if authoritative_candidate is not None
                                    else None
                                ),
                            },
                            trace_id,
                        )
                        if (
                            authoritative_candidate is None
                            and decision.status == "accepted"
                            and decision.upsert is not None
                        ):
                            written = await self.memory.upsert(decision.upsert)
                            memory_write_data["written_ids"].append(written.memory_id)
                    memory_write_data["decisions"] = [decision.status for decision in decisions]
                    if any(
                        decision.status == "pending_review"
                        for decision in decisions
                    ) or authoritative_pending:
                        memory_status = "pending"
                    memory_write_data["status"] = memory_status
                except Exception as exc:
                    memory_status = "unavailable"
                    memory_write_data = {"status": memory_status, "error": type(exc).__name__}
            await self._event(turn_id, request, "memory.write.completed", memory_write_data, trace_id)
            record.result = ChatResponse(turn_id=turn_id, session_id=request.session_id, response=response, runtime_state=state, expression=expression, memory_status=memory_status, build_id=BUILD_ID)
            record.status = "completed"
            await self._event(turn_id, request, "turn.completed", {"reply": response.reply}, trace_id)
        except asyncio.CancelledError:
            record.status = "cancelled"
            await self._event(turn_id, request, "turn.cancelled", {"reason": "runtime_shutdown"}, trace_id)
            raise
        except Exception as exc:
            record.status = "failed"
            record.error = str(exc)
            await self._event(turn_id, request, "turn.failed", {"error": str(exc)}, trace_id)
        finally:
            record.done.set()
            condition = self.conditions.setdefault(request.session_id, asyncio.Condition())
            async with condition:
                condition.notify_all()

    def cancel(self, turn_id: str) -> TurnRecord | None:
        record = self.turns.get(turn_id)
        if record is None:
            return None
        if record.status not in self.terminal_statuses:
            record.cancel_requested.set()
        return record

    def session_is_active(self, session_id: str) -> bool:
        return any(record.request.session_id == session_id and record.status not in self.terminal_statuses for record in self.turns.values())

    def reset(self) -> None:
        for task in tuple(self.tasks):
            task.cancel()
        self.tasks.clear()
        self.events.clear()
        self.turns.clear()
        self.idempotency.clear()
        self.conditions.clear()
        reset_memory = getattr(self.memory, "reset", None)
        if callable(reset_memory):
            reset_memory()
        self.sessions.clear()
        self.state_machine.overrides.clear()


def _chunks(text: str, size: int = 18):
    for start in range(0, len(text), size):
        yield text[start : start + size]
