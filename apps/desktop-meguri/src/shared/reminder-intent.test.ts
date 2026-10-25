import { describe, expect, it } from 'vitest'
import { parseReminderIntent, parseShanghaiTime, shanghaiWallTime } from './reminder-intent.mjs'

const now = Date.parse('2026-10-04T12:00:00+08:00')

describe('shared natural language reminder parsing', () => {
  it.each([
    ['明天上午 9 点提醒我开会。', '开会', '2026-10-05T09:00'],
    ['今晚 8 点提醒我给小王回电话。', '给小王回电话', '2026-10-04T20:00'],
    ['提醒我明天下午三点半检查订单', '检查订单', '2026-10-05T15:30'],
    ['后天早上九点五十分提醒我吃药', '吃药', '2026-10-06T09:50'],
    ['2026年10月6日下午3点一刻提醒我取快递', '取快递', '2026-10-06T15:15'],
    ['2026-10-06 15:45提醒我开会', '开会', '2026-10-06T15:45'],
    ['十分钟后提醒我休息', '休息', '2026-10-04T12:10'],
    ['两小时后提醒我喝水', '喝水', '2026-10-04T14:00'],
    ['明天凌晨十二点提醒我休息', '休息', '2026-10-05T00:00'],
    ['提醒我睡觉', '睡觉', '2026-10-05T12:00'],
    ['提醒我喝水', '喝水', '2026-10-04T12:30'],
  ])('parses %s', (text, title, dueAt) => {
    expect(parseReminderIntent(text, now)).toMatchObject({ status: 'ready', behavior: 'reminder', title, dueAt, timezone: 'Asia/Shanghai' })
  })

  it.each([
    '明天上午9点提醒我', '明天上午12点提醒我开会', '明天下午15点提醒我开会',
    '今天上午9点提醒我开会', '2026-02-30 15:00提醒我开会',
    '明天上午9点或者下午3点提醒我开会', '下周一上午9点提醒我开会',
    '明天下午3点左右提醒我开会', '明天纽约时间9点提醒我开会',
    '明天夏令时9点提醒我开会', '明天上午9点提醒我开会，明天下午3点提醒我吃药',
    '今晚12点提醒我休息', '明天下午12点提醒我吃饭',
    '明天上午9点提醒我或者替我执行订单检查',
    '明天9:提醒我开会', '明天09:00:30提醒我开会',
  ])('clarifies %s', text => expect(parseReminderIntent(text, now)).toMatchObject({ status: 'clarify' }))

  it('uses the requested day and an inferred clock when only the date is given', () => {
    expect(parseReminderIntent('明天提醒我开会', now)).toMatchObject({
      status: 'ready',
      title: '开会',
      dueAt: '2026-10-05T12:30',
      inferred: 'next-half-hour',
    })
  })

  it.each([
    '明天下午3点帮我检查订单', '明天15:00执行 shell 脚本', '明天15:00发送消息给小王',
    '明天15:00电脑关机', '每天上午9点提醒我开会', '关机后也要提醒我开会',
    '明天上午9点提醒我开会，即使退出Meguri也要提醒', '取消开会提醒', '把提醒改到明天15:00',
    '不要明天上午9点提醒我开会', '别提醒我开会',
    '稍后帮我检查订单', '明早帮我检查订单', '10月5日帮我检查订单', '明天上午9点提醒我开会并自动发送消息给小王',
  ])('blocks unsupported scheduling %s', text => expect(parseReminderIntent(text, now)).toMatchObject({ status: 'unsupported' }))

  it('keeps ordinary chat and treats reminder content as notification text', () => {
    expect(parseReminderIntent('今天感觉怎么样？', now)).toEqual({ status: 'none' })
    expect(parseReminderIntent('帮我检查订单', now)).toEqual({ status: 'none' })
    expect(parseReminderIntent('明天15:00提醒我运行脚本', now)).toMatchObject({ behavior: 'reminder', title: '运行脚本' })
  })

  it.each([
    '帮我查看一下今天Codex是否更新了',
    '今天帮我联网搜索Codex更新',
    '今天查看 Todo 列表里已完成的任务，并说明原因',
    '解释一下附件里“明天提醒我喝水”这句话',
    '“明天提醒我喝水”是什么意思？',
    '帮我总结今天的运行日志',
    '打开今天的报告',
    '我今天发送的邮件怎么查',
    '提醒功能怎么用',
    '我的提醒有哪些',
    '提醒我是什么意思',
    '为什么没有提醒我',
    '帮我分析附件中的提醒设置',
  ])('does not intercept an ordinary query: %s', text => {
    expect(parseReminderIntent(text, now)).toEqual({ status: 'none' })
  })

  it.each([
    '明天15:00提醒我喝水',
    '明天15:00提醒喝水',
    '提醒喝水',
    '明天15:00帮我检查订单',
    '稍后帮我检查订单',
  ])('still reviews explicit future reminder or scheduled action: %s', text => {
    expect(parseReminderIntent(text, now).status).not.toBe('none')
  })

  it('keeps quoted temporal words from shifting a real reminder command', () => {
    expect(parseReminderIntent('把“今天”作为标题，明天15:00提醒我开会', now))
      .toMatchObject({ status: 'ready', title: '开会', dueAt: '2026-10-05T15:00' })
  })

  it('resolves clarification to new immutable review values', () => {
    expect(parseReminderIntent('明天3点提醒我开会', now, { title: '开会', dueAt: '2026-10-05T15:00' }))
      .toMatchObject({ status: 'ready', title: '开会', dueAt: '2026-10-05T15:00' })
  })

  it('infers a useful date and time when both are omitted', () => {
    expect(parseReminderIntent('提醒我睡觉', now)).toMatchObject({
      status: 'ready',
      dueAt: '2026-10-05T12:00',
      inferred: 'sleep',
    })
    expect(parseReminderIntent('提醒我喝水', now)).toMatchObject({
      status: 'ready',
      dueAt: '2026-10-04T12:30',
      inferred: 'next-half-hour',
    })
  })

  it('crosses midnight and year boundaries in Shanghai independently of host DST', () => {
    const midnight = Date.parse('2026-12-31T23:59:00+08:00')
    expect(parseReminderIntent('明天上午9点提醒我开会', midnight)).toMatchObject({ dueAt: '2027-01-01T09:00' })
    expect(parseReminderIntent('两分钟后提醒我休息', midnight)).toMatchObject({ dueAt: '2027-01-01T00:01' })
    expect(parseShanghaiTime('2026-03-08T02:30')).toBe(Date.parse('2026-03-08T02:30:00+08:00'))
    expect(shanghaiWallTime(Date.parse('2026-10-04T16:00:00Z'))).toBe('2026-10-05T00:00')
    expect(parseShanghaiTime('2026-02-29T15:00')).toBeNaN()
    expect(parseShanghaiTime('2028-02-29T15:00')).toBe(Date.parse('2028-02-29T15:00+08:00'))
  })
})
