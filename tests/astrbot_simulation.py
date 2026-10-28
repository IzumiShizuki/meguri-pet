"""Isolated AstrBot boundary with real HTTP transport and no platform SDK/network."""
from __future__ import annotations

import asyncio
import importlib
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import threading
import time
import types
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch
from urllib.parse import parse_qs, urlsplit
from uuid import uuid4

import httpx

from adapters.astrbot.astrbot_plugin_meguri_gateway.client import _expected_replay_policy

PLUGIN_MODULE = "adapters.astrbot.astrbot_plugin_meguri_gateway.main"
_MISSING = object()


class MessageChain:
    def __init__(self):
        self.text = ""
        self.files = []

    def message(self, text):
        self.text += str(text)
        return self

    def file_image(self, path):
        self.files.append(str(path))
        return self


def sdk_modules():
    """Only AstrBot's event/context boundary is substituted; adapter code is real."""
    modules = {name: types.ModuleType(name) for name in (
        "astrbot", "astrbot.api", "astrbot.api.event", "astrbot.api.star",
    )}
    api, event, star = (modules[name] for name in (
        "astrbot.api", "astrbot.api.event", "astrbot.api.star",
    ))
    api.AstrBotConfig = dict
    api.logger = types.SimpleNamespace(**{
        level: lambda *args, **kwargs: None for level in ("info", "warning", "debug", "error")
    })
    event.AstrMessageEvent = object
    event.MessageChain = MessageChain
    event.filter = types.SimpleNamespace(
        EventMessageType=types.SimpleNamespace(ALL="all"),
        event_message_type=lambda *args, **kwargs: lambda function: function,
    )
    class Star:
        def __init__(self, context):
            self.context = context
    star.Star, star.Context = Star, object
    star.register = lambda *args, **kwargs: lambda plugin: plugin
    modules["astrbot"].api = api
    api.event, api.star = event, star
    return modules


