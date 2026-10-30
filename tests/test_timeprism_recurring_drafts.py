import json
import tempfile
import unittest
from datetime import datetime
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock

import httpx
from adapters.astrbot.astrbot_plugin_meguri_gateway.client import HttpMeguriCoreClient, CoreProtocolError
from adapters.astrbot.astrbot_plugin_meguri_gateway.models import PlatformMessage
from adapters.astrbot.astrbot_plugin_meguri_gateway.timeprism_todo_creation import (
    TimePrismTodoCreation, TimePrismTodoDraftStore, parse_timeprism_reminder,
)

FIXTURES = json.loads((Path(__file__).resolve().parents[1] / 'contracts/timeprism/recurring-fixtures.json').read_text(encoding='utf-8'))


class RecurringParserTest(unittest.TestCase):
    def test_shared_contract_fixtures(self):
        for case in FIXTURES:
            with self.subTest(case=case['name']):
                result = parse_timeprism_reminder(case['text'], datetime.fromisoformat(case['now']))
                self.assertEqual(result['status'], 'ready')
                self.assertEqual(result['kind'], 'recurring')
                self.assertEqual(result['schedule'], case['schedule'])

    def test_invalid_or_ambiguous_rules_do_not_become_writes(self):
        for text in ['每月1号18:00提醒我虚构检查', '每周一8点提醒我虚构检查',
                     '从2030-02-30到2030-03-01每天18:00提醒我虚构检查',
                     '从2030-01-03到2030-01-01每天18:00提醒我虚构检查',
                     '时区 America/New_York 从2030-03-10T02:30到2030-03-20每天18:00提醒我虚构检查',
                     '每周一18:00提前-1分钟提醒我虚构检查',
                     '时区 Unknown/Nowhere 每天18:00提醒我虚构检查',
                     '每天18:00提前43201分钟提醒我虚构检查',
                     '每天18:00提前366天提醒我虚构检查',
                     '每天18:00提醒我检查并删除文件']:
            with self.subTest(text=text):
                self.assertNotEqual(parse_timeprism_reminder(text)['status'], 'ready')

    def test_unconsumed_time_qualifiers_are_not_silently_put_in_the_title(self):
        now=datetime.fromisoformat('2030-01-01T12:00:00+08:00')
        for text in ['明天起每天18:00提醒我虚构检查', '仅本周每周一18:00提醒我虚构检查',
                     '每天18:00:30提醒我虚构检查', '每天18:00仅限本周提醒我虚构检查',
                     '每周一18:00到下周结束提醒我虚构检查',
                     '美东时间每天18:00提醒我虚构检查']:
            with self.subTest(text=text): self.assertEqual(parse_timeprism_reminder(text,now)['status'],'clarify')
        result=parse_timeprism_reminder('每天18:00提醒我检查秒表和本周报告',now)
        self.assertEqual(result['status'],'ready')
        self.assertEqual(result['title'],'检查秒表和本周报告')

    def test_complete_iana_timezone_is_not_truncated(self):
        for zone in ['Etc/GMT+8','Etc/GMT-8','America/Argentina/Buenos_Aires']:
            with self.subTest(zone=zone):
                result=parse_timeprism_reminder(f'时区 {zone} 每天18:00提醒我虚构检查')
                self.assertEqual(result['status'],'ready')
                self.assertEqual(result['schedule']['timeZoneId'],zone)
                self.assertEqual(result['title'],'虚构检查')

    def test_legacy_one_off_still_works(self):
        result = parse_timeprism_reminder('明天18:30提醒我虚构检查', datetime.fromisoformat('2029-12-30T12:00:00+08:00'))
        self.assertEqual(result['status'], 'ready')
        self.assertEqual(result['due_at'], '2029-12-31T18:30')


class RecurringDraftFlowTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='meguri-recurring-draft-')
        self.addCleanup(self.temp.cleanup)
        self.store = TimePrismTodoDraftStore(Path(self.temp.name) / 'drafts.json')
        self.core = SimpleNamespace(create_timeprism_recurring_rule=AsyncMock(), create_timeprism_todo=AsyncMock())
        self.flow = TimePrismTodoCreation(self.core, owner_user_id=42, owner_senders={'qq:fixture:owner'}, store=self.store)

    def message(self, text, **kwargs):
        values = dict(platform='qq', account_id='fixture', sender_id='owner', conversation_id='owner', message_id='fixture', text=text, chat_type='private')
        values.update(kwargs)
        return PlatformMessage(**values)

    async def draft(self):
        preview = await self.flow.handle(self.message(FIXTURES[0]['text']), 'actor-owner')
        self.assertIn('尚未写入', preview)
        self.assertIn('Asia/Shanghai', preview)
        self.assertIn('10', preview)
        self.core.create_timeprism_recurring_rule.assert_not_awaited()
        return next(iter(json.loads(self.store.file_path.read_text(encoding='utf-8'))['drafts'].values()))

    async def test_preview_confirm_and_repeat_use_one_request(self):
        draft = await self.draft()
        self.core.create_timeprism_recurring_rule.return_value = {'state':'confirmed','ruleId':91}
        text = '/meguri todo confirm ' + draft['draft_id']
        reply = await self.flow.handle(self.message(text), 'actor-owner')
        self.assertIn('91', reply)
        await self.flow.handle(self.message(text), 'actor-owner')
        self.core.create_timeprism_recurring_rule.assert_awaited_once_with(owner_user_id=42, request_id=draft['request_id'], **FIXTURES[0]['schedule'])
        self.core.create_timeprism_todo.assert_not_awaited()

    async def test_foreign_actor_group_and_cancel_do_not_write(self):
        draft = await self.draft()
        confirm = '/meguri todo confirm ' + draft['draft_id']
        await self.flow.handle(self.message(confirm), 'different-actor')
        await self.flow.handle(self.message(confirm, chat_type='group'), 'actor-owner')
        await self.flow.handle(self.message(confirm, sender_id='stranger'), 'actor-owner')
        await self.flow.handle(self.message('/meguri todo cancel ' + draft['draft_id']), 'actor-owner')
        await self.flow.handle(self.message(confirm), 'actor-owner')
        self.core.create_timeprism_recurring_rule.assert_not_awaited()

    async def test_edit_resets_identity_and_uncertain_retry_retains_it(self):
        draft = await self.draft()
        await self.flow.handle(self.message('/meguri todo edit ' + draft['draft_id'] + ' ' + FIXTURES[1]['text']), 'actor-owner')
        edited = self.store.get('actor-owner', draft['draft_id'])
        self.assertNotEqual(edited['request_id'], draft['request_id'])
        self.assertEqual(edited['schedule'], FIXTURES[1]['schedule'])
        self.core.create_timeprism_recurring_rule.return_value = {'state':'uncertain'}
        text = '/meguri todo confirm ' + draft['draft_id']
        await self.flow.handle(self.message(text), 'actor-owner')
        await self.flow.handle(self.message(text), 'actor-owner')
        self.assertEqual([c.kwargs['request_id'] for c in self.core.create_timeprism_recurring_rule.await_args_list], [edited['request_id']]*2)

    async def test_client_contract_and_receipt_validation(self):
        schedule = FIXTURES[0]['schedule']; sent=[]; wrong=False
        def transport(request):
            sent.append(request)
            body=json.loads(request.content)
            return httpx.Response(200,json={'requestId':body['requestId'],'state':'confirmed','ruleId':91,**schedule,'cronExpr':FIXTURES[0]['cronExpr'],'endAt':('2030-02-01T00:00:00' if wrong else schedule['endAt'])})
        client=HttpMeguriCoreClient(transport=httpx.MockTransport(transport))
        try:
            result=await client.create_timeprism_recurring_rule(owner_user_id=42,request_id='00000000-0000-4000-8000-000000000042',**schedule)
            self.assertEqual(result['ruleId'],91)
            self.assertEqual(sent[0].url.path,'/v1/reminders/timeprism/recurring-rules')
            self.assertEqual(sent[0].headers['X-Meguri-Session-ID'],'timeprism-todo-writer')
            self.assertEqual({k:v for k,v in json.loads(sent[0].content).items() if k!='requestId'},schedule)
            wrong=True
            with self.assertRaises(CoreProtocolError):
                await client.create_timeprism_recurring_rule(owner_user_id=42,request_id='00000000-0000-4000-8000-000000000042',**schedule)
        finally: await client.close()
