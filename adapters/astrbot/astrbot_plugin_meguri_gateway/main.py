"""AstrBot entrypoint for the Meguri gateway plugin."""

from __future__ import annotations

import asyncio
import os
from pathlib import Path

from astrbot.api import AstrBotConfig, logger
from astrbot.api.event import AstrMessageEvent, filter
from astrbot.api.star import Context, Star, register

from .bilibili_daily_card import DATE_PATTERN, render_bilibili_daily_card
from .bridge import MessageRoutePolicy, is_meguri_command, platform_message_from_event
from .client import HttpMeguriCoreClient
from .daily_reports import DailyReportPoller
from .gateway import MeguriGateway
from .identity import IdentityBindingStore
from .relay import HttpMeguriRelayClient
from .turn_checkpoints import TurnCheckpointStore


@register(
    "astrbot_plugin_meguri_gateway",
    "IzumiShizuki",
    "Routes AstrBot messages to the shared Meguri Core runtime.",
    "0.5.0",
)
class MeguriGatewayPlugin(Star):
    """Routes selected AstrBot events into the authoritative Meguri services."""

    def __init__(self, context: Context, config: AstrBotConfig) -> None:
        super().__init__(context)
        self.config = config
        salt_env = str(config.get("identity_salt_env", "MEGURI_IDENTITY_SALT"))
        identity_salt = os.getenv(salt_env, "").strip()
        if not identity_salt:
            identity_salt = _read_secret_file(
                str(config.get("identity_salt_file", "")).strip()
            ) or ""
        if not identity_salt:
            raise RuntimeError(
                f"{salt_env} or identity_salt_file is required by the Meguri gateway"
            )

        identities = IdentityBindingStore(salt=identity_salt)
        for binding in config.get("identity_bindings", []) or []:
            if not isinstance(binding, dict):
                continue
            identities.bind(
                str(binding.get("platform", "")).strip(),
                str(binding.get("sender_id", "")).strip(),
                str(binding.get("meguri_user_id", "")).strip(),
                account_id=str(binding.get("account_id", "*")).strip() or "*",
            )

        core = HttpMeguriCoreClient(
            base_url=str(config.get("core_url", "http://127.0.0.1:18080")),
            timeout_seconds=float(config.get("timeout_seconds", 8.0)),
            tenant_id=str(config.get("tenant_id", "meguri-local")),
            shared_token=(
                _read_secret_file(str(config.get("core_token_file", "")).strip())
                or _read_secret_file_env(
                    str(
                        config.get(
                            "core_token_file_env",
                            "MEGURI_ASTRBOT_SHARED_TOKEN_FILE",
                        )
                    )
                )
            ),
            async_turns_enabled=bool(config.get("async_turns_enabled", True)),
            checkpoint_store=TurnCheckpointStore(
                str(
                    config.get(
                        "turn_checkpoint_file",
                        "data/plugin_data/astrbot_plugin_meguri_gateway/turn-checkpoints.json",
                    )
                )
            ),
        )

        relay = None
        if bool(config.get("remote_commands_enabled", False)):
            relay = HttpMeguriRelayClient(
                str(config.get("relay_url", "http://127.0.0.1:18100")),
                token=_read_secret_file_env(
                    str(config.get("relay_token_file_env", "MEGURI_RELAY_TOKEN_FILE"))
                ),
                timeout_seconds=float(config.get("timeout_seconds", 8.0)),
                tenant_id=str(config.get("tenant_id", "meguri-local")),
            )

        remote_operators = frozenset(
            str(value).strip()
            for value in (config.get("remote_operator_senders", []) or [])
            if str(value).strip()
        )
        self.gateway = MeguriGateway(
            core=core,
            identities=identities,
            relay=relay,
            remote_authorizer=lambda message: message.sender_key in remote_operators,
            reply_format=(
                "zh_ja_pairs"
                if bool(config.get("bilingual_zh_ja", True))
                else "default"
            ),
        )
        self.route_policy = MessageRoutePolicy(
            route_all_private_messages=bool(
                config.get("route_all_private_messages", False)
            ),
            allow_group_messages=bool(config.get("allow_group_messages", False)),
            route_all_group_messages=bool(
                config.get("route_all_group_messages", False)
            ),
            allowed_senders=frozenset(
                str(value).strip()
                for value in (config.get("allowed_senders", []) or [])
                if str(value).strip()
            ),
        )
        self._daily_report_task: asyncio.Task | None = None
        self._daily_report_render_directory = Path(
            str(
                config.get(
                    "daily_report_render_directory",
                    "data/plugin_data/astrbot_plugin_meguri_gateway/rendered-reports",
                )
            )
        )
        self._daily_report_poll_seconds = max(
            30, int(config.get("daily_report_poll_seconds", 60))
        )
        if bool(config.get("daily_reports_enabled", False)):
            targets = config.get("daily_report_targets", []) or []
            if not targets:
                raise RuntimeError("daily_reports_enabled requires daily_report_targets")
            self.daily_reports = DailyReportPoller(
                core,
                self._send_daily_report,
                targets=targets,
                kinds=config.get("daily_report_kinds", ["bilibili"]) or ["bilibili"],
                state_file=Path(
                    str(
                        config.get(
                            "daily_report_state_file",
                            "data/plugin_data/astrbot_plugin_meguri_gateway/daily-report-state.json",
                        )
                    )
                ),
                max_age_hours=int(config.get("daily_report_max_age_hours", 48)),
            )
            self._daily_report_task = asyncio.create_task(self._daily_report_loop())
        logger.info("Meguri AstrBot gateway initialized")

    @filter.event_message_type(filter.EventMessageType.ALL, priority=100)
    async def on_message(self, event: AstrMessageEvent):
        """Handle explicit /meguri commands and optionally private chat messages."""

        message = platform_message_from_event(event)
        if message is None:
            return
        explicit = is_meguri_command(message.text)
        if not self.route_policy.should_route(message):
            return
        if not self.route_policy.is_allowed(message):
            if explicit:
                # AstrBot's current API uses True to suppress its default LLM.
                event.should_call_llm(True)
                yield event.plain_result("当前聊天账号未获准访问 Meguri。")
                event.stop_event()
            return

        # AstrBot's current API uses True to suppress its default LLM.
        event.should_call_llm(True)
        reply = await self.gateway.handle(message)
        if reply.ignored:
            event.stop_event()
            return
        render_payload = reply.metadata.get("meguri_render_payload")
        if isinstance(render_payload, dict):
            event.set_extra("meguri_render_payload", render_payload)
        yield event.plain_result(reply.text)
        event.stop_event()

    async def terminate(self) -> None:
        if self._daily_report_task is not None:
            self._daily_report_task.cancel()
            try:
                await self._daily_report_task
            except asyncio.CancelledError:
                pass
        await self.gateway.close()
        logger.info("Meguri AstrBot gateway terminated")

    async def _send_daily_report(
        self, target: str, text: str, render_payload: dict | None
    ) -> bool:
        from astrbot.api.event import MessageChain

        chain = MessageChain()
        if render_payload is not None:
            report_date = str(render_payload.get("date") or "")
            if not DATE_PATTERN.fullmatch(report_date):
                logger.warning("Meguri daily report render payload has an invalid date")
                return False
            output = self._daily_report_render_directory / f"bilibili-{report_date}.png"
            try:
                rendered = await asyncio.to_thread(
                    render_bilibili_daily_card, render_payload, output
                )
            except (OSError, ValueError) as error:
                logger.warning(
                    "Meguri daily report image rendering failed: %s",
                    type(error).__name__,
                )
                return False
            chain.file_image(str(rendered))
        chain.message(text)
        return bool(await self.context.send_message(target, chain))

    async def _daily_report_loop(self) -> None:
        await asyncio.sleep(10)
        while True:
            try:
                stats = await self.daily_reports.poll_once()
                if stats["sent"]:
                    logger.info("Meguri daily reports delivered: %s", stats["sent"])
            except asyncio.CancelledError:
                raise
            except Exception as error:
                logger.warning(
                    "Meguri daily report polling failed: %s",
                    type(error).__name__,
                )
            await asyncio.sleep(self._daily_report_poll_seconds)


def _read_secret_file_env(env_name: str) -> str | None:
    path_value = os.getenv(env_name, "").strip()
    if not path_value:
        return None
    return _read_secret_file(path_value)


def _read_secret_file(path_value: str) -> str | None:
    if not path_value:
        return None
    value = Path(path_value).read_text(encoding="utf-8").strip()
    if not value:
        raise RuntimeError(f"configured secret file {path_value} is empty")
    return value
