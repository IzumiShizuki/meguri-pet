from __future__ import annotations

import hashlib
import time
from dataclasses import dataclass, field
from typing import Any, Iterable

from .models import PlatformMessage


def is_meguri_command(text: str, prefix: str = "/meguri") -> bool:
    parts = text.strip().split(maxsplit=1)
    return bool(parts) and parts[0].casefold() == prefix.casefold()


# Other plugins register their commands through AstrBot's handler registry, so the
# gateway can discover them instead of hardcoding a list. Discovery is cached
# because it runs on every inbound message and the registry only changes when a
# plugin is (re)loaded.
_PLUGIN_COMMAND_CACHE: tuple[float, frozenset[str]] | None = None
_PLUGIN_COMMAND_TTL_SECONDS = 60.0
_OWN_PLUGIN_MODULE = "astrbot_plugin_meguri_gateway"


def _registered_command_filters() -> Iterable[Any]:
    """Yield AstrBot command filters registered by plugins other than the gateway."""

    from astrbot.core.star.filter.command import CommandFilter
    from astrbot.core.star.star_handler import EventType, star_handlers_registry

    handlers = star_handlers_registry.get_handlers_by_event_type(
        EventType.AdapterMessageEvent
    )
    for handler in handlers:
        module_path = str(getattr(handler, "handler_module_path", ""))
        if _OWN_PLUGIN_MODULE in module_path:
            continue
        for event_filter in getattr(handler, "event_filters", []) or []:
            if isinstance(event_filter, CommandFilter):
                yield event_filter


def registered_plugin_commands(now: float | None = None) -> frozenset[str]:
    """Return the command names and aliases other plugins have registered.

    Returns an empty set when the AstrBot internals are unavailable (for example
    under a stubbed test host) so the gateway keeps working without discovery.
    """

    global _PLUGIN_COMMAND_CACHE

    current = time.monotonic() if now is None else now
    cached = _PLUGIN_COMMAND_CACHE
    if cached is not None and current - cached[0] < _PLUGIN_COMMAND_TTL_SECONDS:
        return cached[1]

    discovered: set[str] = set()
    try:
        for event_filter in _registered_command_filters():
            for name in event_filter.get_complete_command_names():
                normalized = str(name).strip().casefold()
                if normalized:
                    discovered.add(normalized)
    except Exception:
        discovered = set()

    result = frozenset(discovered)
    _PLUGIN_COMMAND_CACHE = (current, result)
    return result


@dataclass(frozen=True)
class MessageRoutePolicy:
    prefix: str = "/meguri"
    route_all_private_messages: bool = False
    allow_group_messages: bool = False
    route_all_group_messages: bool = False
    allowed_senders: frozenset[str] = field(default_factory=frozenset)
    passthrough_commands: tuple[str, ...] = ()

    def matches_passthrough(self, text: str | None) -> bool:
        """Return True when another plugin owns this message.

        Matching is case-insensitive and argument-aware: the bare command and the
        command followed by arguments (`查老婆 @某人`) are both released. An
        explicit `/meguri ...` invocation always stays with Meguri, because the
        user asked for it and a neighbouring command must not swallow it.
        """

        value = str(text or "").strip().casefold()
        if not value or is_meguri_command(value, self.prefix):
            return False

        candidates = set(self.passthrough_commands)
        candidates |= registered_plugin_commands()
        return any(
            value == command or value.startswith(f"{command} ")
            for command in candidates
        )

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
