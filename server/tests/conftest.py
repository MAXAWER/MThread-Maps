"""Общие фикстуры: сервисы на демо-провайдерах и подставная погода (без сети)."""
from __future__ import annotations

import json
from datetime import datetime

import pytest

from app.catalog import load_catalog
from app.config import Settings
from app.dgis import UsageCounters
from app.jev import build_questions
from app.models import ASTANA_TZ, SearchRequest
from app.providers.fake_dgis import FakeDgis
from app.providers.fake_jev import FakeJev
from app.providers.fake_llm import TemplateLLM
from app.search import stream_search
from app.services import Services
from app.weather import apply_override, sun_times

MSW = (51.0890, 71.4086)
KSH = (51.1324, 71.4036)
NQZ = (51.0222, 71.4669)


class StubWeather:
    """Погода без сети: сухо, +6 °C; override — как WEATHER_OVERRIDE."""

    def __init__(self, override: dict | None = None):
        self.override = override
        self.calls = {}

    async def get(self, lat, lon, now=None):
        now = now or datetime.now(ASTANA_TZ)
        rise, sset = sun_times(lat, lon, now.date())
        base = {
            "temp_c": 6.0, "feels_like_c": 3.0, "wind_ms": 4.0, "precip_now": False, "precip_starts_in_min": None,
            "precip_intensity_mmh": None, "precision": None, "confirmed": False, "rain_soon": False,
            "precip_next_3h": False, "hourly": [], "source": "stub",
            "sunrise": rise.strftime("%H:%M"), "sunset": sset.strftime("%H:%M"),
            "minutes_to_sunset": int((sset - now).total_seconds() // 60), "is_dark": not (rise <= now <= sset),
        }
        return apply_override(base, self.override) if self.override else base


def make_services(weather_override: dict | None = None, llm=True) -> Services:
    catalog = load_catalog()
    settings = Settings(demo_mode=True)
    counters = UsageCounters(1000)
    return Services(
        settings=settings, catalog=catalog, places=FakeDgis(catalog, counters), jev=FakeJev(catalog),
        llm=TemplateLLM(catalog) if llm else None, weather=StubWeather(weather_override),
        questions=build_questions(catalog), counters=counters, data_source="demo",
    )


@pytest.fixture
def svc() -> Services:
    return make_services()


def at(hour: int, minute: int = 0, day: int = 22) -> datetime:
    """2026-10-22 — четверг."""
    return datetime(2026, 10, day, hour, minute, tzinfo=ASTANA_TZ)


async def run_search(svc: Services, query: str, point=MSW, when=None, **kw) -> dict:
    req = SearchRequest(query=query, lat=point[0], lon=point[1], local_time=when or at(14), **kw)
    events: dict = {"order": []}
    async for chunk in stream_search(req, svc):
        lines = chunk.strip().split("\n")
        name = lines[0].removeprefix("event: ")
        data = json.loads(lines[1].removeprefix("data: "))
        events["order"].append(name)
        events[name] = data
    return events
