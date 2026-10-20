"""Select a backend without taking ownership of AstrBot's native messages."""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from typing import Any


logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class PersonaBackendRouting:
    enabled: bool = False
    meguri_persona_ids: frozenset[str] = field(default_factory=frozenset)

    async def should_use_meguri(self, context: Any, event: Any) -> bool:
        if not self.enabled:
            return True
        if not self.meguri_persona_ids:
            return False

        origin = getattr(event, "unified_msg_origin", None)
        if not isinstance(origin, str) or not origin.strip():
            return False

        try:
            conversations = context.conversation_manager
            conversation_id = await conversations.get_curr_conversation_id(origin)
            conversation_persona_id = None
            if conversation_id:
                conversation = await conversations.get_conversation(
                    origin, conversation_id
                )
                if conversation is None:
                    return False
                conversation_persona_id = conversation.persona_id

            (
                selected_id,
                _,
                _,
                _,
            ) = await context.persona_manager.resolve_selected_persona(
                umo=origin,
                conversation_persona_id=conversation_persona_id,
                platform_name=event.get_platform_name(),
            )
            return (
                isinstance(selected_id, str)
                and selected_id != "[%None]"
                and selected_id in self.meguri_persona_ids
            )
        except Exception as error:
            # Never log exception text: it may contain session IDs or credentials.
            logger.warning(
                "Meguri persona lookup unavailable; using AstrBot (%s)",
                type(error).__name__,
            )
            return False
