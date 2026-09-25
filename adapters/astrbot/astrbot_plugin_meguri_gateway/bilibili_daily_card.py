from __future__ import annotations

import math
import os
import re
from pathlib import Path
from typing import Any

from PIL import Image, ImageDraw, ImageFont


ASSET_ROOT = Path(__file__).resolve().parent / "assets"
DEFAULT_TEMPLATE = ASSET_ROOT / "bilibili_daily_template.png"
DEFAULT_FONT = ASSET_ROOT / "NotoSansSC-Regular.otf"
DATE_PATTERN = re.compile(r"^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
TEXT_COLOR = "#8F3C38"
SECONDARY_TEXT_COLOR = "#B35F5A"
FOOTER_BACKGROUND = "#FFF5E6"


class BilibiliDailyCardError(ValueError):
    pass


def _text(value: object, maximum: int) -> str:
    return " ".join(str(value or "").replace("\x00", "").split())[:maximum]


def _number(value: object, *, minimum: float = 0, maximum: float = 1_000_000) -> float | None:
    if value is None or isinstance(value, bool):
        return None
    try:
        parsed = float(value)
    except (TypeError, ValueError, OverflowError):
        return None
    if not math.isfinite(parsed) or parsed < minimum or parsed > maximum:
        return None
    return parsed


def validate_payload(payload: object) -> dict[str, Any]:
    if not isinstance(payload, dict):
        raise BilibiliDailyCardError("render payload must be an object")
    if payload.get("schema_version") != 1 or payload.get("template") != "bilibili_daily_v1":
        raise BilibiliDailyCardError("unsupported Bilibili daily card contract")
    report_date = _text(payload.get("date"), 10)
    if report_date and not DATE_PATTERN.fullmatch(report_date):
        raise BilibiliDailyCardError("render payload date is invalid")
    if not isinstance(payload.get("statistics"), dict):
        raise BilibiliDailyCardError("render payload statistics are missing")
    for key in ("top_commentary", "interest_tags"):
        if not isinstance(payload.get(key, []), list):
            raise BilibiliDailyCardError(f"render payload {key} must be a list")
    return payload


def _font(size: int, font_path: Path) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(str(font_path), size=size)


def _fit_line(
    draw: ImageDraw.ImageDraw,
    value: object,
    font: ImageFont.FreeTypeFont,
    maximum_width: int,
    maximum_chars: int = 80,
) -> str:
    text = _text(value, maximum_chars)
    if draw.textbbox((0, 0), text, font=font)[2] <= maximum_width:
        return text
    ellipsis = "…"
    while text and draw.textbbox((0, 0), text + ellipsis, font=font)[2] > maximum_width:
        text = text[:-1]
    return text + ellipsis if text else ellipsis


def _draw_centered(
    draw: ImageDraw.ImageDraw,
    center_x: int,
    y: int,
    value: object,
    font: ImageFont.FreeTypeFont,
    maximum_width: int,
    *,
    fill: str = TEXT_COLOR,
) -> None:
    text = _fit_line(draw, value, font, maximum_width)
    box = draw.textbbox((0, 0), text, font=font)
    draw.text((center_x - (box[2] - box[0]) / 2, y), text, font=font, fill=fill)


def _compact_duration(seconds: object) -> str:
    parsed = _number(seconds)
    if parsed is None:
        return "--"
    total = int(parsed)
    hours, remainder = divmod(total, 3600)
    minutes = remainder // 60
    if hours:
        return f"{hours}小时{minutes}分"
    if minutes:
        return f"{minutes}分钟"
    return f"{total}秒"


def _wrap_lines(
    draw: ImageDraw.ImageDraw,
    value: object,
    font: ImageFont.FreeTypeFont,
    maximum_width: int,
    maximum_lines: int,
) -> list[str]:
    text = _text(value, 420)
    if not text:
        return []
    lines: list[str] = []
    current = ""
    for character in text:
        candidate = current + character
        if current and draw.textbbox((0, 0), candidate, font=font)[2] > maximum_width:
            lines.append(current)
            current = character
            if len(lines) == maximum_lines:
                break
        else:
            current = candidate
    if len(lines) < maximum_lines and current:
        lines.append(current)
    truncated = "".join(lines) != text
    if truncated and lines:
        lines[-1] = _fit_line(draw, lines[-1] + "…", font, maximum_width)
    return lines[:maximum_lines]


def _draw_statistics(draw: ImageDraw.ImageDraw, payload: dict[str, Any], font_path: Path) -> None:
    statistics = payload["statistics"]
    value_font = _font(45, font_path)
    topic_font = _font(34, font_path)
    detail_font = _font(18, font_path)
    centers = (170, 425, 680, 934)

    video_count = int(_number(statistics.get("video_count")) or 0)
    record_count = int(_number(statistics.get("record_count")) or 0)
    _draw_centered(draw, centers[0], 348, f"{video_count} 个", value_font, 205)
    _draw_centered(draw, centers[0], 400, f"{record_count} 条观看记录", detail_font, 205, fill=SECONDARY_TEXT_COLOR)

    watch_seconds = statistics.get("estimated_watch_seconds")
    _draw_centered(draw, centers[1], 348, _compact_duration(watch_seconds), value_font, 210)
    duration_note = "根据播放进度估算" if _number(watch_seconds) is not None else "浏览器历史无法估算"
    _draw_centered(draw, centers[1], 400, duration_note, detail_font, 210, fill=SECONDARY_TEXT_COLOR)

    topics = [_text(item, 8) for item in statistics.get("primary_topics", []) if _text(item, 8)][:2]
    if not topics:
        topics = ["暂无识别"]
    topic_start = 350 if len(topics) == 2 else 372
    for index, topic in enumerate(topics):
        _draw_centered(draw, centers[2], topic_start + index * 45, topic, topic_font, 210)

    average = _number(statistics.get("average_completion_rate"), maximum=1)
    average_text = f"{average:.0%}" if average is not None else "--"
    _draw_centered(draw, centers[3], 348, average_text, value_font, 205)
    completed = _number(statistics.get("completed_videos"))
    progress_note = f"看完 {int(completed)} 个视频" if completed is not None else "浏览器历史无法估算"
    _draw_centered(draw, centers[3], 400, progress_note, detail_font, 205, fill=SECONDARY_TEXT_COLOR)


def _draw_top_commentary(draw: ImageDraw.ImageDraw, payload: dict[str, Any], font_path: Path) -> None:
    title_font = _font(23, font_path)
    detail_font = _font(17, font_path)
    title_y = (626, 738, 850)
    detail_y = (666, 778, 890)
    items = [item for item in payload.get("top_commentary", []) if isinstance(item, dict)][:3]
    for index in range(3):
        if index >= len(items):
            placeholder = "昨日未识别到更多时评内容" if index == 0 else "--"
            draw.text((142, title_y[index]), placeholder, font=detail_font, fill="#C98A86")
            continue
        item = items[index]
        title = _fit_line(draw, item.get("title"), title_font, 378, 80)
        draw.text((142, title_y[index]), title, font=title_font, fill=TEXT_COLOR)
        detail_parts = [_text(item.get("topic"), 12)]
        author = _text(item.get("author_name"), 20)
        if author:
            detail_parts.append(author)
        completion = _number(item.get("completion_rate"), maximum=1)
        if completion is not None:
            detail_parts.append(f"进度{completion:.0%}")
        else:
            visits = int(_number(item.get("visit_count")) or 0)
            detail_parts.append(f"访问{visits}次")
        detail = _fit_line(draw, " · ".join(part for part in detail_parts if part), detail_font, 378)
        draw.text((142, detail_y[index]), detail, font=detail_font, fill=SECONDARY_TEXT_COLOR)


def _draw_interest_tags(draw: ImageDraw.ImageDraw, payload: dict[str, Any], font_path: Path) -> None:
    font = _font(26, font_path)
    centers = ((705, 650), (925, 650), (705, 757), (925, 757), (705, 864), (925, 864))
    tags = [_text(item, 8) for item in payload.get("interest_tags", []) if _text(item, 8)][:6]
    for (center_x, y), tag in zip(centers, tags):
        _draw_centered(draw, center_x, y, tag, font, 185, fill="#A85830")


def _draw_summary(draw: ImageDraw.ImageDraw, payload: dict[str, Any], font_path: Path) -> None:
    font = _font(23, font_path)
    lines = _wrap_lines(draw, payload.get("overall_summary"), font, 600, 8)
    for index, line in enumerate(lines):
        draw.text((78, 1086 + index * 34), line, font=font, fill=TEXT_COLOR)


def _draw_source(draw: ImageDraw.ImageDraw, payload: dict[str, Any], font_path: Path) -> None:
    draw.rectangle((245, 1402, 845, 1446), fill=FOOTER_BACKGROUND)
    font = _font(17, font_path)
    source = "数据来源：" + (_text(payload.get("source_label"), 42) or "本地元数据")
    _draw_centered(draw, 543, 1415, source, font, 570, fill="#8C766A")


def render_bilibili_daily_card(
    payload: object,
    output_path: Path,
    *,
    template_path: Path = DEFAULT_TEMPLATE,
    font_path: Path = DEFAULT_FONT,
) -> Path:
    normalized = validate_payload(payload)
    if not template_path.is_file():
        raise BilibiliDailyCardError(f"Bilibili daily template is missing: {template_path}")
    if not font_path.is_file():
        raise BilibiliDailyCardError(f"Bilibili daily font is missing: {font_path}")
    with Image.open(template_path) as source:
        image = source.convert("RGB")
    if image.size != (1086, 1448):
        raise BilibiliDailyCardError("Bilibili daily template must be 1086x1448")
    draw = ImageDraw.Draw(image)
    _draw_statistics(draw, normalized, font_path)
    _draw_top_commentary(draw, normalized, font_path)
    _draw_interest_tags(draw, normalized, font_path)
    _draw_summary(draw, normalized, font_path)
    _draw_source(draw, normalized, font_path)

    resolved = output_path.resolve()
    resolved.parent.mkdir(parents=True, exist_ok=True)
    temporary = resolved.with_name(resolved.name + ".tmp")
    try:
        image.save(temporary, format="PNG", optimize=True)
        os.replace(temporary, resolved)
    finally:
        temporary.unlink(missing_ok=True)
    return resolved
