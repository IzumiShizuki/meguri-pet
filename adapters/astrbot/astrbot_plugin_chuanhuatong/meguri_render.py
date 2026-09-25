from __future__ import annotations

import json
import random
import re
import threading
from datetime import datetime
from pathlib import Path
from typing import Any, Mapping, Sequence
from zoneinfo import ZoneInfo


RESPONSE_FIELDS = frozenset(
    {
        "reply",
        "expression_tag",
        "expression_intensity",
        "voice_style",
        "memory_candidates",
    }
)
MEMORY_CANDIDATE_FIELDS = frozenset(
    {"type", "summary", "confidence", "sensitivity", "source_scope"}
)
EXPRESSION_TAGS = frozenset(
    {
        "affectionate",
        "angry",
        "confused",
        "embarrassed",
        "excited",
        "happy",
        "neutral",
        "sad",
        "sleepy",
        "surprised",
        "teasing",
        "worried",
    }
)
EXPRESSION_INTENSITIES = frozenset({"low", "medium", "high"})
VOICE_STYLES = frozenset(
    {
        "neutral",
        "soft",
        "cheerful",
        "restrained",
        "sleepy",
        "teasing",
        "affectionate",
        "worried",
    }
)
MEMORY_TYPES = frozenset(
    {"preference", "identity", "project", "commitment", "relationship", "routine", "event"}
)
MEMORY_SENSITIVITIES = frozenset({"normal", "private", "sensitive"})
MEMORY_SOURCE_SCOPES = frozenset({"current_message", "conversation"})
ALLOWED_OUTFITS = frozenset({"01", "02", "03", "04", "05", "06"})
BACKGROUND_GROUPS = (
    "work_day",
    "work_evening",
    "work_night",
    "private_day",
    "private_evening",
    "private_night",
    "sleep_night",
    "event_pool_day",
    "event_pool_evening",
    "event_pool_night",
    "event_shrine_day",
    "event_shrine_night",
)

DEFAULT_EXPRESSION_MAP: dict[str, dict[str, str]] = {
    "neutral": {"low": "001", "medium": "001", "high": "004"},
    "happy": {"low": "002", "medium": "003", "high": "202"},
    "affectionate": {"low": "022", "medium": "010", "high": "102"},
    "excited": {"low": "003", "medium": "202", "high": "206"},
    "angry": {"low": "012", "medium": "014", "high": "006"},
    "confused": {"low": "104", "medium": "015", "high": "211"},
    "embarrassed": {"low": "010", "medium": "102", "high": "211"},
    "sad": {"low": "016", "medium": "015", "high": "013"},
    "sleepy": {"low": "023", "medium": "013", "high": "005"},
    "surprised": {"low": "022", "medium": "011", "high": "211"},
    "teasing": {"low": "020", "medium": "021", "high": "006"},
    "worried": {"low": "009", "medium": "016", "high": "015"},
}

DEFAULT_MANIFEST: dict[str, Any] = {
    "version": 1,
    "sprite_dirs": ["sprites", "characters", "."],
    "backgrounds": {group: [] for group in BACKGROUND_GROUPS},
}

_FENCED_JSON_RE = re.compile(r"\A\s*```json\s*(\{.*\})\s*```\s*\Z", re.IGNORECASE | re.DOTALL)
_SPRITE_RE = re.compile(r"\Ace(0[1-6])(\d{3})([lm])\.png\Z", re.IGNORECASE)
_EXPRESSION_CODE_RE = re.compile(r"\A\d{3}\Z")
_BACKGROUND_EXTENSIONS = frozenset({".png", ".jpg", ".jpeg", ".webp"})
# A single physical line may carry several bracket groups, e.g. the server
# sometimes emits `【中文】【日文】` on one line instead of the canonical
# translation-line + original-line form. Groups are flattened before pairing.
_BILINGUAL_BRACKET_GROUP_RE = re.compile(r"【\s*(.*?)\s*】")
# Some Core replies (for example a direct weather response) arrive without
# full-width brackets at all, as a bare Chinese line followed by a Japanese line.
# Those are paired by script, so an unbracketed bilingual reply is still rendered
# as a learning card instead of falling back to plain text.
_KANA_RE = re.compile(r"[\u3040-\u309f\u30a0-\u30ff]")
_HAN_RE = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff]")
_BLANK_LINE_SPLIT_RE = re.compile(r"\n\s*\n")
_INLINE_NEWLINE_RE = re.compile(r"[ \t]*\n[ \t]*")
BILINGUAL_FONT_SIZE_DEFAULT = 42
BILINGUAL_FONT_SIZE_MIN = 18
BILINGUAL_FONT_SIZE_MAX = 64
_BILINGUAL_PAGE_WRAPPER_CHARS = 4


