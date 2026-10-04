export const REMINDER_TIMEZONE = 'Asia/Shanghai'
export const REMINDER_ZONE_LABEL = 'Asia/Shanghai（UTC+08:00）'
const OFFSET = 8 * 60 * 60_000

export function parseShanghaiTime(value) {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?$/.exec(String(value || ''))
  if (!match) return NaN
  const [y, m, d, h, min, s] = match.slice(1).map(value => Number(value ?? 0))
  if (y < 2000 || y > 9999 || m < 1 || m > 12 || d < 1 || h > 23 || min > 59 || s > 59) return NaN
  const wall = new Date(Date.UTC(y, m - 1, d, h, min, s))
  if (wall.getUTCFullYear() !== y || wall.getUTCMonth() !== m - 1 || wall.getUTCDate() !== d) return NaN
  return wall.getTime() - OFFSET
}

export function shanghaiWallTime(epoch) {
  return new Date(epoch + OFFSET).toISOString().slice(0, 16)
}

function chineseNumber(value) {
  if (/^\d+$/.test(value)) return Number(value)
  const digits = '零一二三四五六七八九'
  const text = value.replace(/两/g, '二').replace(/〇/g, '零')
  if (text === '十') return 10
  if (text.includes('十')) {
    const [tens, ones] = text.split('十')
    return (tens ? digits.indexOf(tens) : 1) * 10 + (ones ? digits.indexOf(ones) : 0)
  }
  return text.length === 1 ? digits.indexOf(text) : NaN
}

const NUM = '[零〇一二两三四五六七八九十\\d]+'
const CLOCK = new RegExp(`(${NUM})\\s*(?:点|时|[:：])\\s*(?:(半|一刻|三刻)|(${NUM})\\s*(?:分)?)?`, 'g')
const DAY = /今天|明天|后天|今晚|今早|今晨/
const PART = /凌晨|早上|早晨|上午|中午|下午|傍晚|晚上|夜里/
const DATE = /(\d{4})[-年](\d{1,2})[-月](\d{1,2})(?:日|号)?/

