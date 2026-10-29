// Site recurring rules use six-field Spring Cron and wall-clock dates in timeZoneId.
const dayNames = ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT']
export function recurringStartOfDay(epoch, zone) {
  const parts = Object.fromEntries(new Intl.DateTimeFormat('en', {
    timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(new Date(epoch)).map(part => [part.type, part.value]))
  return `${parts.year}-${parts.month}-${parts.day}T00:00:00`
}

export function weeklyPreset(expression) {
  const fields = String(expression || '').trim().toUpperCase().split(/\s+/)
  if (fields.length !== 6 || fields[0] !== '0' || !/^\d+$/.test(fields[1]) || !/^\d+$/.test(fields[2])
    || Number(fields[1]) > 59 || Number(fields[2]) > 23 || fields[3] !== '*' || fields[4] !== '*') return null
  const days = []
  const number = value => /^[0-7]$/.test(value) ? Number(value) : dayNames.indexOf(value)
  for (const segment of (fields[5] === '*' ? '0-6' : fields[5]).split(',')) {
    const range = segment.split('-')
    if (range.length > 2) return null
    const first = number(range[0]); const last = range.length === 2 ? number(range[1]) : first
    if (first < 0 || last < first) return null
    for (let day = first; day <= last; day++) days.push(day % 7)
  }
  return { weekdays: [...new Set(days)].sort((a, b) => a - b), time: `${fields[2].padStart(2, '0')}:${fields[1].padStart(2, '0')}` }
}

export function weeklyCron(weekdays, time) {
  if (!Array.isArray(weekdays) || !weekdays.length || weekdays.length > 7 || weekdays.some(day => !Number.isInteger(day) || day < 0 || day > 6))
    throw new Error('请至少选择一个有效星期。')
  if (!/^([01]\d|2[0-3]):[0-5]\d$/.test(String(time))) throw new Error('请设置有效的截止时间。')
  const [hour, minute] = time.split(':').map(Number)
  return `0 ${minute} ${hour} * * ${[...new Set(weekdays)].sort((a, b) => a - b).join(',')}`
}

export function recurringTime(value, zone = 'Asia/Shanghai') {
  const raw = String(value || '')
  if (/[zZ]$|[+-]\d{2}:\d{2}$/.test(raw)) return Date.parse(raw)
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?$/.exec(raw)
  if (!match) return NaN
  const [, y, m, d, h, min, s = '0'] = match
  const wall = Date.UTC(+y, +m - 1, +d, +h, +min, +s)
  if (new Date(wall).toISOString().slice(0, 19) !== `${y}-${m}-${d}T${h}:${min}:${s.padStart(2, '0')}`) return NaN
  try {
    const formatter = new Intl.DateTimeFormat('en-GB', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' })
    const local = instant => {
      const p = Object.fromEntries(formatter.formatToParts(new Date(instant)).map(part => [part.type, part.value]))
      return Date.UTC(+p.year, +p.month - 1, +p.day, +p.hour, +p.minute, +p.second)
    }
    const candidates = [-86400000, 0, 86400000].map(delta => wall - (local(wall + delta) - wall - delta))
      .filter(instant => local(instant) === wall)
    return candidates.length ? Math.min(...candidates) : NaN
  } catch { return NaN }
}

export function recurringDescription(rule) {
  const preset = weeklyPreset(rule.cronExpr)
  const cadence = preset
    ? `${preset.weekdays.join(',') === '1,2,3,4,5' ? '周一至周五' : `每周${preset.weekdays.map(day => '日一二三四五六'[day]).join('、')}`} ${preset.time}`
    : `自定义 ${rule.cronExpr}`
  return `${cadence} · ${rule.timeZoneId}${rule.enabled === false ? ' · 已暂停' : ''}`
}
