from __future__ import annotations

import asyncio
import time
from collections import OrderedDict

from .models import PlatformMessage


class MessageDeduplicator:
    """Bounds duplicate platform deliveries without persisting raw identifiers."""

    def __init__(self, ttl_seconds: float = 600.0, max_entries: int = 4096) -> None:
        if ttl_seconds <= 0 or max_entries <= 0:
            raise ValueError("deduplication bounds must be positive")
        self.ttl_seconds = ttl_seconds
        self.max_entries = max_entries
        self._seen: OrderedDict[str, float] = OrderedDict()
        self._lock = asyncio.Lock()

    async def first_delivery(self, message: PlatformMessage) -> bool:
        key = self._key(message)
        now = time.monotonic()
        async with self._lock:
            expired_before = now - self.ttl_seconds
            while self._seen:
                _, timestamp = next(iter(self._seen.items()))
                if timestamp >= expired_before:
                    break
                self._seen.popitem(last=False)
            if key in self._seen:
                return False
            self._seen[key] = now
            while len(self._seen) > self.max_entries:
                self._seen.popitem(last=False)
            return True

    async def forget(self, message: PlatformMessage) -> None:
        async with self._lock:
            self._seen.pop(self._key(message), None)

    @staticmethod
    def _key(message: PlatformMessage) -> str:
        return "\x1f".join(
            (
                message.platform,
                message.account_id,
                message.sender_id,
                message.unified_msg_origin or message.conversation_id,
                message.message_id,
            )
        )
