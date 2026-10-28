import json
from pathlib import Path
import re
import tempfile
import unittest

import httpx

from adapters.astrbot.astrbot_plugin_meguri_gateway.daily_reports import DailyReportPoller
from tests.astrbot_simulation import AstrBotSimulation


class AstrBotConversationSimulationTests(unittest.IsolatedAsyncioTestCase):
    async def test_private_event_crosses_real_http_sse_and_collector(self):
        async with AstrBotSimulation() as sim:
            result = await sim.dispatch(sim.event("模拟用户你好"))
            self.assertEqual(result["outputs"], ["模拟回复：模拟用户你好"])
            self.assertEqual(result["accepted_by_local_collector"], 1)
            self.assertEqual([r["path"] for r in result["requests"]][:2], ["/v1/hello", "/v1/turns"])
            self.assertEqual(result["events"][-1]["type"], "turn.completed")
            self.assertEqual(sim.server.deliveries[0]["target"], result["target"])
            self.assertTrue(result["default_llm_blocked"])
            self.assertTrue(result["stopped"])
            hello = result["requests"][0]["body"]
            self.assertEqual(hello["identity"]["client_instance"]["profile"], "astrbot")
            self.assertFalse(hello["permissions"]["formal_memory_write"])
            self.assertFalse(hello["capabilities"]["formal_memory"])

    async def test_same_sender_private_and_two_groups_have_distinct_sessions_and_destinations(self):
        async with AstrBotSimulation() as sim:
            results = [await sim.dispatch(sim.event("隔离测试", group=group, message_id="same-id"))
                       for group in (None, "test-group-a", "test-group-b")]
            turns = [r["body"] for r in sim.requests if r["path"] == "/v1/turns"]
            self.assertEqual(len(turns), 3)
            self.assertEqual(len({t["identity"]["session"]["id"] for t in turns}), 3)
            self.assertEqual(len({t["identity"]["meguri_user"]["id"] for t in turns}), 1)
            self.assertEqual(len({d["target"] for d in sim.server.deliveries}), 3)
            self.assertTrue(all(r["accepted_by_local_collector"] == 1 for r in results))

    async def test_group_members_have_separate_short_context(self):
        async with AstrBotSimulation() as sim:
            for sender in ("member-a", "member-b"):
                await sim.dispatch(sim.event("群成员测试", group="test-group", sender=sender))
            sessions = [r["body"]["identity"]["session"]["id"] for r in sim.requests if r["path"] == "/v1/turns"]
            self.assertEqual(len(set(sessions)), 2)

    async def test_successive_messages_in_one_session_have_fresh_turns_and_text(self):
        async with AstrBotSimulation() as sim:
            for text in ("第一条测试", "第二条测试", "第三条测试"):
                result = await sim.dispatch(sim.event(text))
                self.assertEqual(result["outputs"], [f"模拟回复：{text}"])
            self.assertEqual(sim.server.turn_counter, 3)
            self.assertEqual(sum(r["accepted_by_local_collector"] for r in sim.results), 3)
            self.assertEqual([r["events"][-1]["sequence"] for r in sim.results], [5, 10, 15])

    async def test_foreign_session_is_rejected_without_applying_its_reply(self):
        async with AstrBotSimulation() as sim:
            sim.server.foreign_session = True
            sim.server.reply = "foreign-session-marker"
            result = await sim.dispatch(sim.event("会话边界测试"))
            self.assertIn("core_degraded_reply", result["findings"])
            self.assertNotIn("foreign-session-marker", "".join(result["outputs"]))
            self.assertEqual(result["core_errors"][0]["reason"], "meguri-core event crossed the requested session scope")

    async def test_group_gate_does_not_create_a_turn_or_delivery(self):
        async with AstrBotSimulation(config={"allow_group_messages": False}) as sim:
            result = await sim.dispatch(sim.event("群聊测试", group="test-group"))
            self.assertEqual((result["outputs"], sim.requests, sim.server.deliveries), ([], [], []))

    async def test_other_plugin_command_is_released_without_a_turn(self):
        async with AstrBotSimulation(config={"passthrough_commands": ["jrlp"]}) as sim:
            result = await sim.dispatch(sim.event("jrlp"))
            self.assertEqual((result["outputs"], sim.requests), ([], []))
            self.assertFalse(result["stopped"])

    async def test_duplicate_event_has_no_second_turn_or_output(self):
        async with AstrBotSimulation() as sim:
            event = sim.event("重复测试")
            await sim.dispatch(event)
            repeated = await sim.dispatch(event)
            self.assertEqual(repeated["outputs"], [])
            self.assertEqual(len(sim.server.turns), 1)
            self.assertEqual(len(sim.server.deliveries), 1)

    async def test_interrupted_sse_resumes_after_checkpoint_without_repeating_output(self):
        async with AstrBotSimulation() as sim:
            sim.server.split_stream = True
            result = await sim.dispatch(sim.event("断流重连测试"))
            reads = [r for r in sim.server.requests if r["path"].endswith("/events")]
            self.assertEqual([r["query"]["after_sequence"] for r in reads], [["0"], ["3"]])
            self.assertEqual(result["accepted_by_local_collector"], 1)
            self.assertEqual(result["events"][-1]["type"], "turn.completed")
            checkpoint = json.loads((sim.state_dir / "checkpoints.json").read_text(encoding="utf-8"))
            self.assertEqual(next(iter(checkpoint["entries"].values()))["terminal"], "turn.completed")

    async def test_core_failure_allows_same_message_to_retry(self):
        async with AstrBotSimulation() as sim:
            sim.server.create_status = 503
            event = sim.event("失败后重试")
            failed = await sim.dispatch(event)
            self.assertIn("core_degraded_reply", failed["findings"])
            self.assertEqual(sim.server.turns, {})
            sim.server.create_status = 202
            recovered = await sim.dispatch(event)
            self.assertEqual(recovered["outputs"], ["模拟回复：失败后重试"])
            self.assertEqual(recovered["events"][-1]["type"], "turn.completed")

    async def test_failed_turn_is_not_reported_as_successful_generation(self):
        async with AstrBotSimulation() as sim:
            sim.server.terminal = "turn.failed"
            result = await sim.dispatch(sim.event("执行失败测试"))
            self.assertIn("core_degraded_reply", result["findings"])
            self.assertEqual(result["events"][-1]["type"], "turn.failed")

    async def test_disabled_and_unavailable_retrieval_are_preserved(self):
        for status in ("disabled", "unavailable"):
            async with AstrBotSimulation() as sim:
                sim.server.retrieval = status
                result = await sim.dispatch(sim.event("联网状态测试"))
                retrieval = next(e for e in result["events"] if e["type"] == "retrieval.completed")
                self.assertEqual(retrieval["lanes"][0]["status"], status)
                self.assertEqual(retrieval["lanes"][0]["result_count"], 0)

    async def test_collector_rejection_preserves_generated_text_but_no_delivery_success(self):
        async with AstrBotSimulation() as sim:
            sim.server.collect_status = 503
            result = await sim.dispatch(sim.event("发送器拒绝测试"))
            self.assertEqual(result["error"], "HTTPStatusError")
            self.assertEqual(result["outputs"], ["模拟回复：发送器拒绝测试"])
            self.assertEqual(result["accepted_by_local_collector"], 0)
            self.assertEqual(sim.server.deliveries, [])

    async def test_reminder_prepare_confirm_and_repeat_confirm_write_once(self):
        async with AstrBotSimulation(config={"timeprism_todo_creation_enabled": True}) as sim:
            preview = await sim.dispatch(sim.event("十分钟后提醒我喝水"))
            self.assertIn("尚未写入", preview["outputs"][0])
            self.assertEqual(sim.server.todo_writes, [])
            self.assertEqual(sim.server.turns, {})
            draft = re.search(r"/meguri todo confirm ([a-z0-9]+)", preview["outputs"][0]).group(1)
            confirmed = await sim.dispatch(sim.event(f"/meguri todo confirm {draft}"))
            self.assertIn("已创建 TimePrism 待办 #701", confirmed["outputs"][0])
            repeated = await sim.dispatch(sim.event(f"/meguri todo confirm {draft}"))
            self.assertIn("不会重复创建", repeated["outputs"][0])
            self.assertEqual(repeated["findings"], [])
            self.assertEqual(len(sim.server.todo_writes), 1)
            self.assertEqual(confirmed["findings"], [])

    async def test_cancelled_draft_cannot_be_confirmed(self):
        async with AstrBotSimulation(config={"timeprism_todo_creation_enabled": True}) as sim:
            preview = await sim.dispatch(sim.event("十分钟后提醒我伸展"))
            draft = re.search(r"/meguri todo confirm ([a-z0-9]+)", preview["outputs"][0]).group(1)
            cancelled = await sim.dispatch(sim.event(f"/meguri todo cancel {draft}"))
            self.assertIn("没有写入", cancelled["outputs"][0])
            await sim.dispatch(sim.event(f"/meguri todo confirm {draft}"))
            self.assertEqual(sim.server.todo_writes, [])

    async def test_group_and_unapproved_sender_cannot_confirm_private_draft(self):
        async with AstrBotSimulation(config={"timeprism_todo_creation_enabled": True}) as sim:
            preview = await sim.dispatch(sim.event("十分钟后提醒我休息"))
            draft = re.search(r"/meguri todo confirm ([a-z0-9]+)", preview["outputs"][0]).group(1)
            group = await sim.dispatch(sim.event(f"/meguri todo confirm {draft}", group="test-group"))
            stranger = await sim.dispatch(sim.event(f"/meguri todo confirm {draft}", sender="stranger"))
            self.assertIn("只允许", group["outputs"][0])
            self.assertIn("未获准", stranger["outputs"][0])
            self.assertEqual(sim.server.todo_writes, [])

    async def test_uncertain_todo_retry_reuses_original_request_id(self):
        async with AstrBotSimulation(config={"timeprism_todo_creation_enabled": True}) as sim:
            preview = await sim.dispatch(sim.event("十分钟后提醒我喝茶"))
            draft = re.search(r"/meguri todo confirm ([a-z0-9]+)", preview["outputs"][0]).group(1)
            sim.server.todo_status = 503
            await sim.dispatch(sim.event(f"/meguri todo confirm {draft}"))
            sim.server.todo_status = 200
            recovered = await sim.dispatch(sim.event(f"/meguri todo confirm {draft}"))
            writes = [r["body"] for r in sim.requests if r["path"] == "/v1/reminders/timeprism/todos"]
            self.assertEqual(len(writes), 2)
            self.assertEqual(writes[0]["requestId"], writes[1]["requestId"])
            self.assertEqual(len(sim.server.todo_writes), 1)
            self.assertIn("#701", recovered["outputs"][0])

    async def test_generated_action_claim_without_receipt_is_a_finding(self):
        async with AstrBotSimulation() as sim:
            sim.server.reply = "提醒已经取消了。"
            result = await sim.dispatch(sim.event("取消提醒的能力测试"))
            self.assertIn("action_claim_without_observed_write_receipt", result["findings"])
            self.assertEqual(sim.server.todo_writes, [])

    async def test_proactive_private_and_group_deliveries_are_collected_once(self):
        async with AstrBotSimulation() as sim:
            targets = [sim.event("日报").unified_msg_origin,
                       sim.event("日报", group="test-group").unified_msg_origin]
            poller = DailyReportPoller(sim.plugin.gateway.core, sim.plugin._send_daily_report,
                                      targets=targets, kinds=["ai_news"], state_file=sim.state_dir / "daily.json")
            self.assertEqual((await poller.poll_once())["sent"], 2)
            self.assertEqual((await poller.poll_once())["sent"], 0)
            self.assertEqual([d["target"] for d in sim.server.deliveries], targets)

    async def test_listener_and_disposable_state_are_closed(self):
        import adapters.astrbot.astrbot_plugin_meguri_gateway as package
        previous_module = getattr(package, "main", None)
        async with AstrBotSimulation() as sim:
            origin, state = sim.server.origin, sim.state_dir
            await sim.dispatch(sim.event("清理测试"))
        self.assertFalse(state.exists())
        self.assertFalse(sim.server.thread.is_alive())
        self.assertIs(getattr(package, "main", None), previous_module)
        async with httpx.AsyncClient(timeout=1, trust_env=False) as client:
            with self.assertRaises(httpx.TransportError):
                await client.get(origin + "/health")

    async def test_credentials_never_enter_simulation_report(self):
        with tempfile.TemporaryDirectory() as directory:
            token = Path(directory) / "token.txt"
            token.write_text("simulation-secret-should-not-be-reported", encoding="utf-8")
            async with AstrBotSimulation(token_file=token) as sim:
                await sim.dispatch(sim.event("凭据隔离测试"))
                self.assertNotIn(token.read_text(encoding="utf-8"), json.dumps(sim.report()))

    async def test_configured_core_mode_rejects_todo_test_writes(self):
        with self.assertRaisesRegex(ValueError, "does not enable test Todo writes"):
            async with AstrBotSimulation(core_url="http://127.0.0.1:18080", config={"timeprism_todo_creation_enabled": True}):
                self.fail("must reject before any request")

    async def test_repeatable_runner_passes_its_fixture_acceptance_checks(self):
        from tools.simulate_astrbot_conversations import run_simulation
        report = await run_simulation()
        self.assertEqual(report["mode"], "protocol-fixture")
        self.assertEqual(report["summary"]["checks_failed"], 0)
        self.assertEqual(report["real_platform_sends"], 0)
        self.assertTrue(report["resources_closed"])
