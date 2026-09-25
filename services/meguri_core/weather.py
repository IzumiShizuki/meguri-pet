from __future__ import annotations

import asyncio
import os
import re
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Mapping

import httpx

from .schemas import LlmResponse, TurnRequest


OPEN_METEO_FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
DEFAULT_LOCATION_NAME = "杭州市钱塘区"
DEFAULT_LATITUDE = 30.323040
DEFAULT_LONGITUDE = 120.493941
DEFAULT_TIMEZONE = "Asia/Shanghai"
WEATHER_CACHE_TTL = timedelta(minutes=10)


@dataclass(frozen=True)
class WeatherBriefing:
    location_name: str
    timezone: str
    condition_zh: str
    condition_ja: str
    temperature_c: float | None
    apparent_temperature_c: float | None
    humidity_percent: int | None
    wind_speed_kmh: float | None
    precipitation_mm: float | None

    def chinese_line(self) -> str:
        details = [f"{self.location_name}现在{self.condition_zh}"]
        if self.temperature_c is not None:
            details.append(f"气温{self.temperature_c:.0f}°C")
        if self.apparent_temperature_c is not None:
            details.append(f"体感{self.apparent_temperature_c:.0f}°C")
        if self.humidity_percent is not None:
            details.append(f"湿度{self.humidity_percent}%")
        if self.wind_speed_kmh is not None:
            details.append(f"风速{self.wind_speed_kmh:.0f} km/h")
        if self.precipitation_mm is not None:
            details.append(f"降水{self.precipitation_mm:.1f} mm")
        return "，".join(details) + "。"

    def japanese_line(self) -> str:
        details = [f"{self.location_name}はいま{self.condition_ja}です"]
        if self.temperature_c is not None:
            details.append(f"気温は{self.temperature_c:.0f}°C")
        if self.apparent_temperature_c is not None:
            details.append(f"体感は{self.apparent_temperature_c:.0f}°C")
        if self.humidity_percent is not None:
            details.append(f"湿度は{self.humidity_percent}%")
        if self.wind_speed_kmh is not None:
            details.append(f"風速は{self.wind_speed_kmh:.0f}km/h")
        if self.precipitation_mm is not None:
            details.append(f"降水量は{self.precipitation_mm:.1f}mm")
        return "、".join(details) + "。"


@dataclass(frozen=True)
class WeatherContext:
    intent: str
    briefing: WeatherBriefing | None

    @property
    def requested(self) -> bool:
        return self.intent != "none"

    @property
    def available(self) -> bool:
        return self.briefing is not None

    def prompt_context(self) -> str | None:
        if not self.briefing:
            return None
        return (
            "system_weather_context: This is the authoritative current weather fact for this turn. "
            f"{self.briefing.chinese_line()} {self.briefing.japanese_line()} "
            "Do not place transient weather facts in memory_candidates."
        )

    def decorate_response(self, response: LlmResponse, reply_format: str) -> LlmResponse:
        if not self.requested:
            return response
        if self.briefing is None:
            chinese = "实时天气暂时无法获取。"
            japanese = "現在の天気は一時的に取得できません。"
        else:
            chinese = self.briefing.chinese_line()
            japanese = self.briefing.japanese_line()
        if reply_format == "zh_ja_pairs":
            prefix = f"【{chinese}】\n【{japanese}】"
        else:
            prefix = chinese
        reply = response.reply.strip()
        return response.model_copy(
            update={
                "reply": prefix if not reply else f"{prefix}\n\n{reply}",
                # Current weather is transient. Do not allow it to affect memory review.
                "memory_candidates": [],
            }
        )


