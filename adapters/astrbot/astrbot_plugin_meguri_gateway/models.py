from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, Field


class PlatformMessage(BaseModel):
    platform: str = Field(min_length=1)
    account_id: str = Field(min_length=1)
    sender_id: str = Field(min_length=1)
    conversation_id: str = Field(min_length=1)
    message_id: str = Field(min_length=1)
    text: str = Field(min_length=1)
    chat_type: Literal["private", "group"]


class IdentityContext(BaseModel):
    meguri_user_id: str
    client_id: Literal["astrbot"] = "astrbot"
    session_id: str


class GatewayReply(BaseModel):
    text: str
    degraded: bool = False
    command_handled: bool = False
    metadata: dict[str, Any] = Field(default_factory=dict)
