"""Настройки сервера из переменных окружения (ключи — из Secret Manager через env)."""
from __future__ import annotations

import json
import logging
import os
from dataclasses import dataclass, field

log = logging.getLogger(__name__)

DEFAULT_SPEEDS_KMH = {
    "walking": 4.5,
    "bicycle": 15.0,
    "scooter": 12.0,
    "driving": 25.0,
    "taxi": 25.0,
    "public_transport": 15.0,
}

# Jev через OpenRouter: тот же /v1/systemone, ключ OpenRouter в Authorization, модель — в пространстве typesafe/.
OPENROUTER_BASE_URL = "https://openrouter.ai/api"
OPENROUTER_JEV_MODEL = "typesafe/jev-1.13"

DEFAULT_PLACE_FIELDS = (
    "items.point,items.full_address_name,items.rubrics,items.schedule,"
    "items.reviews,items.org,items.attribute_groups,items.contact_groups"
)


def _str(name: str, default: str = "") -> str:
    return os.environ.get(name, default).strip()


def _bool(name: str, default: bool = False) -> bool:
    raw = os.environ.get(name)
    if raw is None or raw.strip() == "":
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


def _int(name: str, default: int) -> int:
    try:
        return int(os.environ.get(name, default))
    except ValueError:
        return default


def _float(name: str, default: float) -> float:
    try:
        return float(os.environ.get(name, default))
    except ValueError:
        return default