class ScenarioServer:
    """Owns port zero until cleanup; provides controlled Core and collector endpoints."""
    def __init__(self):
        self.requests = []
        self.deliveries = []
        self.turns = {}
        self.turn_counter = 0
        self.histories = {}
        self.todo_writes = []
        self.collect_status = 202
        self.create_status = 202
        self.todo_status = 200
        self.terminal = "turn.completed"
        self.retrieval = "ok"
        self.split_stream = False
        self.foreign_session = False
        self.reply = None
        self.lock = threading.Lock()
        owner = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, *_args):
                pass

            def do_POST(self):
                self.dispatch()

            def do_GET(self):
                self.dispatch()

            def dispatch(self):
                size = int(self.headers.get("Content-Length", 0))
                body = json.loads(self.rfile.read(size)) if size else None
                path = urlsplit(self.path)
                with owner.lock:
                    owner.requests.append({"method": self.command, "path": path.path,
                                           "query": parse_qs(path.query), "body": body})
                    status, payload, content_type = owner.respond(self.command, path, body)
                encoded = payload.encode("utf-8") if isinstance(payload, str) else json.dumps(payload, ensure_ascii=False).encode("utf-8")
                self.send_response(status)
                self.send_header("Content-Type", content_type)
                self.send_header("Content-Length", str(len(encoded)))
                self.end_headers()
                try:
                    self.wfile.write(encoded)
                except (BrokenPipeError, ConnectionResetError):
                    pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.daemon_threads = True
        self.origin = f"http://127.0.0.1:{self.server.server_port}"
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def respond(self, method, url, body):
        path, json_type = url.path, "application/json"
        if path == "/collect":
            if self.collect_status == 202:
                self.deliveries.append(body)
            return self.collect_status, {"accepted": self.collect_status == 202}, json_type
        if path == "/health":
            return 200, {"service": "simulated-core", "web_search_provider": "fixture"}, json_type
        if path == "/v1/hello":
            return 200, {"selected_protocol_version": "1.1", "server_capabilities_revision": "simulation-v1",
                         "server_capabilities": body["capabilities"], "effective_capabilities": body["capabilities"],
                         "granted_permissions": body["permissions"], "supported_extensions": []}, json_type
        if path == "/v1/turns" and method == "POST":
            if self.create_status != 202:
                return self.create_status, {"code": "INTERNAL", "message": "Fixture unavailable", "retryable": True}, json_type
            session = body["identity"]["session"]["id"]
            self.turn_counter += 1
            turn = f"sim-turn-{self.turn_counter}"
            self.turns[session] = {"id": turn, "message": body["message"], "reads": 0}
            return 202, {"turn_id": turn, "session_id": session, "build_id": "fixture-v1", "status": "accepted"}, json_type
        if path.startswith("/v1/sessions/") and path.endswith("/events"):
            session = path.split("/")[3]
            turn = self.turns[session]
            turn["reads"] += 1
            reply = self.reply if self.reply is not None else f"模拟回复：{turn['message']}"
            semantic = {"reply": reply, "expression_tag": "neutral", "expression_intensity": "low", "voice_style": "neutral", "memory_candidates": []}
            events = [("turn.started", {"runtime_state": {"mode": "work", "outfit_code": "01"}}),
                      ("retrieval.completed", {"lanes": [{"lane": "web", "status": self.retrieval, "result_count": int(self.retrieval == "ok")}]}),
                      ("semantic.completed", semantic), ("text.completed", {"text": reply}),
                      (self.terminal, {"reply": reply})]
            after = int(parse_qs(url.query).get("after_sequence", [0])[0])
            history = self.histories.setdefault(session, [])
            if turn["reads"] == 1:
                base = len(history)
                for sequence, (kind, data) in enumerate(events, base + 1):
                    history.append({"protocol_version": "1.1", "event_id": f"{turn['id']}-{sequence}",
                            "required": False, "replay_policy": _expected_replay_policy(kind, data),
                            "type": kind, "turn_id": turn["id"], "session_id": "other-session" if self.foreign_session else session,
                            "sequence": sequence, "created_at": "2026-10-18T00:00:00Z", "data": data,
                            "metadata": {"trace_id": "simulation-trace", "created_at": "2026-10-18T00:00:00Z",
                                         "source": "simulated-core", "build_id": "fixture-v1"}})
            frames = []
            for envelope in history:
                sequence, kind = envelope["sequence"], envelope["type"]
                if sequence <= after or (self.split_stream and turn["reads"] == 1 and sequence > 3):
                    continue
                frames.append(f"id: {sequence}\nevent: {kind}\ndata: {json.dumps(envelope, ensure_ascii=False)}\n\n")
            return 200, "".join(frames), "text/event-stream"
        if path == "/v1/daily/reports/latest":
            return 200, {"report_id": "ai_news:2026-10-18", "published_at": datetime.now(timezone.utc).isoformat(),
                         "delivery_text": "模拟日报"}, json_type
        if path == "/v1/reminders/timeprism/todos" and method == "POST":
            if self.todo_status != 200:
                return self.todo_status, {"code": "INTERNAL", "message": "Write outcome uncertain"}, json_type
            self.todo_writes.append(body)
            return 200, {**body, "state": "confirmed", "todoId": 701,
                         "verified": True, "outcome": "created"}, json_type
        return 404, {"message": "Unknown fixture endpoint"}, json_type

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)


class ObservedStream(httpx.AsyncByteStream):
    def __init__(self, stream, events):
        self.stream, self.events = stream, events

    async def __aiter__(self):
        pending = b""
        async for chunk in self.stream:
            pending += chunk
            while b"\n\n" in pending or b"\r\n\r\n" in pending:
                match = re.search(rb"\r?\n\r?\n", pending)
                block, pending = pending[:match.start()], pending[match.end():]
                raw = b"\n".join(line[5:].strip() for line in block.splitlines() if line.startswith(b"data:"))
                if raw:
                    value = json.loads(raw)
                    item = {key: value[key] for key in ("type", "sequence", "turn_id", "session_id") if key in value}
                    if value.get("type") == "retrieval.completed":
                        data = value.get("data", {})
                        item["lanes"], item["degradations"] = data.get("lanes", []), data.get("degradations", [])
                    self.events.append(item)
            yield chunk

    async def aclose(self):
        await self.stream.aclose()


