"""Схемы API (pydantic). Контракт — раздел 12 ТЗ."""
from __future__ import annotations

from datetime import datetime, timedelta, timezone
from typing import Literal

from pydantic import BaseModel, Field, field_validator

TRANSPORTS = ("walking", "bicycle", "scooter", "driving", "taxi", "public_transport")
Transport = Literal["walking", "bicycle", "scooter", "driving", "taxi", "public_transport"]
OpenState = Literal["open", "closed", "unknown"]
ASTANA_TZ = timezone(timedelta(hours=5))


def _rounded(v: float) -> float:
    return round(float(v), 3)


def _aware(dt: datetime) -> datetime:
    return dt if dt.tzinfo else dt.replace(tzinfo=ASTANA_TZ)


class GeoRequest(BaseModel):
    """База запросов с точкой: координаты округляются до 3 знаков (≈110 м) сразу при разборе."""

    lat: float = Field(ge=-90, le=90)
    lon: float = Field(ge=-180, le=180)

    @field_validator("lat", "lon")
    @classmethod
    def _round(cls, v: float) -> float:
        return _rounded(v)


class Scored(BaseModel):
    id: str
    p: float = Field(ge=0, le=1)


class JevFilters(BaseModel):
    intent: str = "find_place"
    category_primary: Scored | None = None
    category_secondary: Scored | None = None
    purchase_item: Scored | None = None
    transport: str | None = None
    max_travel_min: int | None = None
    time_budget_min: int | None = None
    open: str | None = None
    company: str | None = None
    kids_age: str | None = None
    price: str | None = None
    price_max_tenge: int | None = None
    ambience: str | None = None
    min_rating: float | None = None
    novelty: str | None = None
    hard: list[str] = Field(default_factory=list)
    soft: list[str] = Field(default_factory=list)
    event_offset_min: int | None = None
    group_size: int | None = None
    needs_return: bool = False
    raw_text_search: bool = False
    source: Literal["jev", "client", "none", "demo", "agent"] = "jev"


class Chip(BaseModel):
    key: str
    label: str
    kind: Literal["hard", "soft", "info", "warning"] = "info"
    removable: bool = True


QUICK_ICONS = ("home", "work", "family", "friend", "school", "sport", "food", "shop", "star", "heart")


class SavedPlaceIn(BaseModel):
    """Место, сохранённое пользователем («Дом», «Брат»): агент едет туда по имени."""
    name: str = Field(max_length=60)
    address: str | None = Field(default=None, max_length=200)
    lat: float = Field(ge=-90, le=90)
    lon: float = Field(ge=-180, le=180)


class RememberOffer(BaseModel):
    """Агент предлагает запомнить место; приложение сохраняет только после согласия человека."""
    name: str
    icon: str = "star"
    address: str | None = None
    lat: float
    lon: float


class SearchRequest(GeoRequest):
    query: str = Field(min_length=1, max_length=500)
    local_time: datetime
    transport_hint: Transport | None = None
    walk_active: bool = False
    profile_summary: str = Field(default="", max_length=2000)
    profile_affinity: dict[str, float] = Field(default_factory=dict)
    lang: str | None = None
    filters: JevFilters | None = None  # фильтры, правленные клиентом: Jev не вызывается повторно
    saved_places: list[SavedPlaceIn] = Field(default_factory=list, max_length=20)

    @field_validator("local_time")
    @classmethod
    def _tz(cls, v: datetime) -> datetime:
        return _aware(v)


class CandidateOut(BaseModel):
    poi_id: str
    slot: str
    name: str
    category: str | None
    category_name: str | None
    lat: float
    lon: float
    eta_min: int | None
    distance_m: int | None
    transport: str
    open_state: OpenState
    open_until: str | None = None
    rating: float | None = None
    reviews: int | None = None
    address: str | None = None
    role: Literal["destination", "waypoint", "place"] = "destination"


class Evidence(BaseModel):
    """Проверенный факт о месте: позиция меню с ценой, акция, особенность — и откуда он (ссылка)."""
    fact: str
    source: str | None = None


class AnswerItem(BaseModel):
    poi_id: str
    name: str
    category: str | None
    category_name: str | None = None
    lat: float
    lon: float
    address: str | None = None
    eta_min: int | None
    transport: str
    eta_source: Literal["matrix", "estimate", "demo"] = "matrix"
    open_state: OpenState
    open_until: str | None = None
    rating: float | None = None
    reviews: int | None = None
    why: str | None = None
    visit_min: int | None = None
    action: str | None = None
    source: Literal["poi_data", "model_idea"] = "poi_data"
    confirmed: list[str] = Field(default_factory=list)
    unconfirmed: list[str] = Field(default_factory=list)
    notes: list[str] = Field(default_factory=list)
    evidence: list[Evidence] = Field(default_factory=list)


class PlanLeg(BaseModel):
    to: str
    name: str
    purpose: str
    transport: str
    eta_min: int
    lat: float
    lon: float
    open_state: OpenState = "unknown"
    open_until: str | None = None
    arrive_at: str | None = None   # «14:05» — прибытие на эту точку
    leave_at: str | None = None    # уход после visit_min
    visit_min: int | None = None
    distance_m: int | None = None


class Plan(BaseModel):
    legs: list[PlanLeg]
    total_min: int
    rain_exposure_min: int
    notes: list[str] = Field(default_factory=list)
    explanation: str | None = None
    geometry: list[list[float]] = Field(default_factory=list)  # [[lon, lat], ...]
    alternatives: list[dict] = Field(default_factory=list)