/** Bounded grammar: unknown temporal qualifiers never become an executable instruction. */
export function parseReminderIntent(input, now = Date.now(), overrides = {}) {
  const text = String(input || '').trim()
  const reminder = /提醒(?:我|一下|我们)?/.exec(text)
  const temporal = /今天|明天|后天|今晚|今早|明早|明晚|稍后|一会|待会|点|时|[:：]|\d+[-年]\d+|\d+\s*月\s*\d+|定时|分钟后|小时后|下周|周[一二三四五六日天]|下个月|月底/.test(text)
  const action = /帮我|替我|自动|执行|发送|运行|打开|关闭|检查订单|关机|重启|删除|下载|上传|发消息|发邮件|回电话|拨打|购买/.test(text)
  if (!reminder && !(temporal && action)) return { status: 'none' }
  const behavior = reminder ? 'reminder' : 'action'
  const unsupported = message => ({ status: 'unsupported', behavior, message })
  const clarify = (message, title = '') => ({ status: 'clarify', behavior, message, title, timezone: REMINDER_TIMEZONE })
  if (/(?:取消|修改|更改|改到|推迟|不要|不用|别再?).*(?:提醒|定时)|(?:提醒).*(?:取消|改到)/.test(text))
    return unsupported('请打开 ☑ 待办，选择具体任务后核对并修改或取消提醒。')
  if (behavior === 'action')
    return unsupported('当前没有获授权的定时动作能力，无法替你检查订单、运行命令、控制电脑或发送消息；没有安排任何动作。可改为“提醒我检查订单”。')
  if (/提醒.*(?:或者|还是).*(?:帮我|替我|自动|执行)|(?:帮我|替我).*(?:或者|还是).*提醒/.test(text))
    return clarify('是提醒你做事，还是替你执行？当前只支持提醒，请在草稿中明确改写为“提醒我…”。')
  if (/(?:并|然后|同时)(?:且)?\s*(?:帮我|替我|自动|发送|执行|运行|关机)/.test(text))
    return unsupported('请求同时包含执行动作；当前只能提醒，不能安排外部执行。请单独描述提醒内容。')
  if (/(?:关机|退出|关闭桌宠|关闭\s*Meguri|离线).*(?:也|仍|后|提醒)|(?:即使|就算).*(?:关机|退出)/i.test(text))
    return unsupported('当前提醒由运行中的 Meguri 桌面触发。关机或退出后触发需要服务器持久化调度器，当前无法安排这种离线保证。')
  if (/每天|每周|每月|每隔|工作日|重复/.test(text)) return unsupported('当前只支持一次提醒，无法安排重复定时任务。')
  if (/纽约|东京|伦敦|美国|日本|美东|美西|夏令|冬令|UTC|GMT|时区|[A-Za-z_]+\/[A-Za-z_]+/i.test(text.replace(/Asia\/Shanghai|UTC\+0?8(?::00)?/g, '')))
    return clarify('请用 Asia/Shanghai（UTC+08:00）明确日期和时间；当前不解析其他时区或夏令时。')
  if (text.length > 1000 || (text.match(/提醒/g) || []).length > 1)
    return clarify('请一次只安排一项提醒，并提供一个日期和时间。')

  const after = text.slice(reminder.index + reminder[0].length)
  let title = after.replace(DATE, '').replace(DAY, '').replace(PART, '')
    .replace(CLOCK, '').replace(new RegExp(`${NUM}\\s*(?:分钟|小时)后`, 'g'), '')
    .replace(/^[\s，在于到]+|[\s，。！？!?]+$/g, '').trim()
  title = String(overrides.title ?? title).trim()
  if (!title || title.length > 200) return clarify('要提醒你做什么？请填写 1 到 200 字的提醒内容。', title)
  if (overrides.dueAt) {
    const at = parseShanghaiTime(overrides.dueAt)
    if (!Number.isFinite(at) || at <= now) return clarify('请填写晚于当前时间的有效上海日期和时间。', title)
    return { status: 'ready', behavior, title, dueAt: String(overrides.dueAt).slice(0, 16), timezone: REMINDER_TIMEZONE }
  }

  const exactDates = [...text.matchAll(new RegExp(DATE.source, 'g'))]
  const days = [...text.matchAll(new RegExp(DAY.source, 'g'))]
  const clocks = [...text.matchAll(CLOCK)]
  const offsets = [...text.matchAll(new RegExp(`(${NUM})\\s*(分钟|小时)后`, 'g'))]
  if (exactDates.length + days.length > 1 || clocks.length > 1 || offsets.length > 1
    || (offsets.length && (clocks.length || days.length || exactDates.length)))
    return clarify('发现多个时间，请选择唯一的提醒日期和时间。', title)
  if (offsets.length) {
    const offset = offsets[0]
    const count = chineseNumber(offset[1])
    if (!Number.isSafeInteger(count) || count <= 0 || count > 10080) return clarify('请提供有效的分钟或小时数。', title)
    const epoch = Math.ceil((now + count * (offset[2] === '小时' ? 3600_000 : 60_000)) / 60_000) * 60_000
    return { status: 'ready', behavior, title, dueAt: shanghaiWallTime(epoch), timezone: REMINDER_TIMEZONE }
  }
  if (/下周|周[一二三四五六日天]|下个月|月底|稍后|一会|左右|大约|差不多|或者|或是|还是|之前|以后|之后/.test(text))
    return clarify('这个时间还不确定，请填写一个准确的日期和时间。', title)
  if (!exactDates.length && !days.length) return clarify('是哪一天？请填写明确日期。', title)
  if (!clocks.length) return clarify('具体几点几分？请填写准确时间。', title)
  const clock = clocks[0]
  if ((/[:：]/.test(clock[0]) && !clock[3]) || /^\s*(?:[:：]|\d+\s*秒)/.test(text.slice(clock.index + clock[0].length)))
    return clarify('请填写完整的小时和分钟；当前不安排精确到秒的提醒。', title)
  let hour = chineseNumber(clock[1])
  const minute = clock[2] ? ({ 半: 30, 一刻: 15, 三刻: 45 })[clock[2]] : (clock[3] ? chineseNumber(clock[3]) : 0)
  const part = text.match(PART)?.[0] || (days[0]?.[0] === '今晚' ? '晚上' : /今早|今晨/.test(days[0]?.[0] || '') ? '上午' : '')
  const is24 = /[:：]/.test(clock[0]) || hour === 0 || hour > 12
  if (!part && !is24) return clarify('这个钟点是上午还是下午？请填写 24 小时制时间。', title)
  if (part) {
    if (hour < 1 || hour > 12) return clarify('时段与钟点冲突，请填写 24 小时制时间。', title)
    if (/下午|傍晚|晚上|夜里/.test(part)) {
      if (hour === 12) return clarify('十二点与这个时段有歧义，请填写包含日期的 24 小时制时间。', title)
      hour += 12
    }
    else if (part === '凌晨') hour = hour === 12 ? 0 : hour
    else if (part === '中午') {
      if (hour !== 11 && hour !== 12 && hour !== 1) return clarify('中午的时间不明确，请填写 24 小时制时间。', title)
      if (hour === 1) hour = 13
    }
    else if (hour === 12) return clarify('上午十二点有歧义，请填写 24 小时制时间。', title)
  }
  let day
  if (exactDates.length) {
    const [, y, m, d] = exactDates[0]
    day = `${y}-${m.padStart(2, '0')}-${d.padStart(2, '0')}`
  }
  else {
    const delta = days[0][0] === '明天' ? 1 : days[0][0] === '后天' ? 2 : 0
    day = shanghaiWallTime(now + delta * 86400_000).slice(0, 10)
  }
  const dueAt = `${day}T${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`
  if (!Number.isFinite(parseShanghaiTime(dueAt)) || parseShanghaiTime(dueAt) <= now)
    return clarify('该时间无效或已经过去，请填写未来的日期和时间。', title)
  return { status: 'ready', behavior, title, dueAt, timezone: REMINDER_TIMEZONE }
}