class ObservedTransport(httpx.AsyncHTTPTransport):
    def __init__(self, requests, events):
        super().__init__()
        self.requests, self.events = requests, events

    async def handle_async_request(self, request):
        item = {"method": request.method, "path": request.url.path}
        if request.content:
            item["body"] = json.loads(request.content)
        self.requests.append(item)
        response = await super().handle_async_request(request)
        item["status"] = response.status_code
        if response.headers.get("Content-Type", "").startswith("text/event-stream"):
            response.stream = ObservedStream(response.stream, self.events)
        return response


class SyntheticEvent:
    def __init__(self, context, text, *, sender="owner", group=None, message_id=None):
        self.context, self.text, self.sender, self.group = context, text, sender, group
        self.unified_msg_origin = f"{context.account}:{'GroupMessage' if group else 'FriendMessage'}:{group or sender}"
        self.message_obj = types.SimpleNamespace(message_id=message_id or str(uuid4()), timestamp=time.time(), message_str=text, message=[])
        self.extras = {"activated_handlers": []}
        self.stopped, self.llm_blocked = False, False

    def get_platform_name(self): return "Simulation"
    def get_self_id(self): return self.context.account
    def get_sender_id(self): return self.sender
    def get_group_id(self): return self.group
    def get_session_id(self): return self.unified_msg_origin
    def get_message_str(self): return self.text
    def is_private_chat(self): return self.group is None
    def is_admin(self): return False
    def get_extra(self, key): return self.extras.get(key)
    def set_extra(self, key, value): self.extras[key] = value
    def should_call_llm(self, blocked): self.llm_blocked = blocked
    def stop_event(self): self.stopped = True
    def plain_result(self, text): return MessageChain().message(text)
    async def send(self, chain): return await self.context.send_message(self.unified_msg_origin, chain)


class CollectorContext:
    def __init__(self, server, account):
        self.server, self.account = server, account
        self.client = httpx.AsyncClient(timeout=5, trust_env=False)

    async def send_message(self, target, chain):
        response = await self.client.post(f"{self.server.origin}/collect", json={
            "target": target, "text": chain.text, "files": chain.files,
        })
        response.raise_for_status()
        return response.json()["accepted"]


