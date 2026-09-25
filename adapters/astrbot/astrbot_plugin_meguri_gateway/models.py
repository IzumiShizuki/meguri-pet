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
    unified_msg_origin: str | None = None
    sender_is_admin: bool = False

    @property
    def sender_key(self) -> str:
        return f"{self.platform}:{self.account_id}:{self.sender_id}"


class IdentityContext(BaseModel):
    meguri_user_id: str
    platform: str
    platform_actor_id: str
    client_instance_id: str
    client_id: Literal["astrbot"] = "astrbot"
    session_id: str
    formal_memory_allowed: bool = False


class GatewayReply(BaseModel):
    text: str
    degraded: bool = False
    command_handled: bool = False
    ignored: bool = False
    metadata: dict[str, Any] = Field(default_factory=dict)