class WeatherService:
    """Read-only, on-demand Open-Meteo integration for weather-related turns."""

    _WEATHER_TERMS = re.compile(
        r"天气|气温|温度|湿度|下雨|降雨|雨伞|台风|风大|晴天|多云|雷雨|下雪|"
        r"天気|気温|湿度|雨|傘|台風|風|晴れ|曇|雪|"
        r"\bweather\b|\btemperature\b|\brain\b|\bforecast\b",
        re.IGNORECASE,
    )
    _OUTING_TERMS = re.compile(
        r"出门|外出|出发|上班|上学|回家|去公司|去学校|"
        r"出かけ|外出|出発|帰る|通勤|通学|"
        r"\bgoing out\b|\bleaving\b|\bcommut",
        re.IGNORECASE,
    )

    def __init__(
        self,
        *,
        enabled: bool | None = None,
        location_name: str | None = None,
        latitude: float | None = None,
        longitude: float | None = None,
        timezone: str | None = None,
        timeout_seconds: float | None = None,
        transport: httpx.AsyncBaseTransport | None = None,
        env: Mapping[str, str] | None = None,
    ) -> None:
        values = os.environ if env is None else env
        self.enabled = (
            _env_bool(values.get("MEGURI_WEATHER_ENABLED"), False)
            if enabled is None
            else enabled
        )
        self.location_name = (location_name or values.get("MEGURI_WEATHER_LOCATION_NAME") or DEFAULT_LOCATION_NAME).strip()
        self.latitude = _float_value(latitude, values.get("MEGURI_WEATHER_LATITUDE"), DEFAULT_LATITUDE)
        self.longitude = _float_value(longitude, values.get("MEGURI_WEATHER_LONGITUDE"), DEFAULT_LONGITUDE)
        self.timezone = (timezone or values.get("MEGURI_WEATHER_TIMEZONE") or DEFAULT_TIMEZONE).strip()
        self.timeout = httpx.Timeout(
            _float_value(timeout_seconds, values.get("MEGURI_WEATHER_TIMEOUT_SECONDS"), 8.0)
        )
        self.transport = transport
        self._cached: tuple[datetime, WeatherBriefing] | None = None
        self._lock = asyncio.Lock()

    def classify(self, message: str) -> str:
        normalized = message.strip()
        if not normalized:
            return "none"
        if self._OUTING_TERMS.search(normalized):
            return "outing"
        return "weather" if self._WEATHER_TERMS.search(normalized) else "none"

    async def context_for(self, request: TurnRequest) -> WeatherContext:
        intent = self.classify(request.message)
        if intent == "none":
            return WeatherContext(intent=intent, briefing=None)
        if not self.enabled:
            return WeatherContext(intent=intent, briefing=None)
        try:
            return WeatherContext(intent=intent, briefing=await self.current())
        except (httpx.HTTPError, ValueError, KeyError, TypeError):
            return WeatherContext(intent=intent, briefing=None)

    async def current(self) -> WeatherBriefing:
        now = datetime.now()
        if self._cached and now - self._cached[0] < WEATHER_CACHE_TTL:
            return self._cached[1]
        async with self._lock:
            now = datetime.now()
            if self._cached and now - self._cached[0] < WEATHER_CACHE_TTL:
                return self._cached[1]
            params = {
                "latitude": self.latitude,
                "longitude": self.longitude,
                "current": (
                    "temperature_2m,relative_humidity_2m,apparent_temperature,"
                    "weather_code,wind_speed_10m,precipitation"
                ),
                "timezone": self.timezone,
            }
            async with httpx.AsyncClient(
                timeout=self.timeout,
                transport=self.transport,
                follow_redirects=False,
            ) as client:
                response = await client.get(OPEN_METEO_FORECAST_URL, params=params)
                response.raise_for_status()
                payload = response.json()
            current = payload["current"]
            code = int(current["weather_code"])
            condition_zh, condition_ja = _weather_code_label(code)
            briefing = WeatherBriefing(
                location_name=self.location_name,
                timezone=self.timezone,
                condition_zh=condition_zh,
                condition_ja=condition_ja,
                temperature_c=_number(current.get("temperature_2m")),
                apparent_temperature_c=_number(current.get("apparent_temperature")),
                humidity_percent=_integer(current.get("relative_humidity_2m")),
                wind_speed_kmh=_number(current.get("wind_speed_10m")),
                precipitation_mm=_number(current.get("precipitation")),
            )
            self._cached = (now, briefing)
            return briefing


def _weather_code_label(code: int) -> tuple[str, str]:
    if code == 0:
        return "晴朗", "晴れ"
    if code in {1, 2}:
        return "晴间多云", "晴れ時々くもり"
    if code == 3:
        return "多云", "くもり"
    if code in {45, 48}:
        return "有雾", "霧"
    if code in {51, 53, 55, 56, 57}:
        return "毛毛雨", "霧雨"
    if code in {61, 63, 65, 66, 67, 80, 81, 82}:
        return "有雨", "雨"
    if code in {71, 73, 75, 77, 85, 86}:
        return "有雪", "雪"
    if code in {95, 96, 99}:
        return "雷雨", "雷雨"
    return "天气未知", "不明"


def _env_bool(value: str | None, default: bool) -> bool:
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


def _float_value(explicit: float | None, configured: str | None, default: float) -> float:
    if explicit is not None:
        return float(explicit)
    if not configured:
        return default
    try:
        return float(configured)
    except ValueError:
        return default


def _number(value: object) -> float | None:
    return float(value) if isinstance(value, (int, float)) else None


def _integer(value: object) -> int | None:
    return int(value) if isinstance(value, (int, float)) else None
