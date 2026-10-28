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
TAG_BOXES = (
    (608, 623, 798, 681), (829, 623, 1006, 681),
    (608, 730, 798, 788), (829, 730, 1006, 788),
    (608, 836, 798, 894), (829, 836, 1006, 894),
)


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


def _draw_in_box(
    draw: ImageDraw.ImageDraw,
    box: tuple[int, int, int, int],
    value: object,
    font: ImageFont.FreeTypeFont,
    *,
    maximum_lines: int = 1,
    line_gap: int = 6,
    align: str = "center",
    vertical: str = "center",
    fill: str = TEXT_COLOR,
) -> None:
    left, top, right, bottom = box
    lines = _wrap_lines(draw, value, font, right - left, maximum_lines)
    if not lines:
        return
    bounds = [draw.textbbox((0, 0), line, font=font) for line in lines]
    line_height = max(bound[3] - bound[1] for bound in bounds)
    height = line_height * len(lines) + line_gap * (len(lines) - 1)
    y = top if vertical == "top" else top + (bottom - top - height) / 2
    for line, bound in zip(lines, bounds):
        width, ink_height = bound[2] - bound[0], bound[3] - bound[1]
        x = left if align == "left" else left + (right - left - width) / 2
        # PIL's default origin includes ascent offsets; align the visible ink.
        draw.text((x - bound[0], y + (line_height - ink_height) / 2 - bound[1]),
                  line, font=font, fill=fill)
        y += line_height + line_gap


def _fit_font(draw: ImageDraw.ImageDraw, value: object, font_path: Path,
              size: int, width: int, minimum: int) -> ImageFont.FreeTypeFont:
    while size > minimum:
        font = _font(size, font_path)
        bounds = draw.textbbox((0, 0), _text(value, 80), font=font)
        if bounds[2] - bounds[0] <= width:
            return font
        size -= 1
    return _font(minimum, font_path)


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
    detail_font = _font(17, font_path)
    centers = (170, 425, 680, 934)

    video_count = int(_number(statistics.get("video_count")) or 0)
    record_count = int(_number(statistics.get("record_count")) or 0)
    watch_seconds = statistics.get("estimated_watch_seconds")
    duration_note = "根据播放进度估算" if _number(watch_seconds) is not None else "浏览器历史无法估算"

    topics = [_text(item, 8) for item in statistics.get("primary_topics", []) if _text(item, 8)][:2]
    if not topics:
        topics = ["暂无识别"]
    topic_start = 349 if len(topics) == 2 else 367
    for index, topic in enumerate(topics):
        y = topic_start + index * 37
        topic_font = _fit_font(draw, topic, font_path, 29, 184, 21)
        _draw_in_box(draw, (588, y, 772, y + 34), topic, topic_font)

    average = _number(statistics.get("average_completion_rate"), maximum=1)
    average_text = f"{average:.0%}" if average is not None else "--"
    completed = _number(statistics.get("completed_videos"))
    progress_note = f"看完 {int(completed)} 个视频" if completed is not None else "浏览器历史无法估算"
    values = ((0, f"{video_count} 个", f"{record_count} 条观看记录"),
              (1, _compact_duration(watch_seconds), duration_note),
              (3, average_text, progress_note))
    for index, value, note in values:
        center = centers[index]
        value_font = _fit_font(draw, value, font_path, 45, 194, 27)
        _draw_in_box(draw, (center - 97, 346, center + 97, 398), value, value_font)
        _draw_in_box(draw, (center - 100, 404, center + 100, 430), note, detail_font,
                     fill=SECONDARY_TEXT_COLOR)


