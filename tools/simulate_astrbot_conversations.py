"""Simulate AstrBot conversations over loopback; deliver only to a local collector."""
from __future__ import annotations

import argparse
import asyncio
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

import httpx
from tests.astrbot_simulation import AstrBotSimulation


async def run_simulation(*, core_url=None, token_file=None, output=None):
    checks = []
    async with AstrBotSimulation(core_url=core_url, token_file=token_file,
                                 config={"timeprism_todo_creation_enabled": not bool(core_url)}) as sim:
        async def send(name, text, **kwargs):
            result = await sim.dispatch(sim.event(text, **kwargs))
            result["name"] = name
            return result

        def check(name, condition):
            checks.append({"name": name, "passed": bool(condition)})

        for name, group in (("private_chat", None), ("group_chat", "test-group-a")):
            result = await send(name, "这是一条隔离测试消息，请简短回复收到。", group=group)
            check(name + "_completed_and_collected", result["accepted_by_local_collector"] == 1
                  and any(e["type"] == "turn.completed" for e in result["events"]))
        lookup = await send("current_information", "帮我查看一下今天Codex是否更新了")
        check("lookup_reply_collected", lookup["accepted_by_local_collector"] == 1)
        check("lookup_core_completed", any(e["type"] == "turn.completed" for e in lookup["events"]))

        if core_url:
            disabled = await send("todo_disabled", "/meguri todo confirm simulation-draft")
            check("disabled_todo_has_no_core_write", "尚未启用" in "".join(disabled["outputs"])
                  and not disabled["requests"])
            headers = {"Authorization": "Bearer " + Path(token_file).read_text(encoding="utf-8").strip()} if token_file else {}
            async with httpx.AsyncClient(timeout=8, trust_env=False) as client:
                response = await client.get(core_url.rstrip("/") + "/health", headers=headers)
                response.raise_for_status()
                health = response.json()
            report = sim.report()
            report["core_health"] = {key: health.get(key) for key in ("status", "service", "llm_provider", "web_search_provider", "build_id")}
        else:
            duplicate = sim.event("消息重投测试", message_id="duplicate-message")
            await sim.dispatch(duplicate)
            repeated = await sim.dispatch(duplicate)
            repeated["name"] = "duplicate_redelivery"
            check("duplicate_not_delivered_twice", repeated["accepted_by_local_collector"] == 0 and not repeated["requests"])
            preview = await send("reminder_preview", "十分钟后提醒我喝水")
            match = re.search(r"/meguri todo confirm ([a-z0-9]+)", "".join(preview["outputs"]))
            check("reminder_preview_does_not_write", match is not None and not sim.server.todo_writes)
            if match:
                draft = match.group(1)
                confirmed = await send("reminder_confirm", f"/meguri todo confirm {draft}")
                again = await send("reminder_repeat_confirm", f"/meguri todo confirm {draft}")
                check("confirm_receipt_and_single_write", confirmed["verified_task_receipt"]
                      and again["verified_task_receipt"] and len(sim.server.todo_writes) == 1)
            cancelled_preview = await send("cancel_preview", "十分钟后提醒我伸展")
            cancelled_id = re.search(r"/meguri todo confirm ([a-z0-9]+)", "".join(cancelled_preview["outputs"])).group(1)
            await send("draft_cancel", f"/meguri todo cancel {cancelled_id}")
            await send("cancelled_draft_confirm", f"/meguri todo confirm {cancelled_id}")
            check("cancel_does_not_create_task", len(sim.server.todo_writes) == 1)
            group_action = await send("group_task_denied", f"/meguri todo confirm {cancelled_id}", group="test-group-a")
            check("group_task_cannot_write", not group_action["requests"] and "只允许" in "".join(group_action["outputs"]))
            sim.server.collect_status = 503
            rejected = await send("collector_rejection", "模拟发送器拒绝")
            check("rejected_delivery_not_marked_success", rejected["accepted_by_local_collector"] == 0 and rejected.get("error") == "HTTPStatusError")
            sim.server.collect_status = 202
            sim.server.create_status = 503
            retry_event = sim.event("Core 失败重试")
            failed = await sim.dispatch(retry_event)
            failed["name"] = "core_unavailable"
            sim.server.create_status = 202
            recovered = await sim.dispatch(retry_event)
            recovered["name"] = "core_recovered_same_message"
            check("core_failure_retry_recovers", "core_degraded_reply" in failed["findings"]
                  and any(e["type"] == "turn.completed" for e in recovered["events"]))
            report = sim.report()

        report["checks"] = checks
        report["summary"] = {"cases": len(report["cases"]), "checks_passed": sum(c["passed"] for c in checks),
                             "checks_failed": sum(not c["passed"] for c in checks),
                             "captured_deliveries": len(report["captured_deliveries"]), "real_platform_sends": 0}
        report["findings"] = [{"case": c.get("name", "unnamed"), "code": finding}
                              for c in report["cases"] for finding in c["findings"]]
        if core_url and report["core_health"].get("web_search_provider") == "disabled":
            report["findings"].append({"case": "current_information", "code": "configured_core_web_search_disabled"})
    report["resources_closed"] = not sim.server.thread.is_alive() and not sim.state_dir.exists()
    if output:
        output = Path(output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--core-url", help="Optional existing loopback Core, including a temporary SSH forward")
    parser.add_argument("--core-token-file", type=Path, help="External token file; token contents are never reported")
    parser.add_argument("--output", type=Path, default=ROOT / ".codex-runtime/astrbot-simulation/latest.json")
    args = parser.parse_args(argv)
    report = asyncio.run(run_simulation(core_url=args.core_url, token_file=args.core_token_file, output=args.output))
    print(json.dumps({"mode": report["mode"], "collector": report["collector"], **report["summary"],
                      "resources_closed": report["resources_closed"], "findings": report["findings"],
                      "report": str(args.output)}, ensure_ascii=False, indent=2))
    return 1 if report["summary"]["checks_failed"] else 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    raise SystemExit(main())
