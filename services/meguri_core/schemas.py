from __future__ import annotations

from datetime import datetime, timezone
from typing import Any, Literal
from uuid import uuid4

from pydantic import BaseModel, ConfigDict, Field, field_validator


ExpressionTag = Literal[
    "affectionate", "angry", "confused", "embarrassed", "excited", "happy",
    "neutral", "sad", "sleepy", "surprised", "teasing", "worried",
]
Intensity = Literal["low", "medium", "high"]
VoiceStyle = Literal[
    "neutral", "soft", "cheerful", "restrained", "sleepy", "teasing", "affectionate", "worried",
]
Mode = Literal["work", "private", "sleep", "event"]
Relationship = Literal["sibling", "pursuit", "lover"]


class ClientCapabilities(BaseModel):
    text: bool = True
    sprite: bool = False
    voice: bool = False
    screen_context: bool = False


class TurnRequest(BaseModel):
    model_config = ConfigDict(extra="ignore")

    user_id: str = Field(min_length=1)
    client_id: Literal["astrbot", "desktop_pet", "website"]
    session_id: str = Field(min_length=1)
    message: str = Field(min_length=1)
    attachments: list[dict[str, Any]] = Field(default_factory=list)
    client_capabilities: ClientCapabilities = Field(default_factory=ClientCapabilities)
    optional_screen_context_id: str | None = None
    relationship_profile: Relationship | None = None


class RuntimeState(BaseModel):
    client_id: str
    mode: Mode
    relationship_profile: Relationship
    outfit_code: str
    local_time: str
    is_holiday: bool
    voice_enabled: bool
    screen_context_enabled: bool
    allowed_expression_tags: list[ExpressionTag]


class MemoryCandidate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    type: Literal["preference", "identity", "project", "commitment", "relationship", "routine", "event"]
    summary: str = Field(min_length=1, max_length=500)
    confidence: float = Field(ge=0, le=1)
    sensitivity: Literal["normal", "private", "sensitive"] = "normal"
    source_scope: Literal["current_message", "conversation"] = "current_message"


class LlmResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    reply: str = Field(min_length=1)
    expression_tag: ExpressionTag = "neutral"
    expression_intensity: Intensity = "low"
    voice_style: VoiceStyle = "neutral"
    memory_candidates: list[MemoryCandidate] = Field(default_factory=list, max_length=3)


class ResolvedExpression(BaseModel):
    expression_tag: ExpressionTag
    expression_intensity: Intensity
    outfit_code: str
    expression_code: str | None = None
    sprite_file: str | None = None


class EventMetadata(BaseModel):
    trace_id: str
    source: str = "meguri-core"
    created_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
    build_id: str


class EventEnvelope(BaseModel):
    type: str
    turn_id: str
    session_id: str
    sequence: int
    data: dict[str, Any] = Field(default_factory=dict)
    metadata: EventMetadata


class TurnCreateResponse(BaseModel):
    turn_id: str
    session_id: str
    build_id: str
    status: Literal["accepted", "running", "completed", "failed", "cancelled"]


class TurnStatusResponse(BaseModel):
    turn_id: str
    session_id: str
    status: Literal["accepted", "running", "completed", "failed", "cancelled"]
    build_id: str
    error: str | None = None


class ChatResponse(BaseModel):
    turn_id: str
    session_id: str
    response: LlmResponse
    runtime_state: RuntimeState
    expression: ResolvedExpression
    memory_status: Literal["written", "pending", "unavailable"]
    build_id: str


class RuntimeOverride(BaseModel):
    mode: Mode | None = None
    relationship_profile: Relationship | None = None
    outfit_code: str | None = None
    expires_at: datetime | None = None

    @field_validator("outfit_code")
    @classmethod
    def validate_outfit_code(cls, value: str | None) -> str | None:
        if value is not None and value not in {"01", "02", "03", "04", "05", "06"}:
            raise ValueError("outfit_code must be one of 01-06; 07 and 08 are disabled")
        return value

    @field_validator("expires_at")
    @classmethod
    def validate_expiry_timezone(cls, value: datetime | None) -> datetime | None:
        if value is not None and value.tzinfo is None:
            raise ValueError("expires_at must include a timezone offset")
        return value


def new_id(prefix: str) -> str:
    return f"{prefix}_{uuid4().hex[:16]}"
