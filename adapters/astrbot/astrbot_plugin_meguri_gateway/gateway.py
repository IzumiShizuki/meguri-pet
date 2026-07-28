from __future__ import annotations

import asyncio
import hashlib
from collections.abc import Callable

from .client import CoreProtocolError, CoreUnavailableError, MeguriCoreClient
from .commands import HELP_TEXT, MeguriCommand, parse_command
from .dedupe import MessageDeduplicator
from .identity import IdentityBindingStore
from .models import GatewayReply, IdentityContext, PlatformMessage
from .relay import (
    MeguriRelayClient,
    RelayProtocolError,
    RelayUnavailableError,
    RemoteDevice,
    RemoteTask,
    RemoteTaskDraft,
)


REMOTE_ACTIONS = frozenset({"devices", "run", "confirm", "task", "cancel"})


class MeguriGateway:
    def __init__(
        self,
        core: MeguriCoreClient,
        identities: IdentityBindingStore,
        *,
        relay: MeguriRelayClient | None = None,
        remote_authorizer: Callable[[PlatformMessage], bool] | None = None,
        deduplicator: MessageDeduplicator | None = None,
        reply_format: str = "default",
    ) -> None:
        if reply_format not in {"default", "zh_ja_pairs"}:
            raise ValueError("reply_format must be default or zh_ja_pairs")
        self.core = core
        self.identities = identities
        self.relay = relay
        self.remote_authorizer = remote_authorizer or (lambda _message: False)
        self.deduplicator = deduplicator or MessageDeduplicator()
        self.reply_format = reply_format

    async def handle(self, message: PlatformMessage) -> GatewayReply:
        if not await self.deduplicator.first_delivery(message):
            return GatewayReply(text="", ignored=True, metadata={"duplicate": True})
        identity = self.identities.resolve(message)
        command = parse_command(message.text)
        try:
            if command is not None:
                return await self._handle_command(identity, message, command)
            return await self._chat(identity, message, message.text)
        except (CoreUnavailableError, CoreProtocolError, TimeoutError):
            await self.deduplicator.forget(message)
            return GatewayReply(
                text="Meguri 服务暂时不可用，请稍后再试。",
                degraded=True,
                metadata={"session_id": identity.session_id},
            )
        except (RelayUnavailableError, RelayProtocolError):
            await self.deduplicator.forget(message)
            return GatewayReply(
                text="Meguri 远程任务服务暂时不可用，请稍后再试。",
                degraded=True,
                command_handled=True,
                metadata={"session_id": identity.session_id},
            )

    async def close(self) -> None:
        closers = []
        for dependency in (self.core, self.relay):
            close = getattr(dependency, "close", None)
            if close is not None:
                closers.append(close())
        if closers:
            await asyncio.gather(*closers)

    async def _chat(
        self,
        identity: IdentityContext,
        message: PlatformMessage,
        text: str,
    ) -> GatewayReply:
        payload = {
            "protocol_version": "1.0",
            "identity": {
                "meguri_user": {"id": identity.meguri_user_id},
                "platform_actor": {
                    "platform": identity.platform,
                    "actor_id": identity.platform_actor_id,
                },
                "client_instance": {
                    "id": identity.client_instance_id,
                    "profile": "astrbot",
                },
                "session": {"id": identity.session_id},
            },
            "message": text,
            "reply_format": self.reply_format,
            "formal_memory_allowed": identity.formal_memory_allowed,
            "attachments": [],
        }
        response = await self.core.respond(
            payload,
            idempotency_key=_idempotency_key(message),
        )
        semantic = response.get("response")
        if not isinstance(semantic, dict) or not isinstance(semantic.get("reply"), str):
            raise CoreProtocolError("meguri-core response is missing response.reply")
        runtime_state = response.get("runtime_state")
        expression = response.get("expression")
        if not isinstance(runtime_state, dict) or not isinstance(expression, dict):
            raise CoreProtocolError(
                "meguri-core response is missing runtime_state or expression"
            )
        render_payload = {
            "schema_version": 1,
            "response": {
                "reply": semantic["reply"],
                "expression_tag": semantic.get("expression_tag", "neutral"),
                "expression_intensity": semantic.get("expression_intensity", "low"),
                "voice_style": semantic.get("voice_style", "neutral"),
                "memory_candidates": semantic.get("memory_candidates", []),
            },
            "runtime_state": runtime_state,
            "expression": expression,
            "turn_id": response.get("turn_id"),
            "build_id": response.get("build_id"),
        }
        return GatewayReply(
            text=semantic["reply"],
            metadata={
                "turn_id": response.get("turn_id"),
                "session_id": identity.session_id,
                "meguri_render_payload": render_payload,
            },
        )

    async def _handle_command(
        self,
        identity: IdentityContext,
        message: PlatformMessage,
        command: MeguriCommand,
    ) -> GatewayReply:
        if command.action == "help":
            return GatewayReply(text=HELP_TEXT, command_handled=True)
        if command.action == "chat":
            reply = await self._chat(identity, message, command.text or "")
            return reply.model_copy(update={"command_handled": True})
        if command.action in REMOTE_ACTIONS:
            return await self._handle_remote(identity, message, command)
        user_id = identity.meguri_user_id
        session_id = identity.session_id
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

    async def _handle_remote(
        self,
        identity: IdentityContext,
        message: PlatformMessage,
        command: MeguriCommand,
    ) -> GatewayReply:
        if self.relay is None:
            return GatewayReply(
                text="远程开发命令尚未启用。请先配置 Meguri Relay。",
                command_handled=True,
            )
        if not self.remote_authorizer(message):
            return GatewayReply(
                text="当前聊天账号没有远程开发权限。",
                command_handled=True,
            )
        user_id = identity.meguri_user_id
        session_id = identity.session_id
        if command.action == "devices":
            devices = await self.relay.list_devices(user_id, session_id)
            return GatewayReply(text=_format_devices(devices), command_handled=True)
        if command.action == "run":
            draft = await self.relay.preview_task(
                user_id=user_id,
                session_id=session_id,
                prompt=command.text or "",
                target_device_id=command.target_device_id,
                source_message_id=_idempotency_key(message),
            )
            return GatewayReply(
                text=_format_draft(draft),
                command_handled=True,
                metadata={"draft_id": draft.draft_id},
            )
        if command.action == "confirm":
            task = await self.relay.confirm_task(
                user_id=user_id,
                session_id=session_id,
                draft_id=command.draft_id or "",
                confirmation_code=command.confirmation_code or "",
                source_message_id=_idempotency_key(message),
            )
            return GatewayReply(
                text=_format_task(task),
                command_handled=True,
                metadata={"task_id": task.task_id},
            )
        if command.action == "task":
            task = await self.relay.get_task(
                user_id=user_id,
                session_id=session_id,
                task_id=command.task_id or "",
            )
            return GatewayReply(
                text=_format_task(task),
                command_handled=True,
                metadata={"task_id": task.task_id},
            )
        task = await self.relay.cancel_task(
            user_id=user_id,
            session_id=session_id,
            task_id=command.task_id or "",
            source_message_id=_idempotency_key(message),
        )
        return GatewayReply(
            text=_format_task(task),
            command_handled=True,
            metadata={"task_id": task.task_id},
        )