class Answer(BaseModel):
    mode: Literal["simple", "plan", "place", "empty"]
    items: list[AnswerItem] = Field(default_factory=list)
    idea: str | None = None
    relaxed: list[str] = Field(default_factory=list)
    plan: Plan | None = None
    note: str | None = None
    data_source: Literal["2gis", "demo"] = "2gis"
    summary: str | None = None            # итог агента одной фразой
    understood: list[str] = Field(default_factory=list)  # как агент понял запрос
    route: dict | None = None             # маршрут к первому месту (RouteOut), если просили «как добраться»
    route_option_id: str | None = None    # выбранный агентом вариант маршрута
    remember: RememberOffer | None = None  # предложение запомнить место (с разрешения)


class PlacePoint(BaseModel):
    """Точка места (цель, промежуточная остановка): публичные координаты, не округляются."""

    lat: float = Field(ge=-90, le=90)
    lon: float = Field(ge=-180, le=180)


class RouteRequest(BaseModel):
    origin: GeoRequest = Field(alias="from")  # позиция пользователя — округляется до ~110 м
    to: PlacePoint
    via: list[PlacePoint] = Field(default_factory=list, max_length=8)
    transport: Transport = "walking"

    model_config = {"populate_by_name": True}


class Maneuver(BaseModel):
    text: str
    icon: str | None = None
    type: str | None = None
    turn_direction: str | None = None
    lat: float
    lon: float
    distance_m: int = 0
    duration_s: int = 0


class RouteStop(BaseModel):
    lat: float
    lon: float
    name: str | None = None


class RouteLeg(BaseModel):
    """Этап варианта: walk — пешком, bus/trolleybus/tram/… — поездка с номерами маршрутов и остановками."""

    kind: str
    names: list[str] = Field(default_factory=list)
    color: str | None = None
    from_name: str | None = None
    to_name: str | None = None
    duration_s: int = 0
    wait_s: int = 0
    distance_m: int = 0
    geometry: list[list[float]] = Field(default_factory=list)
    stops: list[RouteStop] = Field(default_factory=list)


class RouteOption(BaseModel):
    id: str
    label: str | None = None
    total_distance_m: int
    total_duration_s: int
    geometry: list[list[float]]
    maneuvers: list[Maneuver]
    legs: list[RouteLeg] = Field(default_factory=list)
    transfers: int | None = None
    walk_text: str | None = None
    departure: str | None = None          # ближайшее отправление по расписанию, «13:52»
    departure_in_min: int | None = None
    next_departures: list[str] = Field(default_factory=list)  # следующие отправления
    interval_min: int | None = None       # если точного расписания нет — интервал движения


class RouteOut(BaseModel):
    """Основной вариант — в верхних полях (их берёт навигатор), все варианты — в options."""

    transport: str
    total_distance_m: int
    total_duration_s: int
    geometry: list[list[float]]
    maneuvers: list[Maneuver]
    source: Literal["routing", "public_transport", "demo", "estimate"]
    deeplink: str | None = None
    note: str | None = None
    options: list[RouteOption] = Field(default_factory=list)


class DeckRequest(GeoRequest):
    local_time: datetime
    transport: Transport = "walking"
    categories: list[str] = Field(min_length=1, max_length=4)
    extra_areas: list[GeoRequest] = Field(default_factory=list, max_length=3)
    profile_summary: str = Field(default="", max_length=2000)
    lang: str = "ru"

    @field_validator("local_time")
    @classmethod
    def _tz(cls, v: datetime) -> datetime:
        return _aware(v)


class DeckCard(BaseModel):
    slot: str
    poi_id: str
    name: str
    category: str
    category_name: str
    group: str
    lat: float
    lon: float
    eta_min: int | None
    transport: str
    open_state: OpenState
    open_until: str | None = None
    rating: float | None = None
    reviews: int | None = None
    q: float
    k: float
    indoor: bool
    context_chip: str | None = None
    why: str | None = None
    address: str | None = None


class ProfileEvent(BaseModel):
    query: str = Field(default="", max_length=500)
    filters: dict = Field(default_factory=dict)
    shown: list[dict] = Field(default_factory=list, max_length=20)
    action: Literal["selected", "route_started", "route_completed", "closed_no_choice", "reformulated", "walk_finished"]
    selected_category: str | None = None
    local_time: datetime | None = None
    weather: str | None = None
    walk: dict | None = None


class ProfileUpdateRequest(BaseModel):
    event: ProfileEvent
    summary: str = Field(default="", max_length=2000)
    lang: str = "ru"


class ProfileOp(BaseModel):
    op: Literal["add", "reinforce", "weaken", "remove"]
    key: str
    value: str
    explicit: bool
    evidence: str


class NotifyRequest(GeoRequest):
    local_time: datetime
    transport: Transport = "walking"
    trigger: Literal["rain", "cold", "long_walk", "meal", "sunset", "low_battery", "closing"]
    profile_summary: str = Field(default="", max_length=2000)
    profile_affinity: dict[str, float] = Field(default_factory=dict)
    exclude_ids: list[str] = Field(default_factory=list, max_length=20)
    target_category: str | None = None
    target_closes_at: str | None = None
    lang: str = "ru"

    @field_validator("local_time")
    @classmethod
    def _tz(cls, v: datetime) -> datetime:
        return _aware(v)
