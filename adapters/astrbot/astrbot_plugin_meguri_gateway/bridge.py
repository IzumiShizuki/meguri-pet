from __future__ import annotations

import hashlib
from dataclasses import dataclass, field
from typing import Any

from .models import PlatformMessage


def is_meguri_command(text: str, prefix: str = "/meguri") -> bool:
    parts = text.strip().split(maxsplit=1)
    return bool(parts) and parts[0].casefold() == prefix.casefold()


@dataclass(frozen=True)
class MessageRoutePolicy:
    prefix: str = "/meguri"
    route_all_private_messages: bool = False
    allow_group_messages: bool = False
    route_all_group_messages: bool = False
    allowed_senders: frozenset[str] = field(default_factory=frozenset)

    def is_allowed(self, message: PlatformMessage) -> bool:
        return not self.allowed_senders or message.sender_key in self.allowed_senders

    def should_route(self, message: PlatformMessage) -> bool:
        explicit = is_meguri_command(message.text, self.prefix)
        if message.chat_type == "group":
            return self.allow_group_messages and (
                explicit or self.route_all_group_messages
            )
        return explicit or self.route_all_private_messages


def platform_message_from_event(event: Any) -> PlatformMessage | None:
    platform = str(event.get_platform_name())
    account_id = str(event.get_self_id())
    sender_id = str(event.get_sender_id())
    is_private = bool(event.is_private_chat())
    unified_origin = str(
        getattr(event, "unified_msg_origin", "") or event.get_session_id()
    )
    group_id = event.get_group_id() if not is_private else None
    conversation_id = str(group_id or unified_origin)
    message_obj = getattr(event, "message_obj", None)
    message_id = str(getattr(message_obj, "message_id", "") or "")
    text = str(event.get_message_str()).strip()
    if not text:
        return None
    if not message_id:
        timestamp = str(getattr(message_obj, "timestamp", ""))
        material = "\x1f".join((unified_origin, sender_id, timestamp, text))
        message_id = "fallback_" + hashlib.sha256(material.encode("utf-8")).hexdigest()[:24]
    return PlatformMessage(
        platform=platform,
        account_id=account_id,
        sender_id=sender_id,
        conversation_id=conversation_id,
        message_id=message_id,
        text=text,
        chat_type="private" if is_private else "group",
        unified_msg_origin=unified_origin,
        sender_is_admin=bool(event.is_admin()),
    )
