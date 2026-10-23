"""Контейнер зависимостей: реальные клиенты или демо-провайдеры (без ключей)."""
from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Any

import httpx

from .catalog import Catalog, load_catalog
from .config import Settings
from .dgis import DgisClient, UsageCounters
from .jev import JevClient, JevSchemaError, JevUnavailable, build_filters, build_questions, unavailable_filters
from .llm import ClaudeService
from .models import JevFilters
from .query_parse import ParsedNumbers
from .weather import WeatherService

log = logging.getLogger(__name__)


@dataclass
class Services:
    settings: Settings
    catalog: Catalog
    places: Any            # DgisClient | FakeDgis
    jev: Any | None        # JevClient | FakeJev | None
    llm: Any | None        # ClaudeService | TemplateLLM | None
    weather: Any           # WeatherService | FakeWeather
    questions: dict
    counters: UsageCounters
    data_source: str       # "2gis" | "demo"
    http: Any = None       # общий httpx-клиент (фото мест)

    async def filters_for(self, state: dict, nums: ParsedNumbers) -> JevFilters:
        """Jev → JevFilters. Jev недоступен — поиск по исходному тексту и чип «Фильтры не определены»."""
        if self.jev is None:
            return unavailable_filters(nums)
        try:
            answers = await self.jev.ask(state, self.questions)
        except (JevUnavailable, JevSchemaError) as exc:
            log.warning("jev unavailable: %s", exc)
            return unavailable_filters(nums)
        source = "demo" if getattr(self.jev, "is_demo", False) else "jev"
        return build_filters(answers, nums, source=source)


def build_services(settings: Settings, http: httpx.AsyncClient) -> Services:
    catalog = load_catalog()
    counters = UsageCounters(settings.dgis_quota_per_service)
    questions = build_questions(catalog)
    if settings.demo_mode:
        from .providers.fake_dgis import FakeDgis
        places = FakeDgis(catalog, counters)
        data_source = "demo"
    else:
        places = DgisClient(http, settings, catalog, counters)
        data_source = "2gis"
    # Каждый сервис подключается своим ключом. Без ключа Jev — классификатор по ключевым словам, без ключа
    # Claude — шаблонные пояснения; так работает и живой 2ГИС без нейросетей.
    if settings.has_jev:
        jev = JevClient(http, settings.jev_base_url, settings.jev_api_key, settings.jev_model, settings.jev_timeout_s)
    else:
        from .providers.fake_jev import FakeJev
        jev = FakeJev(catalog)
    if settings.has_claude:
        llm = ClaudeService(settings, catalog)
    else:
        from .providers.fake_llm import TemplateLLM
        llm = TemplateLLM(catalog)
    weather = WeatherService(http, settings)
    log.info("services: %s", settings.describe())
    return Services(settings, catalog, places, jev, llm, weather, questions, counters, data_source,
                    http=None if settings.demo_mode else http)
