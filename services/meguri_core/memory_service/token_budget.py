from __future__ import annotations

import re
from typing import TypeVar


T = TypeVar("T")


_CJK = re.compile(r"[\u3040-\u30ff\u3400-\u9fff]")
_ASCII_WORD = re.compile(r"[A-Za-z0-9_]+")


def estimate_tokens(text: str) -> int:
    """Conservative dependency-free estimate for mixed Chinese/Japanese/English."""

    cjk_count = len(_CJK.findall(text))
    without_cjk = _CJK.sub(" ", text)
    ascii_tokens = sum(max(1, (len(word) + 3) // 4) for word in _ASCII_WORD.findall(without_cjk))
    punctuation = len(re.findall(r"[^\w\s]", without_cjk)) // 2
    return max(1, cjk_count + ascii_tokens + punctuation)


def take_within_token_budget(
    items: list[T],
    *,
    text_of,
    token_budget: int,
    per_item_overhead: int = 12,
) -> list[T]:
    if token_budget <= 0:
        return []
    selected: list[T] = []
    consumed = 0
    for item in items:
        cost = estimate_tokens(text_of(item)) + per_item_overhead
        if cost > token_budget - consumed:
            continue
        selected.append(item)
        consumed += cost
    return selected
