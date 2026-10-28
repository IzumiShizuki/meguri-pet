"""Owner-only TimePrism reminder drafts for AstrBot private chat."""

from __future__ import annotations

import json
import math
import os
import re
import tempfile
import threading
import time
import uuid
from datetime import date, datetime, time as wall_time, timedelta
from pathlib import Path
from typing import Any
from zoneinfo import ZoneInfo

from .client import CoreProtocolError, CoreUnavailableError
from .models import PlatformMessage


TIMEZONE = ZoneInfo("Asia/Shanghai")
TIMEZONE_LABEL = "Asia/Shanghai（UTC+08:00）"
_NUM = r"[零〇一二两三四五六七八九十\d]+"
_DATE = re.compile(
    r"(?P<year>\d{4})\s*(?:-|年)\s*(?P<month>\d{1,2})\s*(?:-|月)\s*"
    r"(?P<day>\d{1,2})\s*(?:日|号)?"
)
_DAY = re.compile(r"明天|后天|今晨|今早|今晚|明早|明晚|今天")
_PART = re.compile(r"凌晨|早上|早晨|上午|中午|下午|傍晚|晚上|夜里")
_CLOCK = re.compile(
    rf"(?P<hour>{_NUM})\s*(?P<separator>点|时|[:：])\s*"
    rf"(?:(?P<special>半|一刻|三刻)|(?P<minute>{_NUM})\s*(?:分)?)?"
)
_OFFSET = re.compile(rf"(?P<count>{_NUM})\s*(?P<unit>分钟|小时)后")
_TODO_COMMAND = re.compile(r"^/meguri\s+todo(?:\s+|$)", re.IGNORECASE)
_DRAFT_TTL_SECONDS = 24 * 60 * 60
_MAX_DRAFTS = 250
_SUBMIT_STALE_SECONDS = 60


def is_timeprism_todo_command(text: str) -> bool:
    return bool(_TODO_COMMAND.match(str(text or "").strip()))


def _chinese_number(value: str) -> int | None:
    text = str(value).replace("两", "二").replace("〇", "零")
    if text.isdigit():
        return int(text)
    if text == "十":
        return 10
    digits = "零一二三四五六七八九"
    if len(text) == 1:
        return digits.find(text) if text in digits else None
    if text.count("十") != 1:
        return None
    tens, ones = text.split("十")
    if (tens and (len(tens) != 1 or tens not in digits[1:])) or (
        ones and (len(ones) != 1 or ones not in digits)
    ):
        return None
    return (digits.index(tens) if tens else 1) * 10 + (
        digits.index(ones) if ones else 0
    )


def _parse_result(status: str, message: str = "", **values: Any) -> dict[str, Any]:
    return {"status": status, "message": message, **values}