def _is_japanese_text(value: str) -> bool:
    """Japanese original: contains hiragana or katakana."""

    return bool(_KANA_RE.search(value))


def _is_chinese_text(value: str) -> bool:
    """Chinese translation: contains han characters and no kana."""

    return bool(_HAN_RE.search(value)) and not _KANA_RE.search(value)


def _pair_text_lines(values: Sequence[str]) -> list[tuple[str, str]] | None:
    """Pair a (translation, original, translation, original, ...) sequence.

    Returns ``None`` unless every pair is (Chinese without kana, text with kana),
    so ordinary multi-line text is never mistaken for a bilingual reply.
    """

    if len(values) < 2 or len(values) % 2:
        return None
    for index in range(0, len(values), 2):
        if not _is_chinese_text(values[index]) or not _is_japanese_text(values[index + 1]):
            return None
    return [
        (
            _INLINE_NEWLINE_RE.sub(" ", values[index]).strip(),
            _INLINE_NEWLINE_RE.sub(" ", values[index + 1]).strip(),
        )
        for index in range(0, len(values), 2)
    ]


def normalize_bilingual_font_size(value: Any) -> int:
    """Normalize the bilingual-only font size to a safe renderer range."""

    if isinstance(value, bool):
        size = BILINGUAL_FONT_SIZE_DEFAULT
    else:
        try:
            size = int(value)
        except (TypeError, ValueError, OverflowError):
            size = BILINGUAL_FONT_SIZE_DEFAULT
    return max(BILINGUAL_FONT_SIZE_MIN, min(BILINGUAL_FONT_SIZE_MAX, size))


def parse_bilingual_pairs(text: str) -> list[tuple[str, str]] | None:
    """Parse complete Chinese/Japanese line pairs without dropping stray text.

    The canonical form is ``【Chinese translation】`` followed by ``【Japanese
    original】``. The line order, rather than a visible language label, carries
    the meaning. Every non-empty line must consist of complete full-width bracket
    groups and belong to a pair; otherwise the caller preserves the original
    text unchanged.

    A physical line may hold one group (the canonical layout) or several groups
    (``【中文】【日文】``). Groups are flattened in reading order before pairing,
    so the two layouts produce identical pairs instead of a cross-language pair
    that mixes Chinese and Japanese inside one string.

    A reply with no brackets at all is still accepted when every line pairs as
    (Chinese without kana, text with kana); anything else returns ``None``.
    """

    if not isinstance(text, str) or not text.strip():
        return None

    groups: list[str] = []
    bracketed = True
    for raw_line in text.splitlines():
        if not raw_line.strip():
            continue
        # The line must be nothing but full-width bracket groups. One group is the
        # canonical layout; several groups on one physical line (`【中文】【日文】`)
        # are flattened in reading order so both layouts yield the same pairs.
        matches = list(_BILINGUAL_BRACKET_GROUP_RE.finditer(raw_line))
        if not matches or _BILINGUAL_BRACKET_GROUP_RE.sub("", raw_line).strip():
            bracketed = False
            break
        for match in matches:
            content = match.group(1).strip()
            if not content:
                return None
            groups.append(content)

    if bracketed and groups and not len(groups) % 2:
        return list(zip(groups[::2], groups[1::2]))

    # Unbracketed form: pair by blank-line block first, then by non-empty line,
    # and only when every pair is (Chinese, Japanese). Ordinary multi-line text
    # fails both attempts and is preserved unchanged by the caller.
    blocks = [
        block.strip() for block in _BLANK_LINE_SPLIT_RE.split(text) if block.strip()
    ]
    pairs = _pair_text_lines(blocks)
    if pairs is None:
        plain_lines = [line.strip() for line in text.splitlines() if line.strip()]
        pairs = _pair_text_lines(plain_lines)
    return pairs