def _format_devices(devices: list[RemoteDevice]) -> str:
    if not devices:
        return "没有找到已绑定的电脑。"
    lines = ["已绑定电脑："]
    for device in devices:
        busy = "，忙碌" if device.busy else ""
        capabilities = ", ".join(device.capabilities) or "未声明"
        lines.append(
            f"- {device.label} ({device.device_id})：{device.status}{busy}；能力={capabilities}"
        )
    return "\n".join(lines)


def _format_draft(draft: RemoteTaskDraft) -> str:
    lines = [
        f"已匹配电脑：{draft.target_device.label} ({draft.target_device.device_id})",
        f"任务预览：{draft.summary}",
    ]
    if draft.permissions:
        lines.append("权限范围：" + ", ".join(draft.permissions))
    lines.extend(f"警告：{warning}" for warning in draft.warnings)
    lines.extend(
        (
            f"确认码：{draft.confirmation_code}（有效至 {draft.expires_at}）",
            f"回复 /meguri confirm {draft.draft_id} {draft.confirmation_code}",
        )
    )
    return "\n".join(lines)


def _format_task(task: RemoteTask) -> str:
    text = (
        f"任务 {task.task_id}：{task.status}\n"
        f"电脑：{task.target_device_id}\n"
        f"摘要：{task.summary or '无'}"
    )
    if task.detail:
        text += f"\n详情：{task.detail}"
    if task.waiting_for_approval:
        text += "\n任务正在等待审批。"
    return text


def _idempotency_key(message: PlatformMessage) -> str:
    material = "\x1f".join(
        (
            "astrbot",
            message.platform,
            message.account_id,
            message.sender_id,
            message.unified_msg_origin or message.conversation_id,
            message.message_id,
        )
    )
    return hashlib.sha256(material.encode("utf-8")).hexdigest()
