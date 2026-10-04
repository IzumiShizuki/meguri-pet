"""Rendered Vue smoke using a mocked development host, never production writes.

Run with the webapp-testing with_server.py helper and existing py314 Playwright.
Use a dedicated Vite port. Screenshots go to an ignored output directory.
"""
import argparse
import json
from pathlib import Path
from playwright.sync_api import sync_playwright, expect

parser = argparse.ArgumentParser()
parser.add_argument('--url', default='http://127.0.0.1:4186/?preview=stage')
parser.add_argument('--output', required=True)
args = parser.parse_args()
output = Path(args.output).resolve()
output.mkdir(parents=True, exist_ok=True)

with sync_playwright() as p:
    browser = p.chromium.launch(channel='msedge', headless=True)
    try:
        context = browser.new_context(viewport={'width': 900, 'height': 760})
        # Prevent any test from reaching real Core, gateway, TTS, or TimePrism.
        context.route('**/*', lambda route: route.continue_() if route.request.url.startswith('http://127.0.0.1:4186/') else route.abort())
        context.add_init_script("""
          Date.now = () => Date.parse('2026-10-04T12:00:00+08:00');
          window.__reminderTest = { calls: [], records: [], failOnce: false, voice: null };
          let bridge;
          Object.defineProperty(window, 'meguriDesktop', {
            configurable: true, get: () => bridge,
            set: value => {
              const state = window.__reminderTest;
              const get = value.preferences.get;
              value.preferences.get = async () => {
                const result = await get(); result.chat.expanded = true; return result;
              };
              value.shizuki = {
                status: async () => ({ connected: true, origin: 'https://test.invalid', userId: 42, email: 'test@example.invalid', error: '' }),
                pendingCreates: async () => ({ ok: true, requests: [] }),
                listTodos: async () => ({ ok: true, todos: state.records }),
                createTodo: async input => {
                  state.calls.push(input);
                  if (state.failOnce) { state.failOnce = false; return { ok: false, code: 'network_error', error: '测试连接中断，服务器尚未确认' }; }
                  const todo = { todoId: state.records.length + 1, title: input.title, dueAt: input.dueAt + ':00', reminderEnabled: true, done: false };
                  state.records.push(todo); return { ok: true, todo };
                },
                updateTodo: async (id, input) => {
                  const previous = state.records.find(item => item.todoId === id);
                  const todo = { ...previous, title: input.cancel ? previous.title : input.title, dueAt: input.cancel ? previous.dueAt : input.dueAt + ':00', reminderEnabled: !input.cancel };
                  state.records = state.records.map(item => item.todoId === id ? todo : item);
                  return { ok: true, todo };
                },
                completeTodo: async () => ({ ok: false }), onReminder: () => () => {},
              };
              value.voice = {
                start: async () => ({ ok: true }), cancel: async () => ({ ok: true }),
                onChanged: listener => { state.voice = listener; return () => {}; },
              };
              bridge = value;
            },
          });
        """)
        page = context.new_page()
        errors = []
        page.on('pageerror', lambda error: errors.append(str(error)))
        page.goto(args.url)
        page.wait_for_load_state('networkidle')
        # Inspect the mounted page before relying on its selectors.
        labels = page.locator('button[aria-label]').evaluate_all('(items) => items.map(item => item.getAttribute("aria-label"))')
        assert '发送消息' in labels, labels
        draft = page.get_by_role('textbox', name='消息', exact=True)
        send = page.get_by_role('button', name='发送消息', exact=True)
        review = page.get_by_role('region', name='提醒核对')

        draft.fill('明天上午9点提醒我开会。')
        send.click()
        expect(review).to_contain_text('2026-10-05 09:00')
        expect(review).to_contain_text('Asia/Shanghai')
        assert page.evaluate('window.__reminderTest.calls.length') == 0
        page.screenshot(path=str(output / 'review.png'))
        review.get_by_role('button', name='确认创建提醒', exact=True).click()
        expect(review).to_contain_text('已创建提醒 #1')
        expect(draft).to_have_value('')
        review.get_by_role('button', name='关闭', exact=True).click()

        draft.fill('明天3点提醒我开会')
        send.click()
        expect(review).to_contain_text('上午还是下午')
        review.locator('input[type="datetime-local"]').fill('2026-10-05T15:00')
        review.get_by_role('button', name='重新核对', exact=True).click()
        expect(review).to_contain_text('2026-10-05 15:00')
        page.screenshot(path=str(output / 'clarified.png'))
        assert page.evaluate('window.__reminderTest.calls.length') == 1
        review.get_by_role('button', name='取消核对', exact=True).click()

        draft.fill('明天下午3点帮我检查订单')
        send.click()
        expect(review).to_contain_text('没有安排任何动作')
        assert page.evaluate('window.__reminderTest.calls.length') == 1
        review.get_by_role('button', name='取消核对', exact=True).click()

        draft.fill('')
        page.evaluate('window.__reminderTest.voice({ state: "completed", text: "今晚8点提醒我给小王回电话" })')
        expect(draft).to_have_value('今晚8点提醒我给小王回电话')
        assert page.evaluate('window.__reminderTest.calls.length') == 1
        send.click()
        expect(review).to_contain_text('2026-10-04 20:00')
        page.evaluate('window.__reminderTest.failOnce = true')
        review.get_by_role('button', name='确认创建提醒', exact=True).click()
        expect(review).to_contain_text('服务器尚未确认')
        expect(draft).to_have_value('今晚8点提醒我给小王回电话')
        page.screenshot(path=str(output / 'failed-retry.png'))
        review.get_by_role('button', name='重试 / 核对写入结果', exact=True).click()
        expect(review).to_contain_text('已创建提醒 #2')
        assert page.evaluate('window.__reminderTest.calls[1].requestId === window.__reminderTest.calls[2].requestId')
        review.get_by_role('button', name='关闭', exact=True).click()

        page.get_by_role('button', name='打开 Shizuki 待办', exact=True).click()
        panel = page.get_by_role('region', name='Shizuki 待办', exact=True)
        expect(panel).to_contain_text('给小王回电话')
        item = panel.locator('.shizuki-todo-item').filter(has_text='给小王回电话')
        item.get_by_role('button', name='取消提醒', exact=True).click()
        panel.get_by_role('button', name='确认取消提醒', exact=True).click()
        expect(panel).to_contain_text('已取消提醒：给小王回电话')
        assert page.evaluate('window.__reminderTest.records[1].reminderEnabled') is False
        page.screenshot(path=str(output / 'cancelled.png'))
        assert not errors, errors
        print(json.dumps({'ok': True, 'scenarios': ['review-before-write', 'clarification', 'unsupported-action', 'voice-draft', 'failure-retry', 'selected-cancellation'], 'screenshots': str(output)}, ensure_ascii=False))
    finally:
        browser.close()