def parse_timeprism_reminder(
    text: str, now: datetime | None = None
) -> dict[str, Any]:
    """Parse one bounded reminder request; unsupported timing never becomes a write."""

    value = str(text or "").strip()
    if not value or "提醒" not in value:
        return _parse_result("none")
    if len(value) > 1000 or value.count("提醒") > 1:
        return _parse_result("clarify", "请一次只安排一项提醒，并提供一个日期和时间。")
    if re.search(r"每天|每日|每周|每月|每隔|工作日|重复", value):
        return _parse_result("unsupported", "当前只支持一次提醒，无法安排重复定时任务。")
    if re.search(r"纽约|东京|伦敦|美国|日本|美东|美西|夏令|冬令|UTC|GMT|时区|[A-Za-z_]+/[A-Za-z_]+", value.replace("Asia/Shanghai", ""), re.I):
        return _parse_result("clarify", f"请使用 {TIMEZONE_LABEL} 并提供明确日期和时间。")
    if re.search(r"取消|修改|更改|改到|推迟|不要|别再", value):
        return _parse_result("unsupported", "请先打开待办草稿，再使用 /meguri todo edit 或 cancel 修改。")
    if re.search(r"下周|周[一二三四五六日天]|下个月|月底|稍后|一会|待会|左右|大约|差不多|或者|还是|之前|以后|之后", value):
        return _parse_result("clarify", "这个时间还不确定，请提供一个准确的日期和时间。")
    if re.search(r"(?:并|然后|同时).*(?:关机|发送|发邮件|发消息|运行|执行|购买|删除|重启)", value):
        return _parse_result("unsupported", "当前只支持提醒，不会安排其他操作；请单独描述提醒内容。")

    title = value
    for pattern in (_DATE, _DAY, _PART, _CLOCK, _OFFSET):
        title = pattern.sub(" ", title)
    title = re.sub(r"提醒(?:我|一下|我们)?", " ", title)
    title = re.sub(r"^[\s，在于到请]+|[\s，。！？!?]+$", "", title).strip()
    title = re.sub(r"\s+", " ", title)
    if not title or len(title) > 200:
        return _parse_result("clarify", "要提醒你做什么？请填写 1 到 200 字的提醒内容。", title=title)
    if any(ord(char) < 32 for char in title):
        return _parse_result("clarify", "提醒内容包含不可用字符，请重新描述。")

    exact_dates = list(_DATE.finditer(value))
    day_matches = list(_DAY.finditer(value))
    clocks = list(_CLOCK.finditer(value))
    offsets = list(_OFFSET.finditer(value))
    if len(exact_dates) + len(day_matches) > 1 or len(clocks) > 1 or len(offsets) > 1:
        return _parse_result("clarify", "发现多个时间，请选择唯一的提醒日期和时间。", title=title)
    if offsets:
        if clocks or exact_dates or day_matches:
            return _parse_result("clarify", "相对时间和指定日期/钟点不能同时使用，请选一个。", title=title)
        count = _chinese_number(offsets[0].group("count"))
        unit = offsets[0].group("unit")
        maximum = 10080 if unit == "分钟" else 168
        if count is None or count <= 0 or count > maximum:
            return _parse_result("clarify", "请提供有效的分钟或小时数。", title=title)
        current = _now_in_shanghai(now)
        due = math.ceil((current.timestamp() + count * (60 if unit == "分钟" else 3600)) / 60) * 60
        return _parse_result(
            "ready", title=title,
            due_at=datetime.fromtimestamp(due, TIMEZONE).strftime("%Y-%m-%dT%H:%M"),
        )
    if not clocks:
        return _parse_result("clarify", "具体几点几分？请填写准确时间。", title=title)

    clock = clocks[0]
    hour = _chinese_number(clock.group("hour"))
    minute_part = clock.group("minute")
    special = clock.group("special")
    if clock.group("separator") in {":", "："} and not minute_part:
        return _parse_result("clarify", "请填写完整的小时和分钟。", title=title)
    minute = {"半": 30, "一刻": 15, "三刻": 45}.get(special)
    if minute is None:
        minute = _chinese_number(minute_part) if minute_part else 0
    if hour is None or minute is None or minute > 59 or hour > 23:
        return _parse_result("clarify", "请填写有效的小时和分钟。", title=title)
    if value[clock.end():].lstrip().startswith("秒"):
        return _parse_result("clarify", "当前提醒精确到分钟，请不要填写秒。", title=title)

    current = _now_in_shanghai(now)
    part_match = _PART.search(value)
    part = part_match.group(0) if part_match else ""
    day_word = day_matches[0].group(0) if day_matches else ""
    implied_part = {
        "今晚": "晚上", "明晚": "晚上", "今早": "上午", "今晨": "上午", "明早": "上午"
    }.get(day_word, "")
    if part and implied_part and part != implied_part:
        return _parse_result("clarify", "日期时段与钟点描述冲突，请填写 24 小时制时间。", title=title)
    part = part or implied_part
    colon_time = clock.group("separator") in {":", "："}
    if part:
        if not 1 <= hour <= 12:
            return _parse_result("clarify", "时段与钟点冲突，请填写 24 小时制时间。", title=title)
        if part in {"下午", "傍晚", "晚上", "夜里"}:
            if hour == 12:
                return _parse_result("clarify", "十二点与这个时段有歧义，请填写 24 小时制时间。", title=title)
            candidate_hours = [hour + 12]
        elif part == "凌晨":
            candidate_hours = [0 if hour == 12 else hour]
        elif part == "中午":
            if hour not in {1, 11, 12}:
                return _parse_result("clarify", "中午的时间不明确，请填写 24 小时制时间。", title=title)
            candidate_hours = [13 if hour == 1 else hour]
        else:
            if hour == 12:
                return _parse_result("clarify", "上午十二点有歧义，请填写 24 小时制时间。", title=title)
            candidate_hours = [hour]
    elif colon_time or hour == 0 or hour > 12:
        candidate_hours = [hour]
    else:
        candidate_hours = [hour % 12, hour % 12 + 12]

    if exact_dates:
        date_match = exact_dates[0]
        try:
            target_day = date(
                int(date_match.group("year")),
                int(date_match.group("month")),
                int(date_match.group("day")),
            )
        except ValueError:
            return _parse_result("clarify", "日期无效，请填写有效的未来日期。", title=title)
        target_days = [target_day]
    elif day_word:
        delta = 2 if day_word == "后天" else 1 if day_word in {"明天", "明早", "明晚"} else 0
        target_days = [current.date() + timedelta(days=delta)]
    else:
        target_days = [current.date(), current.date() + timedelta(days=1)]

    candidates: list[datetime] = []
    for target_day in target_days:
        for candidate_hour in candidate_hours:
            try:
                candidate = datetime.combine(target_day, wall_time(candidate_hour, minute), TIMEZONE)
            except ValueError:
                continue
            if candidate > current:
                candidates.append(candidate)
    if not candidates:
        message = (
            "该日期上的时间已经过去，请修改日期或时间。"
            if exact_dates or day_word
            else "该时间无效或已过去，请重新填写。"
        )
        return _parse_result("clarify", message, title=title)
    selected = min(candidates)
    return _parse_result("ready", title=title, due_at=selected.strftime("%Y-%m-%dT%H:%M"))