def format_bilingual_pairs(pairs: Sequence[tuple[str, str]]) -> str:
    """Return renderer text in the canonical translation/original order."""

    return "\n\n".join(
        f"【{chinese.strip()}】\n【{japanese.strip()}】"
        for chinese, japanese in pairs
    )


def _balanced_text_slices(text: str, slice_count: int) -> list[str]:
    """Partition text into non-empty contiguous slices of near-equal length."""

    slice_count = max(1, min(slice_count, len(text)))
    base_size, larger_slice_count = divmod(len(text), slice_count)
    slices: list[str] = []
    offset = 0
    for index in range(slice_count):
        size = base_size + (1 if index < larger_slice_count else 0)
        slices.append(text[offset : offset + size])
        offset += size
    return slices


def _bilingual_pair_page_count(chinese: str, japanese: str, max_chars: int) -> int:
    """Return a feasible page count whose formatted slices fit the limit."""

    content_limit = max_chars - _BILINGUAL_PAGE_WRAPPER_CHARS
    max_page_count = min(len(chinese), len(japanese))
    if content_limit < 2 or max_page_count < 2:
        return 1

    total_length = len(chinese) + len(japanese)
    page_count = max(2, (total_length + content_limit - 1) // content_limit)
    page_count = min(page_count, max_page_count)
    while page_count < max_page_count:
        chinese_size = (len(chinese) + page_count - 1) // page_count
        japanese_size = (len(japanese) + page_count - 1) // page_count
        if chinese_size + japanese_size <= content_limit:
            break
        page_count += 1
    return page_count


def split_bilingual_text(text: str, max_chars: int) -> list[str] | None:
    """Return ordered, bounded bilingual renderer panels.

    Short translation/original pairs retain the one-pair-per-panel behavior.
    An oversized pair is partitioned into the same number of contiguous slices
    for both languages, so every page stays parseable and later pages continue
    instead of repeating earlier content.
    """

    pairs = parse_bilingual_pairs(text)
    if pairs is None:
        return None

    panels: list[str] = []
    for chinese, japanese in pairs:
        formatted_pair = format_bilingual_pairs([(chinese, japanese)])
        visible_length = len(formatted_pair.replace("\n", ""))
        if max_chars <= 0 or visible_length <= max_chars:
            panels.append(formatted_pair)
            continue

        page_count = _bilingual_pair_page_count(chinese, japanese, max_chars)
        if page_count <= 1:
            panels.append(formatted_pair)
            continue

        chinese_slices = _balanced_text_slices(chinese, page_count)
        japanese_slices = _balanced_text_slices(japanese, page_count)
        panels.extend(
            format_bilingual_pairs([(chinese_slice, japanese_slice)])
            for chinese_slice, japanese_slice in zip(chinese_slices, japanese_slices)
        )
    return panels


def _normalized_enum(value: Any, allowed: frozenset[str], fallback: str) -> str:
    normalized = value.strip().lower() if isinstance(value, str) else ""
    return normalized if normalized in allowed else fallback


def _normalize_memory_candidates(value: Any) -> list[dict[str, Any]] | None:
    if not isinstance(value, list) or len(value) > 3:
        return None
    normalized: list[dict[str, Any]] = []
    for candidate in value:
        if not isinstance(candidate, Mapping) or set(candidate) != MEMORY_CANDIDATE_FIELDS:
            return None
        summary = candidate.get("summary")
        confidence = candidate.get("confidence")
        if not isinstance(summary, str) or not summary.strip() or len(summary.strip()) > 500:
            return None
        if isinstance(confidence, bool) or not isinstance(confidence, (int, float)):
            return None
        if not 0 <= float(confidence) <= 1:
            return None
        candidate_type = candidate.get("type")
        sensitivity = candidate.get("sensitivity")
        source_scope = candidate.get("source_scope")
        if candidate_type not in MEMORY_TYPES:
            return None
        if sensitivity not in MEMORY_SENSITIVITIES:
            return None
        if source_scope not in MEMORY_SOURCE_SCOPES:
            return None
        normalized.append(
            {
                "type": candidate_type,
                "summary": summary.strip(),
                "confidence": float(confidence),
                "sensitivity": sensitivity,
                "source_scope": source_scope,
            }
        )
    return normalized


def _normalize_response(value: Any, *, exact_fields: bool) -> dict[str, Any] | None:
    if not isinstance(value, Mapping):
        return None
    if exact_fields:
        if set(value) != RESPONSE_FIELDS:
            return None
    elif not RESPONSE_FIELDS.issubset(value):
        return None

    reply = value.get("reply")
    if not isinstance(reply, str) or not reply.strip():
        return None
    memory_candidates = _normalize_memory_candidates(value.get("memory_candidates"))
    if memory_candidates is None:
        return None
    return {
        "reply": reply.strip(),
        "expression_tag": _normalized_enum(value.get("expression_tag"), EXPRESSION_TAGS, "neutral"),
        "expression_intensity": _normalized_enum(
            value.get("expression_intensity"), EXPRESSION_INTENSITIES, "low"
        ),
        "voice_style": _normalized_enum(value.get("voice_style"), VOICE_STYLES, "neutral"),
        "memory_candidates": memory_candidates,
    }


def parse_meguri_json(text: str) -> tuple[str, dict[str, Any]] | None:
    """Parse the canonical five-field LLM JSON into a renderer payload.

    Only a bare JSON object or a complete ``json`` fenced block is accepted.
    The renderer never accepts background, sprite, or outfit fields from the LLM.
    """

    if not isinstance(text, str):
        return None
    candidate = text.strip()
    fenced = _FENCED_JSON_RE.fullmatch(candidate)
    if fenced:
        candidate = fenced.group(1)
    elif candidate.startswith("```"):
        return None
    try:
        decoded = json.loads(candidate)
    except (json.JSONDecodeError, TypeError):
        return None
    response = _normalize_response(decoded, exact_fields=True)
    if response is None:
        return None
    payload = {
        "response": response,
        "runtime_state": {},
        "expression": {
            "expression_tag": response["expression_tag"],
            "expression_intensity": response["expression_intensity"],
        },
    }
    return response["reply"], payload


def normalize_gateway_payload(payload: Any) -> dict[str, Any] | None:
    """Validate the core/gateway payload and make resolved expression data authoritative."""

    if not isinstance(payload, Mapping):
        return None
    if RESPONSE_FIELDS.issubset(payload):
        payload = {"response": payload, "runtime_state": {}, "expression": {}}

    response = _normalize_response(payload.get("response"), exact_fields=False)
    if response is None:
        return None
    raw_runtime = payload.get("runtime_state")
    runtime_state = dict(raw_runtime) if isinstance(raw_runtime, Mapping) else {}
    raw_expression = payload.get("expression")
    raw_expression = dict(raw_expression) if isinstance(raw_expression, Mapping) else {}

    resolved_tag = (
        _normalized_enum(raw_expression.get("expression_tag"), EXPRESSION_TAGS, "neutral")
        if "expression_tag" in raw_expression
        else response["expression_tag"]
    )
    resolved_intensity = (
        _normalized_enum(raw_expression.get("expression_intensity"), EXPRESSION_INTENSITIES, "low")
        if "expression_intensity" in raw_expression
        else response["expression_intensity"]
    )

    validation = dict(payload.get("_render_validation", {})) if isinstance(
        payload.get("_render_validation"), Mapping
    ) else {}
    outfit: str | None = None
    if "outfit_code" in raw_expression:
        candidate_outfit = raw_expression.get("outfit_code")
        if candidate_outfit in ALLOWED_OUTFITS:
            outfit = str(candidate_outfit)
        else:
            validation["rejected_outfit"] = True
    elif "outfit_code" in runtime_state:
        candidate_outfit = runtime_state.get("outfit_code")
        if candidate_outfit in ALLOWED_OUTFITS:
            outfit = str(candidate_outfit)
        else:
            validation["rejected_outfit"] = True
    else:
        outfit = scheduled_outfit(runtime_state)

    expression_code = raw_expression.get("expression_code")
    if not isinstance(expression_code, str) or not _EXPRESSION_CODE_RE.fullmatch(expression_code):
        expression_code = None

    sprite_file = raw_expression.get("sprite_file")
    if sprite_file is not None:
        match = _safe_sprite_name(sprite_file)
        if match is None:
            validation["rejected_sprite"] = True
            sprite_file = None
        else:
            sprite_outfit, sprite_code, _ = match
            if outfit is not None and sprite_outfit != outfit:
                validation["rejected_sprite"] = True
                sprite_file = None
            elif expression_code is not None and sprite_code != expression_code:
                validation["rejected_sprite"] = True
                sprite_file = None
            else:
                sprite_file = Path(str(sprite_file)).name
                expression_code = expression_code or sprite_code
                outfit = outfit or sprite_outfit

    expression = {
        "expression_tag": resolved_tag,
        "expression_intensity": resolved_intensity,
        "outfit_code": outfit,
        "expression_code": expression_code,
        "sprite_file": sprite_file,
    }
    normalized = {
        "response": response,
        "runtime_state": runtime_state,
        "expression": expression,
    }
    if validation:
        normalized["_render_validation"] = validation
    return normalized


def _parse_local_hour(value: Any) -> float | None:
    if not isinstance(value, str) or not value.strip():
        return None
    candidate = value.strip()
    try:
        parsed = datetime.fromisoformat(candidate.replace("Z", "+00:00"))
        return parsed.hour + parsed.minute / 60
    except ValueError:
        match = re.fullmatch(r"(\d{1,2}):(\d{2})(?::\d{2}(?:\.\d+)?)?", candidate)
        if not match:
            return None
        hour, minute = int(match.group(1)), int(match.group(2))
        if hour > 23 or minute > 59:
            return None
        return hour + minute / 60


def scheduled_outfit(runtime_state: Mapping[str, Any] | None = None) -> str:
    state = runtime_state if isinstance(runtime_state, Mapping) else {}
    hour = _parse_local_hour(state.get("local_time"))
    is_holiday = state.get("is_holiday") is True
    if hour is None:
        now = datetime.now(ZoneInfo("Asia/Shanghai"))
        hour = now.hour + now.minute / 60
        if "is_holiday" not in state:
            is_holiday = now.weekday() >= 5
    if hour >= 22 or hour < 8:
        return "04"
    if hour < 18:
        return "02" if is_holiday else "01"
    return "03"


def background_group_for_payload(payload: Mapping[str, Any] | None) -> str:
    normalized = normalize_gateway_payload(payload) if isinstance(payload, Mapping) else None
    if normalized is None:
        runtime_state: Mapping[str, Any] = {}
        outfit = scheduled_outfit(runtime_state)
    else:
        runtime_state = normalized["runtime_state"]
        outfit = normalized["expression"].get("outfit_code")
    mode = runtime_state.get("mode") if runtime_state.get("mode") in {"work", "private", "sleep", "event"} else None
    hour = _parse_local_hour(runtime_state.get("local_time"))
    if hour is None:
        now = datetime.now(ZoneInfo("Asia/Shanghai"))
        hour = now.hour + now.minute / 60

    period = "night" if hour >= 22 or hour < 8 else "evening" if hour >= 18 else "day"

    if outfit == "05":
        return f"event_pool_{period}"
    if outfit == "06":
        return "event_shrine_night" if period == "night" else "event_shrine_day"
    if mode == "event":
        return f"event_pool_{period}"
    if outfit == "04" or mode == "sleep":
        return "sleep_night"
    if outfit == "03":
        return "private_night" if period == "night" else "private_evening"
    if outfit == "02":
        return f"private_{period}"
    if outfit == "01" or mode == "work":
        return f"work_{period}"

    if period == "night":
        return "sleep_night"
    if period == "evening":
        return "private_evening"
    return "private_day" if runtime_state.get("is_holiday") is True else "work_day"


def load_asset_manifest(source: Path | str | Mapping[str, Any] | None) -> dict[str, Any]:
    if isinstance(source, Mapping):
        decoded: Any = dict(source)
    elif source is None:
        decoded = {}
    else:
        try:
            # Windows PowerShell 5.1 writes UTF-8 JSON with a BOM by default.
            decoded = json.loads(Path(source).read_text(encoding="utf-8-sig"))
        except (OSError, json.JSONDecodeError, TypeError):
            decoded = {}
    if not isinstance(decoded, Mapping):
        decoded = {}
    sprite_dirs = decoded.get("sprite_dirs")
    if not isinstance(sprite_dirs, list) or not all(isinstance(value, str) for value in sprite_dirs):
        sprite_dirs = list(DEFAULT_MANIFEST["sprite_dirs"])
    backgrounds = decoded.get("backgrounds")
    backgrounds = dict(backgrounds) if isinstance(backgrounds, Mapping) else {}
    return {
        **dict(decoded),
        "version": decoded.get("version", 1),
        "sprite_dirs": sprite_dirs,
        "backgrounds": {group: backgrounds.get(group, []) for group in BACKGROUND_GROUPS},
    }


def _safe_sprite_name(value: Any) -> tuple[str, str, str] | None:
    if not isinstance(value, str) or Path(value).name != value:
        return None
    match = _SPRITE_RE.fullmatch(value)
    if not match:
        return None
    return match.group(1), match.group(2), match.group(3).lower()


def _within_root(path: Path, root: Path) -> bool:
    try:
        path.resolve().relative_to(root.resolve())
        return True
    except (OSError, ValueError):
        return False


def _safe_relative_file(root: Path, value: Any, *, extensions: frozenset[str] | None = None) -> Path | None:
    if not isinstance(value, str) or not value.strip():
        return None
    relative = Path(value)
    if relative.is_absolute() or ".." in relative.parts:
        return None
    candidate = (root / relative).resolve()
    if not _within_root(candidate, root) or not candidate.is_file():
        return None
    if extensions is not None and candidate.suffix.lower() not in extensions:
        return None
    return candidate


def _sprite_bases(root: Path, manifest: Mapping[str, Any]) -> list[Path]:
    bases: list[Path] = []
    for raw_dir in manifest.get("sprite_dirs", DEFAULT_MANIFEST["sprite_dirs"]):
        if not isinstance(raw_dir, str):
            continue
        relative = Path(raw_dir)
        if relative.is_absolute() or ".." in relative.parts:
            continue
        candidate = (root / relative).resolve()
        if _within_root(candidate, root) and candidate not in bases:
            bases.append(candidate)
    return bases


def _find_sprite(root: Path, bases: Sequence[Path], outfit: str, filename: str) -> Path | None:
    for base in bases:
        for candidate in (base / outfit / filename, base / filename):
            if _within_root(candidate, root) and candidate.is_file():
                return candidate.resolve()
    matches = sorted(
        (candidate.resolve() for candidate in root.rglob(filename) if _within_root(candidate, root)),
        key=lambda candidate: (len(candidate.parts), str(candidate).lower()),
    )
    return matches[0] if matches else None


def resolve_sprite_path(
    asset_root: Path | str,
    payload: Mapping[str, Any],
    manifest: Path | str | Mapping[str, Any] | None = None,
) -> Path | None:
    """Resolve a safe local Meguri sprite, preferring medium-sized assets."""

    root = Path(asset_root).resolve()
    existing_validation = payload.get("_render_validation") if isinstance(payload, Mapping) else None
    if isinstance(existing_validation, Mapping) and (
        existing_validation.get("rejected_outfit") or existing_validation.get("rejected_sprite")
    ):
        return None
    normalized = normalize_gateway_payload(payload)
    if normalized is None:
        return None
    validation = normalized.get("_render_validation", {})
    if validation.get("rejected_outfit") or validation.get("rejected_sprite"):
        return None
    expression = normalized["expression"]
    outfit = expression.get("outfit_code")
    if outfit not in ALLOWED_OUTFITS:
        return None

    safe_sprite = _safe_sprite_name(expression.get("sprite_file")) if expression.get("sprite_file") else None
    code = expression.get("expression_code")
    if safe_sprite:
        sprite_outfit, sprite_code, _ = safe_sprite
        if sprite_outfit != outfit or (code is not None and sprite_code != code):
            return None
        code = sprite_code
    if not isinstance(code, str) or not _EXPRESSION_CODE_RE.fullmatch(code):
        tag = expression.get("expression_tag", "neutral")
        intensity = expression.get("expression_intensity", "low")
        code = DEFAULT_EXPRESSION_MAP.get(tag, DEFAULT_EXPRESSION_MAP["neutral"]).get(
            intensity, DEFAULT_EXPRESSION_MAP["neutral"]["low"]
        )

    loaded_manifest = load_asset_manifest(manifest or root / "meguri_asset_manifest.json")
    bases = _sprite_bases(root, loaded_manifest)
    for filename in (f"ce{outfit}{code}m.png", f"ce{outfit}{code}l.png"):
        found = _find_sprite(root, bases, outfit, filename)
        if found is not None:
            return found

    neutral_codes = []
    intensity = expression.get("expression_intensity", "low")
    for candidate in (
        DEFAULT_EXPRESSION_MAP["neutral"].get(intensity),
        DEFAULT_EXPRESSION_MAP["neutral"]["medium"],
        DEFAULT_EXPRESSION_MAP["neutral"]["low"],
    ):
        if candidate and candidate not in neutral_codes:
            neutral_codes.append(candidate)
    for neutral_code in neutral_codes:
        for filename in (f"ce{outfit}{neutral_code}m.png", f"ce{outfit}{neutral_code}l.png"):
            found = _find_sprite(root, bases, outfit, filename)
            if found is not None:
                return found
    return None


def _manifest_entry_path(entry: Any) -> Any:
    return entry.get("path") if isinstance(entry, Mapping) else entry


def choose_background_path(
    asset_root: Path | str,
    payload: Mapping[str, Any],
    manifest: Path | str | Mapping[str, Any] | None = None,
    *,
    previous: Path | str | None = None,
    rng: random.Random | None = None,
) -> Path | None:
    root = Path(asset_root).resolve()
    loaded_manifest = load_asset_manifest(manifest or root / "meguri_asset_manifest.json")
    group = background_group_for_payload(payload)
    raw_entries = loaded_manifest.get("backgrounds", {}).get(group, [])
    if not isinstance(raw_entries, list):
        return None
    candidates: list[Path] = []
    for entry in raw_entries:
        candidate = _safe_relative_file(
            root, _manifest_entry_path(entry), extensions=_BACKGROUND_EXTENSIONS
        )
        if candidate is not None and candidate not in candidates:
            candidates.append(candidate)
    if not candidates:
        return None

    previous_resolved: Path | None = None
    if previous is not None:
        try:
            previous_resolved = Path(previous).resolve()
        except OSError:
            previous_resolved = None
    available = [candidate for candidate in candidates if candidate != previous_resolved]
    if not available:
        available = candidates
    return (rng or random).choice(available)


class BackgroundPicker:
    """Session-aware background picker that avoids immediate repetition."""

    def __init__(
        self,
        asset_root: Path | str,
        manifest: Path | str | Mapping[str, Any] | None = None,
        *,
        rng: random.Random | None = None,
    ) -> None:
        self.asset_root = Path(asset_root).resolve()
        self.manifest = load_asset_manifest(manifest or self.asset_root / "meguri_asset_manifest.json")
        self.rng = rng or random.Random()
        self._last_by_session: dict[str, Path] = {}
        self._lock = threading.Lock()

    def pick(self, payload: Mapping[str, Any], session_id: str | None = None) -> Path | None:
        key = session_id or "__global__"
        with self._lock:
            selected = choose_background_path(
                self.asset_root,
                payload,
                self.manifest,
                previous=self._last_by_session.get(key),
                rng=self.rng,
            )
            if selected is not None:
                self._last_by_session[key] = selected
            return selected


class MeguriAssetResolver:
    """Small integration facade for the AstrBot renderer."""

    def __init__(
        self,
        asset_root: Path | str,
        manifest: Path | str | Mapping[str, Any] | None = None,
        *,
        rng: random.Random | None = None,
    ) -> None:
        self.asset_root = Path(asset_root).resolve()
        self.manifest = load_asset_manifest(manifest or self.asset_root / "meguri_asset_manifest.json")
        self.backgrounds = BackgroundPicker(self.asset_root, self.manifest, rng=rng)

    def sprite(self, payload: Mapping[str, Any]) -> Path | None:
        return resolve_sprite_path(self.asset_root, payload, self.manifest)

    def background(self, payload: Mapping[str, Any], session_id: str | None = None) -> Path | None:
        return self.backgrounds.pick(payload, session_id)


parse_meguri_response = parse_meguri_json
