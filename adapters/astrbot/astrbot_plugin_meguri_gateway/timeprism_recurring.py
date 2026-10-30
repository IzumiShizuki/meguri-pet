"""Bounded recurring reminder grammar; never executes a write."""
from __future__ import annotations

import re
from datetime import datetime, timezone
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

_BOUND = r"\d{4}-\d{2}-\d{2}(?:T\d{2}:\d{2}(?::\d{2})?)?"
_RANGE = re.compile(rf"从\s*({_BOUND})\s*(?:到|至)\s*({_BOUND})")
_ZONE = re.compile(r"(?<![A-Za-z0-9_+\-/])(?:时区\s*)?([A-Za-z][A-Za-z0-9_+\-]*/[A-Za-z0-9_+\-]+(?:/[A-Za-z0-9_+\-]+)?)(?![A-Za-z0-9_+\-/])")
_WEEK = re.compile(r"(?:每周|每星期)([一二三四五六日天](?:(?:\s*[、,，和及至到-]\s*)(?:周|星期)?[一二三四五六日天])*)")
_CADENCE = re.compile(r"每天|每日|工作日")
_NUM = r"[零〇一二两三四五六七八九十\d]+"
_ADVANCE = re.compile(rf"提前\s*({_NUM})\s*(分钟|小时|天)")
_CLOCK = re.compile(rf"(凌晨|早上|早晨|上午|中午|下午|傍晚|晚上|夜里)?\s*({_NUM})\s*(点|时|[:：])\s*(?:(半|一刻|三刻)|({_NUM})\s*分?)?")


def _boundary(raw: str, zone: ZoneInfo, *, end: bool = False) -> str:
    value = datetime.fromisoformat(raw + ('T23:59:59' if end else 'T00:00:00') if len(raw) == 10 else raw)
    if value.tzinfo is not None:
        raise ValueError('invalid boundary')
    # ZoneInfo normalizes nonexistent local times; reject them via a round trip.
    if value.replace(tzinfo=zone).astimezone(timezone.utc).astimezone(zone).replace(tzinfo=None) != value:
        raise ValueError('nonexistent wall time')
    return value.isoformat(timespec='seconds')