def _now_in_shanghai(now: datetime | None) -> datetime:
    if now is None:
        return datetime.now(TIMEZONE)
    if now.tzinfo is None:
        return now.replace(tzinfo=TIMEZONE)
    return now.astimezone(TIMEZONE)


class TodoDraftStoreError(RuntimeError):
    """A draft cannot be safely read or persisted."""


class TimePrismTodoDraftStore:
    """Small atomic JSON store; raw platform sender IDs are never persisted."""

    def __init__(self, file_path: Path | str) -> None:
        self.file_path = Path(file_path).expanduser().resolve()
        self._lock = threading.RLock()

    def create(self, actor_id: str, title: str, due_at: str) -> dict[str, Any]:
        with self._lock:
            now = time.time()
            drafts = self._load()
            self._prune(drafts, now)
            if len(drafts) >= _MAX_DRAFTS:
                raise TodoDraftStoreError("待确认草稿较多，请先处理已有草稿。")
            draft_id = uuid.uuid4().hex[:8]
            while draft_id in drafts:
                draft_id = uuid.uuid4().hex[:8]
            draft = {
                "draft_id": draft_id,
                "request_id": str(uuid.uuid4()),
                "actor_id": actor_id,
                "title": title,
                "due_at": due_at,
                "state": "draft",
                "created_at": now,
                "expires_at": now + _DRAFT_TTL_SECONDS,
            }
            drafts[draft_id] = draft
            self._save(drafts)
            return dict(draft)

    def get(self, actor_id: str, draft_id: str) -> dict[str, Any] | None:
        with self._lock:
            drafts = self._load()
            self._prune(drafts, time.time())
            draft = drafts.get(str(draft_id).lower())
            if draft is None or draft.get("actor_id") != actor_id:
                self._save(drafts)
                return None
            self._save(drafts)
            return dict(draft)

    def edit(self, actor_id: str, draft_id: str, title: str, due_at: str) -> dict[str, Any] | None:
        with self._lock:
            drafts = self._load()
            self._prune(drafts, time.time())
            draft = drafts.get(str(draft_id).lower())
            if not self._owned_active(draft, actor_id) or draft.get("state") not in {"draft", "rejected"}:
                self._save(drafts)
                return None
            draft.update({"request_id": str(uuid.uuid4()), "title": title, "due_at": due_at, "state": "draft"})
            self._save(drafts)
            return dict(draft)

    def cancel(self, actor_id: str, draft_id: str) -> bool:
        with self._lock:
            drafts = self._load()
            self._prune(drafts, time.time())
            draft = drafts.get(str(draft_id).lower())
            if not self._owned_active(draft, actor_id) or draft.get("state") not in {"draft", "rejected"}:
                self._save(drafts)
                return False
            draft["state"] = "cancelled"
            self._save(drafts)
            return True

    def begin_submit(self, actor_id: str, draft_id: str) -> tuple[str, dict[str, Any] | None]:
        with self._lock:
            drafts = self._load()
            now = time.time()
            self._prune(drafts, now)
            draft = drafts.get(str(draft_id).lower())
            if (
                draft is None
                or draft.get("actor_id") != actor_id
                or (
                    float(draft.get("expires_at", 0)) <= now
                    and draft.get("state") not in {"submitting", "uncertain"}
                )
            ):
                self._save(drafts)
                return "missing", None
            state = str(draft.get("state", ""))
            if state == "confirmed":
                self._save(drafts)
                return "confirmed", dict(draft)
            if state == "submitting":
                if now - float(draft.get("updated_at", 0)) <= _SUBMIT_STALE_SECONDS:
                    self._save(drafts)
                    return "submitting", dict(draft)
                state = "uncertain"
            if state not in {"draft", "rejected", "uncertain"}:
                self._save(drafts)
                return state, dict(draft)
            draft["state"] = "submitting"
            draft["updated_at"] = now
            self._save(drafts)
            return "ready", dict(draft)

    def finish(self, draft_id: str, state: str, todo_id: int | None = None) -> None:
        with self._lock:
            drafts = self._load()
            draft = drafts.get(str(draft_id).lower())
            if draft is None:
                return
            draft["state"] = state
            if todo_id is not None:
                draft["todo_id"] = int(todo_id)
            draft["updated_at"] = time.time()
            self._save(drafts)

    @staticmethod
    def _owned_active(draft: dict[str, Any] | None, actor_id: str) -> bool:
        return bool(
            draft
            and draft.get("actor_id") == actor_id
            and float(draft.get("expires_at", 0)) > time.time()
        )

    @staticmethod
    def _prune(drafts: dict[str, dict[str, Any]], now: float) -> None:
        for key, draft in list(drafts.items()):
            if float(draft.get("expires_at", 0)) <= now and draft.get("state") not in {"submitting", "uncertain"}:
                drafts.pop(key, None)

    def _load(self) -> dict[str, dict[str, Any]]:
        if not self.file_path.exists():
            return {}
        try:
            value = json.loads(self.file_path.read_text(encoding="utf-8"))
            drafts = value.get("drafts") if isinstance(value, dict) and value.get("version") == 1 else None
            if not isinstance(drafts, dict) or any(not isinstance(item, dict) for item in drafts.values()):
                raise ValueError("invalid draft store")
            return {str(key): dict(item) for key, item in drafts.items()}
        except (OSError, ValueError, TypeError) as error:
            raise TodoDraftStoreError("待办草稿存储不可用；没有提交请求。") from error

    def _save(self, drafts: dict[str, dict[str, Any]]) -> None:
        temporary: str | None = None
        try:
            self.file_path.parent.mkdir(parents=True, exist_ok=True)
            with tempfile.NamedTemporaryFile(
                mode="w", encoding="utf-8", dir=self.file_path.parent,
                prefix=f"{self.file_path.name}.", suffix=".tmp", delete=False,
            ) as stream:
                temporary = stream.name
                os.chmod(temporary, 0o600)
                json.dump({"version": 1, "drafts": drafts}, stream, ensure_ascii=False, separators=(",", ":"))
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, self.file_path)
        except OSError as error:
            raise TodoDraftStoreError("无法保存待办草稿；没有提交请求。") from error
        finally:
            if temporary:
                try:
                    Path(temporary).unlink(missing_ok=True)
                except OSError:
                    pass


