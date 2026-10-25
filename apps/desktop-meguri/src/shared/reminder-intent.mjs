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

function nextShanghaiDate(date) {
  const midnight = parseShanghaiTime(`${date}T00:00`)
  return Number.isFinite(midnight) ? shanghaiWallTime(midnight + 86400_000).slice(0, 10) : ''
}

function inferredReminderClock(title, now) {
  if (/(?:睡觉|睡眠|上床|就寝)/.test(title))
    return { hour: 12, minute: 0, reason: 'sleep' }
  const wall = new Date(now + OFFSET)
  const minutes = wall.getUTCHours() * 60 + wall.getUTCMinutes()
  const rounded = Math.ceil((minutes + 1) / 30) * 30
  return { hour: Math.floor(rounded / 60) % 24, minute: rounded % 60, reason: 'next-half-hour' }
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
  const routingText = text.replace(/“[^”]*”|‘[^’]*’|「[^」]*」|『[^』]*』|"[^"]*"|'[^']*'/g, quoted => `${quoted[0]}${' '.repeat(Math.max(0, quoted.length - 2))}${quoted.at(-1) || ''}`)
  const reminderKeyword = /提醒/.exec(routingText)
  const temporal = /明天|后天|今晚|今夜|今早|明早|明晚|稍后|一会|待会|\d+\s*(?:分钟|小时)后|下周|周[一二三四五六日天]|下个月|月底|\d{1,2}\s*月\s*\d{1,2}(?:日|号)?|\d{4}[-年]\d{1,2}[-月]\d{1,2}/.test(routingText)
    || /(?:上午|下午|晚上|早上|傍晚)?\s*[零〇一二两三四五六七八九十\d]+\s*(?:点|时|[:：])/.test(routingText)
  const explicitClock = /(?:上午|下午|晚上|早上|傍晚)?\s*[零〇一二两三四五六七八九十\d]+\s*(?:点|时|[:：])/.test(routingText)
  const action = /自动|执行|发送|运行|检查订单|关机|重启|删除|下载|上传|发消息|发邮件|回电话|拨打|购买/.test(routingText)
    || (explicitClock && /打开|关闭/.test(routingText))
    || (/安排|计划|定时|预约/.test(routingText) && /打开|关闭/.test(routingText))
  const reminderManagement = /(?:取消|修改|更改|改到|推迟|不要|不用|别再?).*(?:提醒|定时)|(?:提醒).*(?:取消|改到)/.test(routingText)
  const explicitReminder = /提醒(?:我|我们|一下(?:我|我们)?)/.exec(routingText)
  const reminderTail = reminderKeyword ? routingText.slice(reminderKeyword.index + reminderKeyword[0].length).trim() : ''
  const taskAfterBareReminder = Boolean(reminderTail && !/^(?:功能|设置|列表|事项|用法|说明|怎么|如何|是什么|是什么意思|有哪些|为什么|卡片)/.test(reminderTail))
  const bareReminderCommand = Boolean(reminderKeyword && taskAfterBareReminder
    && (/^\s*(?:请|帮我|替我)?\s*提醒/.test(routingText) || temporal))
  const reminder = explicitReminder || (bareReminderCommand ? reminderKeyword : undefined)
  const reminderDiscussion = /提醒(?:功能|的功能|设置|我(?:是什么意思|是什么|怎么用|如何使用|有哪些|为什么))|(?:分析|解释|讨论|核对|查看|检查).{0,20}提醒.{0,12}(?:设置|功能|列表|用法|说明)|为什么(?:没有|没收到|未收到)?提醒我|我的提醒(?:有哪些|是什么|怎么用)?/.test(routingText)
  if (reminderDiscussion) return { status: 'none' }
  if (!reminder && !reminderManagement && !(temporal && action)) return { status: 'none' }
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

  const exactDates = [...routingText.matchAll(new RegExp(DATE.source, 'g'))]
  const days = [...routingText.matchAll(new RegExp(DAY.source, 'g'))]
  const clocks = [...routingText.matchAll(CLOCK)]
  const offsets = [...routingText.matchAll(new RegExp(`(${NUM})\\s*(分钟|小时)后`, 'g'))]
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
  if (!clocks.length) {
    const inferred = inferredReminderClock(title, now)
    const day = exactDates.length
      ? `${exactDates[0][1]}-${exactDates[0][2].padStart(2, '0')}-${exactDates[0][3].padStart(2, '0')}`
      : days.length
        ? shanghaiWallTime(now + (days[0][0] === '明天' ? 86400_000 : days[0][0] === '后天' ? 2 * 86400_000 : 0)).slice(0, 10)
        : shanghaiWallTime(now).slice(0, 10)
    const dueAt = `${day}T${String(inferred.hour).padStart(2, '0')}:${String(inferred.minute).padStart(2, '0')}`
    if (parseShanghaiTime(dueAt) <= now) {
      if (exactDates.length || days.length)
        return clarify('这个提醒缺少时间，而且默认时间已经过去，请补充具体时间。', title)
      return {
        status: 'ready',
        behavior,
        title,
        dueAt: `${nextShanghaiDate(day)}T${String(inferred.hour).padStart(2, '0')}:${String(inferred.minute).padStart(2, '0')}`,
        timezone: REMINDER_TIMEZONE,
        inferred: inferred.reason,
      }
    }
    return { status: 'ready', behavior, title, dueAt, timezone: REMINDER_TIMEZONE, inferred: inferred.reason }
  }
  const clock = clocks[0]
  if ((/[:：]/.test(clock[0]) && !clock[3]) || /^\s*(?:[:：]|\d+\s*秒)/.test(text.slice(clock.index + clock[0].length)))
    return clarify('请填写完整的小时和分钟；当前不安排精确到秒的提醒。', title)
  let hour = chineseNumber(clock[1])
  const minute = clock[2] ? ({ 半: 30, 一刻: 15, 三刻: 45 })[clock[2]] : (clock[3] ? chineseNumber(clock[3]) : 0)
  const part = routingText.match(PART)?.[0] || (days[0]?.[0] === '今晚' ? '晚上' : /今早|今晨/.test(days[0]?.[0] || '') ? '上午' : '')
  const is24 = /[:：]/.test(clock[0]) || hour === 0 || hour > 12
  let candidateHours
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
    candidateHours = [hour]
  }
  else if (is24) candidateHours = [hour]
  else candidateHours = [hour === 12 ? 0 : hour, hour === 12 ? 12 : hour + 12]
  let day
  if (exactDates.length) {
    const [, y, m, d] = exactDates[0]
    day = `${y}-${m.padStart(2, '0')}-${d.padStart(2, '0')}`
  }
  else if (days.length) {
    const delta = days[0][0] === '明天' ? 1 : days[0][0] === '后天' ? 2 : 0
    day = shanghaiWallTime(now + delta * 86400_000).slice(0, 10)
  }
  else day = shanghaiWallTime(now).slice(0, 10)

  const hasExplicitDay = exactDates.length > 0 || days.length > 0
  const candidateDays = hasExplicitDay ? [day] : [day, nextShanghaiDate(day)].filter(Boolean)
  const futureCandidates = candidateDays.flatMap(candidateDay => candidateHours.map(candidateHour => {
    const dueAt = `${candidateDay}T${String(candidateHour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`
    return { dueAt, at: parseShanghaiTime(dueAt) }
  })).filter(candidate => Number.isFinite(candidate.at) && candidate.at > now)
    .sort((left, right) => left.at - right.at)
  const selected = futureCandidates[0]
  if (!selected)
    return clarify('该时间无效或已经过去，请填写未来的日期和时间。', title)
  return { status: 'ready', behavior, title, dueAt: selected.dueAt, timezone: REMINDER_TIMEZONE }
}