def parse_recurring_reminder(value: str, now: datetime | None, number) -> dict | None:
    if not re.search(r"每天|每日|每周|每星期|每月|每隔|工作日|重复", value):
        return None
    def clarify(message='请提供明确的星期、24小时制时间和提醒内容；可加提前量、IANA时区及从日期到日期。'):
        return {'status':'clarify','message':message}
    if re.search(r"每月|每隔|重复|取消|修改|不要|别再|或者|左右|大约", value):
        return clarify('目前支持每天或每周指定星期的提醒；请明确周期，修改/取消已有草稿请用 todo 命令。')
    work = value
    zones = list(_ZONE.finditer(work))
    if len(zones) > 1: return clarify()
    zone_name = zones[0].group(1) if zones else 'Asia/Shanghai'
    try: zone = ZoneInfo(zone_name)
    except (ValueError, ZoneInfoNotFoundError): return clarify('时区无效，请提供有效IANA时区。')
    work = _ZONE.sub(' ', work)
    if re.search(r'时区|纽约|东京|伦敦|UTC|GMT|夏令|冬令', work, re.I): return clarify('请用明确IANA时区，例如 America/New_York。')
    control = work.split('提醒', 1)[0]
    if re.search(r'美东|美西|当地|本地|[\u4e00-\u9fff]{1,6}时间', control):
        return clarify('请用明确IANA时区，例如 America/New_York。')
    current = now or datetime.now(zone)
    current = current.replace(tzinfo=zone) if current.tzinfo is None else current.astimezone(zone)
    ranges = list(_RANGE.finditer(work))
    if len(ranges) > 1: return clarify()
    try:
        start = _boundary(ranges[0].group(1), zone) if ranges else current.replace(hour=0, minute=0, second=0, microsecond=0, tzinfo=None).isoformat(timespec='seconds')
        end = _boundary(ranges[0].group(2), zone, end=True) if ranges else ''
        if end and end < start: return clarify('结束时间不能早于开始时间。')
    except ValueError: return clarify('日期范围无效或落在时区不存在的时间中。')
    work = _RANGE.sub(' ', work)
    if re.search(r'明天|后天|今天|本周|这周|下周|本月|下月|本年|今年|明年|仅限|截至|截止|为止', work.split('提醒', 1)[0]):
        return clarify('周期范围请用明确的从YYYY-MM-DD到YYYY-MM-DD，避免误建长期提醒。')
    if re.search(r'\d{4}-\d|从|截止|截至', work): return clarify('请使用“从YYYY-MM-DD到YYYY-MM-DD”的完整日期范围。')
    advances = list(_ADVANCE.finditer(work))
    if len(advances) > 1: return clarify()
    amount, unit = 0, 'MINUTE'
    if advances:
        amount = number(advances[0].group(1)); raw_unit = advances[0].group(2)
        if amount is None or amount < 0 or (raw_unit == '天' and amount == 0): return clarify('提前量需要非负分钟或正整数天。')
        if raw_unit == '小时': amount *= 60
        unit = 'DAY' if raw_unit == '天' else 'MINUTE'
        if amount > (365 if unit == 'DAY' else 43200): return clarify('提前量超出站点支持范围（最多30天的分钟数或365天）。')
    work = _ADVANCE.sub(' ', work)
    if '提前' in work: return clarify('提前量需要非负分钟或正整数天。')
    weeks, cadences = list(_WEEK.finditer(work)), list(_CADENCE.finditer(work))
    if len(weeks) + len(cadences) != 1: return clarify()
    if weeks:
        days_text = re.sub(r'周|星期|\s', '', weeks[0].group(1))
        to_day = lambda x: 0 if x in '日天' else ' 一二三四五六'.index(x)
        days = []
        for part in re.split(r'[、,，和及]', days_text):
            bounds = re.split(r'[至到-]', part)
            if len(bounds) == 1: days.append(to_day(bounds[0]))
            elif len(bounds) == 2:
                first, last = (to_day(x) or 7 for x in bounds)
                if last < first: return clarify('跨周范围请逐项列出星期。')
                days.extend(x % 7 for x in range(first, last + 1))
            else: return clarify()
        work = _WEEK.sub(' ', work)
    else:
        days = [1,2,3,4,5] if cadences[0].group() == '工作日' else list(range(7))
        work = _CADENCE.sub(' ', work)
    if re.search(r'每周|每星期|周[一二三四五六日天]', work): return clarify()
    clocks = list(_CLOCK.finditer(work))
    if len(clocks) != 1: return clarify()
    clock = clocks[0]; part, hour_text, separator, special, minute_text = clock.groups()
    hour = number(hour_text); minute = {'半':30,'一刻':15,'三刻':45}.get(special, number(minute_text) if minute_text else 0)
    if hour is None or minute is None or not 0 <= hour <= 23 or not 0 <= minute <= 59: return clarify()
    if separator in ':：' and not minute_text: return clarify()
    if part:
        if not 1 <= hour <= 12: return clarify()
        if part in {'下午','傍晚','晚上','夜里'}:
            if hour == 12: return clarify()
            hour += 12
        elif part == '凌晨': hour %= 12
        elif part == '中午':
            if hour not in {1,11,12}: return clarify()
            if hour == 1: hour = 13
        elif hour == 12: return clarify()
    elif separator not in ':：' and 1 <= hour <= 12:
        return clarify('周期提醒的上午/下午不明确，请用24小时制，例如08:00或20:00。')
    if re.match(r'\s*(?:秒|[:：]\s*\d)', work[clock.end():]):
        return clarify('周期提醒目前精确到分钟，请不要提供秒数。')
    work = _CLOCK.sub(' ', work)
    title = re.sub(r'提醒(?:我|一下|我们)?', ' ', work)
    title = re.sub(r'^[\s，在于到请]+|[\s，。！？!?]+$', '', title)
    title = re.sub(r'\s+', ' ', title).strip()
    if not title or len(title) > 200 or any(ord(c) < 32 for c in title): return clarify('请填写1到200字的提醒内容。')
    schedule = {'title':title,'weekdays':sorted(set(days)),'time':f'{hour:02}:{minute:02}','timeZoneId':zone_name,
                'startAt':start,'endAt':end,'deadlineRemindValue':amount,'deadlineRemindUnit':unit}
    return {'status':'ready','kind':'recurring','title':title,'schedule':schedule}
