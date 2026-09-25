from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class ChatMessage(BaseModel):
    model_config = ConfigDict(extra="forbid")

    role: Literal["system", "user", "assistant"]
    content: str = Field(min_length=1, max_length=30000)


class ChatCompletionRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    model: str = Field(min_length=1)
    messages: list[ChatMessage] = Field(min_length=2, max_length=64)
    stream: bool = False
    temperature: float = Field(default=0.0, ge=0, le=2)
    response_format: dict | None = None
    max_tokens: int | None = Field(default=None, ge=1, le=512)