class TimePrismTodoCreation:
    def __init__(
        self,
        core: Any,
        *,
        owner_user_id: int,
        owner_senders: set[str],
        store: TimePrismTodoDraftStore,
    ) -> None:
        if owner_user_id <= 0 or not owner_senders:
            raise ValueError("TimePrism Todo creation requires an owner ID and private sender allowlist")
        self.core = core
        self.owner_user_id = owner_user_id
        self.owner_senders = frozenset(owner_senders)
        self.store = store

    async def handle(self, message: PlatformMessage, actor_id: str) -> str | None:
        is_command = is_timeprism_todo_command(message.text)
        if message.chat_type != "private":
            return "待办写入只允许在配置的私聊中使用。" if is_command else None
        if message.sender_key not in self.owner_senders:
            return "当前私聊账号未获准创建 TimePrism 待办。" if is_command else None
        if is_command:
            return await self._command(message.text, actor_id)

        parsed = parse_timeprism_reminder(message.text)
        if parsed["status"] == "none":
            return None
        if parsed["status"] != "ready":
            return str(parsed.get("message") or "请补充提醒时间和内容。")
        try:
            draft = self.store.create(actor_id, parsed["title"], parsed["due_at"])
        except TodoDraftStoreError as error:
            return str(error)
        return self._preview(draft)

    async def _command(self, text: str, actor_id: str) -> str:
        match = _TODO_COMMAND.match(str(text or "").strip())
        tail = text[match.end():].strip() if match else ""
        parts = tail.split(maxsplit=2)
        if not parts or parts[0].casefold() in {"help", "帮助"}:
            return self._help()
        action = parts[0].casefold()
        if action == "confirm" and len(parts) == 2:
            return await self._confirm(actor_id, parts[1].lower())
        if action == "cancel" and len(parts) == 2:
            try:
                cancelled = self.store.cancel(actor_id, parts[1])
            except TodoDraftStoreError as error:
                return str(error)
            return "待办草稿已取消，没有写入 TimePrism。" if cancelled else "找不到可取消的草稿；草稿可能已过期或正在核对写入结果。"
        if action == "edit" and len(parts) == 3:
            parsed = parse_timeprism_reminder(parts[2])
            if parsed["status"] != "ready":
                return str(parsed.get("message") or "请在修改内容中写完整的提醒，例如：提醒我1点睡觉。")
            try:
                draft = self.store.edit(actor_id, parts[1], parsed["title"], parsed["due_at"])
            except TodoDraftStoreError as error:
                return str(error)
            return self._preview(draft) if draft else "找不到可修改的草稿；草稿可能已过期或正在核对写入结果。"
        return self._help()

    async def _confirm(self, actor_id: str, draft_id: str) -> str:
        try:
            state, draft = self.store.begin_submit(actor_id, draft_id)
        except TodoDraftStoreError as error:
            return str(error)
        if state == "missing" or draft is None:
            return "找不到这个草稿；它可能已过期，或属于另一个私聊账号。"
        if state == "confirmed":
            return f"这个草稿已创建为 TimePrism 待办 #{draft.get('todo_id', '未知')}，不会重复创建。"
        if state == "submitting":
            return "这个草稿正在提交，请稍后查看结果。"
        if state != "ready":
            return "这个草稿当前不能确认；请重新创建一个提醒草稿。"

        try:
            result = await self.core.create_timeprism_todo(
                owner_user_id=self.owner_user_id,
                request_id=str(draft["request_id"]),
                title=str(draft["title"]),
                due_at=str(draft["due_at"]),
            )
        except CoreProtocolError as error:
            if error.status_code == 409:
                self.store.finish(draft_id, "uncertain")
                return "已有相同内容的写入仍待核对；请先用原草稿确认结果，当前草稿不会重复提交。"
            if error.status_code is not None and 400 <= error.status_code < 500:
                self.store.finish(draft_id, "rejected")
                return f"Core 拒绝了本次写入（HTTP {error.status_code}）；草稿仍可修改或稍后重试。"
            self.store.finish(draft_id, "uncertain")
            return "写入结果暂时无法确认，已保留原请求编号；再次确认只会先核对，不会盲目重复创建。"
        except CoreUnavailableError:
            self.store.finish(draft_id, "uncertain")
            return "Core 暂时不可用，写入结果可能尚未返回；已保留原请求编号，请稍后用同一草稿再次确认。"
        except Exception:
            self.store.finish(draft_id, "uncertain")
            return "Core 暂时不可用，写入结果可能尚未返回；已保留原请求编号，请稍后用同一草稿再次确认。"

        if result.get("state") == "confirmed":
            todo_id = result.get("todoId")
            self.store.finish(draft_id, "confirmed", int(todo_id) if todo_id else None)
            return (
                f"已创建 TimePrism 待办 #{todo_id}：{draft['title']}，"
                f"{draft['due_at'].replace('T', ' ')}（{TIMEZONE_LABEL}）。"
            )
        self.store.finish(draft_id, "uncertain")
        return "TimePrism 尚未确认写入结果，已保留原请求编号；请稍后用同一草稿再次确认核对。"

    @staticmethod
    def _preview(draft: dict[str, Any] | None) -> str:
        if draft is None:
            return "找不到可修改的草稿。"
        draft_id = draft["draft_id"]
        return (
            "TimePrism 待办草稿（尚未写入）\n"
            f"标题：{draft['title']}\n"
            f"时间：{str(draft['due_at']).replace('T', ' ')}（{TIMEZONE_LABEL}）\n"
            f"确认：/meguri todo confirm {draft_id}\n"
            f"修改：/meguri todo edit {draft_id} 提醒我1点睡觉\n"
            f"取消：/meguri todo cancel {draft_id}"
        )

    @staticmethod
    def _help() -> str:
        return (
            "请先直接描述提醒，例如“提醒我1点睡觉”；我会先给你预览。\n"
            "确认：/meguri todo confirm <草稿编号>\n"
            "修改：/meguri todo edit <草稿编号> <新的提醒描述>\n"
            "取消：/meguri todo cancel <草稿编号>"
        )
