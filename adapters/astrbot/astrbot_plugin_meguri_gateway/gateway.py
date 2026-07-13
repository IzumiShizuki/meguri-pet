from __future__ import annotations

from .client import CoreProtocolError, CoreUnavailableError, MeguriCoreClient
from .commands import HELP_TEXT, parse_command
from .identity import IdentityBindingStore
from .models import GatewayReply, PlatformMessage


class MeguriGateway:
    def __init__(self, core: MeguriCoreClient, identities: IdentityBindingStore) -> None:
        self.core = core
        self.identities = identities

    async def handle(self, message: PlatformMessage) -> GatewayReply:
        identity = self.identities.resolve(message)
        command = parse_command(message.text)
        try:
            if command is not None:
                return await self._handle_command(identity.meguri_user_id, identity.session_id, command)
            payload = {
                "user_id": identity.meguri_user_id,
                "client_id": "astrbot",
                "session_id": identity.session_id,
                "message": message.text,
                "attachments": [],
                "client_capabilities": {
                    "text": True,
                    "sprite": False,
                    "voice": False,
                    "screen_context": False,
                },
            }
            response = await self.core.respond(payload)
            semantic = response.get("response")
            if not isinstance(semantic, dict) or not isinstance(semantic.get("reply"), str):
                raise CoreProtocolError("meguri-core response is missing response.reply")
            return GatewayReply(
                text=semantic["reply"],
                metadata={"turn_id": response.get("turn_id"), "session_id": identity.session_id},
            )
        except (CoreUnavailableError, CoreProtocolError, TimeoutError):
            return GatewayReply(
                text="Meguri 服务暂时不可用，请稍后再试。",
                degraded=True,
                metadata={"session_id": identity.session_id},
            )

    async def _handle_command(self, user_id: str, session_id: str, command) -> GatewayReply:
        if command.action == "help":
            return GatewayReply(text=HELP_TEXT, command_handled=True)
        if command.action == "status":
            state = await self.core.runtime_state(user_id, session_id)
            return GatewayReply(
                text=(
                    f"mode={state.get('mode', 'unknown')} "
                    f"outfit={state.get('outfit_code', 'unknown')} "
                    f"relation={state.get('relationship_profile', 'unknown')}"
                ),
                command_handled=True,
            )
        if command.action == "clear_override":
            await self.core.clear_override(user_id)
            return GatewayReply(text="Meguri override cleared.", command_handled=True)
        await self.core.set_override(user_id, command.override)
        return GatewayReply(text="Meguri override updated.", command_handled=True)