class AstrBotSimulation:
    def __init__(self, *, core_url=None, token_file=None, config=None):
        self.core_url, self.token_file, self.config = core_url, token_file, config or {}
        self.run_id = uuid4().hex[:12]
        self.requests, self.events, self.results, self.errors = [], [], [], []
        self.server = self.plugin = self.context = None

    async def __aenter__(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="meguri-astrbot-sim-")
        self.state_dir = Path(self.temporary.name)
        self.server = ScenarioServer()
        self.context = CollectorContext(self.server, f"sim-bot-{self.run_id}")
        self._parent_module = sys.modules[PLUGIN_MODULE.rpartition(".")[0]]
        self._old_attribute = getattr(self._parent_module, "main", _MISSING)
        self._old_module = sys.modules.pop(PLUGIN_MODULE, None)
        self._modules = patch.dict(sys.modules, sdk_modules())
        self._modules.start()
        self._environment = patch.dict(os.environ, {"MEGURI_SIMULATION_IDENTITY_SALT": self.run_id})
        self._environment.start()
        try:
            module = importlib.import_module(PLUGIN_MODULE)
            client_type = module.HttpMeguriCoreClient
            transport = ObservedTransport(self.requests, self.events)
            errors = self.errors
            class ObservedClient(client_type):
                async def respond(self, *args, **kwargs):
                    try:
                        return await super().respond(*args, **kwargs)
                    except Exception as error:
                        reason = str(error)
                        errors.append({"type": type(error).__name__, "reason": reason[:200] if reason.startswith("meguri-core") else type(error).__name__})
                        raise
            def client_factory(**kwargs):
                return ObservedClient(transport=transport, **kwargs)
            config = {"core_url": self.core_url or self.server.origin,
                      "timeout_seconds": 8, "identity_salt_env": "MEGURI_SIMULATION_IDENTITY_SALT",
                      "core_token_file_env": "MEGURI_SIMULATION_UNUSED_TOKEN_FILE",
                      "core_token_file": str(self.token_file or ""),
                      "turn_checkpoint_file": str(self.state_dir / "checkpoints.json"),
                      "timeprism_todo_draft_file": str(self.state_dir / "drafts.json"),
                      "route_all_private_messages": True, "allow_group_messages": True,
                      "route_all_group_messages": True, "bilingual_zh_ja": False,
                      **self.config}
            if config.get("timeprism_todo_creation_enabled"):
                if self.core_url:
                    raise ValueError("Configured-Core mode does not enable test Todo writes")
                config.setdefault("timeprism_owner_user_id", 701)
                config.setdefault("timeprism_todo_operator_senders", [f"Simulation:{self.context.account}:owner"])
            with patch.object(module, "HttpMeguriCoreClient", client_factory):
                self.plugin = module.MeguriGatewayPlugin(self.context, config)
            return self
        except BaseException:
            await self.__aexit__(*sys.exc_info())
            raise

    async def __aexit__(self, *_args):
        try:
            if self.plugin:
                await self.plugin.terminate()
        finally:
            await self.context.client.aclose()
            self._environment.stop()
            self._modules.stop()
            sys.modules.pop(PLUGIN_MODULE, None)
            if self._old_module is not None:
                sys.modules[PLUGIN_MODULE] = self._old_module
            if self._old_attribute is _MISSING:
                if hasattr(self._parent_module, "main"):
                    delattr(self._parent_module, "main")
            else:
                self._parent_module.main = self._old_attribute
            await asyncio.to_thread(self.server.close)
            self.temporary.cleanup()

    def event(self, text, **kwargs):
        return SyntheticEvent(self.context, text, **kwargs)

    async def dispatch(self, event):
        start_requests, start_events, start_errors = len(self.requests), len(self.events), len(self.errors)
        result = {"chat_type": "private" if event.is_private_chat() else "group",
                  "target": event.unified_msg_origin, "input": event.text,
                  "outputs": [], "accepted_by_local_collector": 0, "findings": []}
        try:
            async with asyncio.timeout(60):
                async for chain in self.plugin.on_message(event):
                    result["outputs"].append(chain.text)
                    await event.send(chain)
                    result["accepted_by_local_collector"] += 1
        except Exception as error:
            result["error"] = type(error).__name__
        result["requests"] = self.requests[start_requests:]
        result["events"] = self.events[start_events:]
        result["core_errors"] = self.errors[start_errors:]
        result["stopped"], result["default_llm_blocked"] = event.stopped, event.llm_blocked
        text = "\n".join(result["outputs"])
        has_receipt = any(request["path"] == "/v1/reminders/timeprism/todos"
                          and 200 <= request.get("status", 0) < 300 for request in result["requests"])
        drafts_file = self.state_dir / "drafts.json"
        if not has_receipt and drafts_file.exists():
            drafts = json.loads(drafts_file.read_text(encoding="utf-8")).get("drafts", {})
            has_receipt = any(draft.get("state") == "confirmed" and f"#{draft.get('todo_id')}" in text
                              for draft in drafts.values())
        result["verified_task_receipt"] = has_receipt
        if re.search(r"(?:已(?:经)?(?:创建|取消)|已经安排|已设置).{0,15}(?:提醒|待办)|提醒已(?:经)?取消|我(?:会|再|将).{0,16}提醒你", text) and not has_receipt:
            result["findings"].append("action_claim_without_observed_write_receipt")
        if any(word in text for word in ("服务暂时不可用", "暂时无法读取")):
            result["findings"].append("core_degraded_reply")
        self.results.append(result)
        return result

    def report(self):
        return {"mode": "configured-core" if self.core_url else "protocol-fixture",
                "run_id": self.run_id, "collector": self.server.origin,
                "delivery_evidence": "local_http_collector_acceptance",
                "real_platform_sends": 0, "cases": self.results,
                "captured_deliveries": list(self.server.deliveries)}