@dataclass(frozen=True)
class Settings:
    # Режим: "auto" — демо-данные, если нет ключа 2ГИС; "1" — всегда демо; "0" — только реальные API.
    demo_mode: bool = False
    dgis_key: str = ""
    dgis_catalog_url: str = "https://catalog.api.2gis.com"
    dgis_routing_url: str = "https://routing.api.2gis.com"
    dgis_place_fields: str = DEFAULT_PLACE_FIELDS
    dgis_demo_key: bool = True            # демо-ключ: ≤ 10 объектов на страницу, ≤ 5 страниц
    dgis_quota_per_service: int = 1000    # лимит демо-ключа на каждый сервис
    jev_base_url: str = "https://api.typesafe.ai"
    jev_api_key: str = ""
    jev_model: str = "jev-1.13.0"
    jev_timeout_s: float = 1.5
    jev_via: str = "typesafe"             # typesafe | openrouter — куда идут запросы Jev
    anthropic_api_key: str = ""
    # Адрес Claude API задаётся явно: SDK иначе возьмёт ANTHROPIC_BASE_URL из окружения процесса.
    anthropic_base_url: str = "https://api.anthropic.com"
    answer_model: str = "claude-haiku-5-5"
    profile_model: str = "claude-haiku-5-5"
    text_model: str = "claude-haiku-5-5"
    planner_model: str = "claude-sonnet-5-5"
    planner_effort: str = "medium"
    planner_fallback: bool = True
    # Агент подбора на Sonnet 5.5: сам ищет, сравнивает и проверяет места и варианты поездки.
    agent_enabled: bool = True
    agent_model: str = "claude-sonnet-5-5"
    agent_effort: str = "high"
    agent_max_tool_calls: int = 10
    agent_web: bool = True
    agent_web_max_uses: int = 8
    agent_timeout_s: float = 170.0
    answer_effort: str = "medium"
    answer_timeout_s: float = 15.0
    planner_timeout_s: float = 10.0
    openweather_key: str = ""
    weather_override: dict | None = None
    daily_search_limit: int = 60
    debug_logs: bool = False
    speeds_kmh: dict = field(default_factory=lambda: dict(DEFAULT_SPEEDS_KMH))
    default_max_travel_min: int = 20
    purchase_minutes: int = 5
    rain_margin_min: int = 3
    matrix_max_targets: int = 10          # демо-ключ: матрица не больше 10 × 10
    matrix_max_sources: int = 10
    places_page_size: int = 10
    deck_categories_max: int = 4

    @property
    def has_dgis(self) -> bool:
        return bool(self.dgis_key)

    @property
    def has_jev(self) -> bool:
        return bool(self.jev_api_key)

    @property
    def has_claude(self) -> bool:
        return bool(self.anthropic_api_key)

    @classmethod
    def from_env(cls) -> "Settings":
        dgis_key = _str("DGIS_API_KEY")
        demo_raw = _str("DEMO_MODE", "auto").lower()
        if demo_raw in {"1", "true", "yes", "on"}:
            demo = True
        elif demo_raw in {"0", "false", "no", "off"}:
            demo = False
        else:
            demo = not dgis_key
        override = None
        raw_override = _str("WEATHER_OVERRIDE")
        if raw_override:
            try:
                override = json.loads(raw_override)
            except json.JSONDecodeError:
                log.warning("WEATHER_OVERRIDE is not valid JSON; ignored")
        speeds = dict(DEFAULT_SPEEDS_KMH)
        raw_speeds = _str("SPEEDS_KMH")
        if raw_speeds:
            try:
                speeds.update({k: float(v) for k, v in json.loads(raw_speeds).items()})
            except (json.JSONDecodeError, ValueError, AttributeError):
                log.warning("SPEEDS_KMH is not valid JSON; defaults used")
        # Jev: прямой ключ TypeSafe, а без него — OpenRouter (тот же /v1/systemone, модель в пространстве typesafe/).
        jev_key, jev_via = _str("JEV_API_KEY"), "typesafe"
        jev_base, jev_model = _str("JEV_BASE_URL", cls.jev_base_url), _str("JEV_MODEL", cls.jev_model)
        openrouter_key = _str("OPENROUTER_API_KEY")
        if not jev_key and openrouter_key:
            # JEV_BASE_URL и JEV_MODEL относятся к TypeSafe и здесь не читаются.
            jev_key, jev_via = openrouter_key, "openrouter"
            jev_base, jev_model = OPENROUTER_BASE_URL, _str("OPENROUTER_JEV_MODEL", OPENROUTER_JEV_MODEL)
        return cls(
            demo_mode=demo,
            dgis_key=dgis_key,
            dgis_catalog_url=_str("DGIS_CATALOG_URL", cls.dgis_catalog_url),
            dgis_routing_url=_str("DGIS_ROUTING_URL", cls.dgis_routing_url),
            dgis_place_fields=_str("DGIS_PLACE_FIELDS", DEFAULT_PLACE_FIELDS),
            dgis_demo_key=_bool("DGIS_DEMO_KEY", True),
            dgis_quota_per_service=_int("DGIS_QUOTA_PER_SERVICE", 1000),
            jev_base_url=jev_base.rstrip("/"),
            jev_api_key=jev_key,
            jev_model=jev_model,
            jev_timeout_s=_float("JEV_TIMEOUT_S", 1.5),
            jev_via=jev_via,
            anthropic_api_key=_str("ANTHROPIC_API_KEY"),
            anthropic_base_url=_str("CLAUDE_API_URL", cls.anthropic_base_url).rstrip("/"),
            answer_model=_str("ANSWER_MODEL", cls.answer_model),
            profile_model=_str("PROFILE_MODEL", cls.profile_model),
            text_model=_str("TEXT_MODEL", cls.text_model),
            planner_model=_str("PLANNER_MODEL", cls.planner_model),
            planner_effort=_str("PLANNER_EFFORT", "medium"),
            planner_fallback=_bool("PLANNER_FALLBACK", True),
            agent_enabled=_bool("AGENT_ENABLED", True),
            agent_model=_str("AGENT_MODEL", cls.agent_model),
            agent_effort=_str("AGENT_EFFORT", "high"),
            agent_max_tool_calls=_int("AGENT_MAX_TOOL_CALLS", 10),
            agent_web=os.getenv("AGENT_WEB", "1") != "0",
            agent_web_max_uses=_int("AGENT_WEB_MAX_USES", 8),
            agent_timeout_s=_float("AGENT_TIMEOUT_S", 170.0),
            answer_effort=_str("ANSWER_EFFORT", "medium"),
            answer_timeout_s=_float("ANSWER_TIMEOUT_S", 15.0),
            planner_timeout_s=_float("PLANNER_TIMEOUT_S", 10.0),
            openweather_key=_str("OPENWEATHER_API_KEY"),
            weather_override=override,
            daily_search_limit=_int("DAILY_SEARCH_LIMIT", 60),
            debug_logs=_bool("DEBUG_LOGS", False),
            speeds_kmh=speeds,
            default_max_travel_min=_int("DEFAULT_MAX_TRAVEL_MIN", 20),
            purchase_minutes=_int("PURCHASE_MINUTES", 5),
            rain_margin_min=_int("RAIN_MARGIN_MIN", 3),
            matrix_max_targets=_int("MATRIX_MAX_TARGETS", 10 if _bool("DGIS_DEMO_KEY", True) else 25),
            matrix_max_sources=_int("MATRIX_MAX_SOURCES", 10 if _bool("DGIS_DEMO_KEY", True) else 25),
            places_page_size=_int("PLACES_PAGE_SIZE", 10),
            deck_categories_max=_int("DECK_CATEGORIES_MAX", 4),
        )

    def describe(self) -> dict:
        """Что настроено — без значений ключей (для /v1/status и логов старта)."""
        return {
            "demo_mode": self.demo_mode,
            "dgis": self.has_dgis,
            "jev": self.has_jev,
            "jev_via": self.jev_via if self.has_jev else None,
            "claude": self.has_claude,
            "openweather": bool(self.openweather_key),
            "weather_override": self.weather_override is not None,
            "models": {
                "answer": self.answer_model,
                "profile": self.profile_model,
                "text": self.text_model,
                "planner": self.planner_model,
            },
            "jev_model": self.jev_model,
        }