def _draw_top_commentary(draw: ImageDraw.ImageDraw, payload: dict[str, Any], font_path: Path) -> None:
    title_font = _font(23, font_path)
    detail_font = _font(17, font_path)
    items = [item for item in payload.get("top_commentary", []) if isinstance(item, dict)][:3]
    content_mode = payload.get("analysis_basis") == "content_evidence"
    if content_mode:
        draw.rounded_rectangle((208, 528, 464, 569), radius=8, fill="#FF87A5")
        _draw_in_box(draw, (208, 528, 464, 569), "具体内容 TOP3", _font(28, font_path), fill="#FFFFFF")
    for index in range(3):
        row_top = 612 + index * 112
        if index >= len(items):
            placeholder = ("暂无可分析的内容" if content_mode else "昨日未识别到更多时评内容") if index == 0 else "--"
            _draw_in_box(draw, (142, row_top, 517, row_top + 28), placeholder,
                         detail_font, align="left", fill="#C98A86")
            continue
        item = items[index]
        if content_mode:
            content_font = _font(21, font_path)
            subject = _text(item.get("summary"), 160).rstrip("。；;")
            lines = _wrap_lines(draw, subject, content_font, 375, 2)
            if len(lines) == 2 and len(lines[-1]) <= 3 and not lines[-1].endswith("…"):
                content_font = _fit_font(draw, subject, font_path, 21, 375, 18)
                lines = _wrap_lines(draw, subject, content_font, 375, 2)
            for line_index, line in enumerate(lines):
                y = row_top + line_index * 40
                _draw_in_box(draw, (142, y, 517, y + 28), line, content_font, align="left")
        else:
            _draw_in_box(draw, (142, row_top, 517, row_top + 28), item.get("title"), title_font, align="left")
        detail_parts = [_text(item.get("topic"), 12)]
        if content_mode:
            detail_parts.append(_text(item.get("basis_label"), 10))
        author = _text(item.get("author_name"), 20)
        if author:
            detail_parts.append(author)
        completion = _number(item.get("completion_rate"), maximum=1)
        if completion is not None:
            detail_parts.append(f"进度{completion:.0%}")
        else:
            visits = int(_number(item.get("visit_count")) or 0)
            detail_parts.append(f"访问{visits}次")
        detail_top = row_top + (74 if content_mode else 40)
        _draw_in_box(draw, (142, detail_top, 510, detail_top + 22),
                     " · ".join(part for part in detail_parts if part), detail_font,
                     align="left", fill=SECONDARY_TEXT_COLOR)


def _draw_interest_tags(draw: ImageDraw.ImageDraw, payload: dict[str, Any], font_path: Path) -> None:
    font = _font(24, font_path)
    content_mode = payload.get("analysis_basis") == "content_evidence"
    tags = [_text(item, 18 if content_mode else 8) for item in payload.get("interest_tags", []) if _text(item, 18)][:6]
    for box, tag in zip(TAG_BOXES, tags):
        _draw_in_box(draw, box, tag, font, maximum_lines=2 if content_mode else 1,
                     line_gap=6, fill="#A85830")


def _draw_summary(draw: ImageDraw.ImageDraw, payload: dict[str, Any], font_path: Path) -> None:
    font = _font(23, font_path)
    _draw_in_box(draw, (78, 1090, 675, 1295), payload.get("overall_summary"), font,
                 maximum_lines=6, line_gap=10, align="left", vertical="top")


def _draw_source(draw: ImageDraw.ImageDraw, payload: dict[str, Any], font_path: Path) -> None:
    draw.rectangle((245, 1402, 845, 1446), fill=FOOTER_BACKGROUND)
    font = _font(17, font_path)
    source = "数据来源：" + (_text(payload.get("source_label"), 42) or "本地元数据")
    _draw_in_box(draw, (260, 1408, 830, 1440), source, font, fill="#8C766A")


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
    if normalized.get("analysis_basis") == "content_evidence":
        draw.rounded_rectangle((340, 144, 766, 181), radius=8, fill=FOOTER_BACKGROUND)
        _draw_in_box(draw, (340, 144, 766, 181), _text(normalized.get("date"), 10) + " · 内容与方向",
                     _font(25, font_path))
        draw.rounded_rectangle((742, 530, 897, 570), radius=8, fill="#FFB33B")
        _draw_in_box(draw, (742, 530, 897, 570), "内容方向", _font(30, font_path), fill="#FFFFFF")
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
