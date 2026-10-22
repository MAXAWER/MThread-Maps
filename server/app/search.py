"""Простой режим: поиск и ответ 1–3 рекомендациями (раздел 5 ТЗ).

Факты о месте (время в пути, режим работы, рейтинг) подставляет код.
Claude выбирает места только из списка кандидатов и пишет причину, время на посещение и действие.
"""
from __future__ import annotations

import asyncio
import dataclasses
import json
import logging
import re
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from typing import AsyncIterator

from .catalog import Catalog
from .dgis import DgisError, Place
from .geo import haversine_m, search_radius_m
from .jev import NOUL_LABELS, TRANSPORT_LABELS, build_chips, jev_state
from .models import Answer, AnswerItem, CandidateOut, JevFilters, SearchRequest
from .prompts import ANSWER_SLOTS
from .query_parse import detect_lang, parse_numbers
from .ranking import attr_status, bayes_rating, context_bonus, portrait_fit, quality, relevance, search_score
from .schedule import OpenInfo, is_open_late, open_at, open_info, work_time_param
from .services import Services
from .weather import is_bad_weather, weather_brief

log = logging.getLogger(__name__)

DETOUR = 1.3
PLACE_MODE_RADIUS = 30_000
WEEKDAYS_RU = ("понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье")
_OPEN_CLAIM = re.compile(r"\b(открыт\w*|работает сейчас|open now|ашық)\b", re.IGNORECASE)


def sse(event: str, data: dict) -> str:
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False, separators=(',', ':'))}\n\n"


@dataclass
class QuerySpec:
    q: str
    category: str | None
    p: float
    role: str = "destination"


@dataclass
class Evaluated:
    place: Place
    category_p: float
    role: str = "destination"
    eta_min: float | None = None
    distance_m: float | None = None
    eta_source: str = "matrix"
    now_info: OpenInfo | None = None
    arrival_info: OpenInfo | None = None
    q: float = 0.0
    score: float = 0.0
    confirmed: list[str] = field(default_factory=list)
    unconfirmed: list[str] = field(default_factory=list)
    slot: str = ""


@dataclass
class Ctx:
    req: SearchRequest
    svc: Services
    filters: JevFilters
    transport: str
    weather: dict | None
    now: datetime
    lang: str
    timings: dict = field(default_factory=dict)

    @property
    def catalog(self) -> Catalog:
        return self.svc.catalog

    @property
    def origin(self) -> tuple[float, float]:
        return self.req.lat, self.req.lon


def category_label(catalog: Catalog, place: Place) -> str | None:
    cat = catalog.get(place.category)
    if cat:
        return cat.name_ru
    return place.rubrics[0] if place.rubrics else None


def build_queries(filters: JevFilters, query: str, catalog: Catalog) -> list[QuerySpec]:
    """Строка q для 2ГИС собирается кодом из таблицы соответствий (раздел 4)."""
    prim = filters.category_primary
    if filters.raw_text_search or prim is None:
        specs = [QuerySpec(query, None, 0.6)]
        if prim is not None and (cat := catalog.get(prim.id)):
            specs.append(QuerySpec(cat.q, prim.id, prim.p))
        return specs[:2]
    cat = catalog.get(prim.id)
    q = cat.q if cat else prim.id
    wants = set(filters.hard) | set(filters.soft)
    if "halal" in wants:
        q = f"халяль {q}"
    elif "vegetarian" in wants:
        q = f"вегетарианское {q}"
    specs = [QuerySpec(q, prim.id, prim.p)]
    if prim.p < 0.7:
        specs.append(QuerySpec(query, None, 0.5))
    return specs


async def fetch_places(svc: Services, specs: list[QuerySpec], lat: float, lon: float, radius: int,
                       work_time: str | None = None, opened_after=None, page_size: int | None = None
                       ) -> list[tuple[Place, QuerySpec]]:
    if not specs:
        return []
    results = await asyncio.gather(*[
        svc.places.places(q=s.q, lat=lat, lon=lon, radius_m=radius, work_time=work_time,
                          opened_after=opened_after, page_size=page_size)
        for s in specs
    ], return_exceptions=True)
    errors = [r for r in results if isinstance(r, Exception)]
    if errors and len(errors) == len(results):
        raise DgisError(str(errors[0]))
    seen: set[str] = set()
    out: list[tuple[Place, QuerySpec]] = []
    for spec, res in zip(specs, results):
        if isinstance(res, Exception):
            log.warning("places query failed: %s", type(res).__name__)
            continue
        for place in res:
            if place.id in seen:
                continue
            seen.add(place.id)
            if place.category is None and spec.category:
                place = dataclasses.replace(place, category=spec.category)
            out.append((place, spec))
    return out


async def travel_times(svc: Services, origin: tuple[float, float], places: list[Place], transport: str
                       ) -> dict[str, tuple[float, float, str]]:
    """Один запрос к матрице: источник — пользователь, цели — кандидаты."""
    if not places:
        return {}
    points = [origin] + [(p.lat, p.lon) for p in places]
    source = "demo" if svc.data_source == "demo" else "matrix"
    try:
        res = await svc.places.matrix(points, [0], list(range(1, len(points))), transport)
    except DgisError as exc:
        log.warning("matrix failed: %s", exc)
        res, source = {}, "estimate"
    speed_m_min = svc.settings.speeds_kmh.get(transport, 4.5) * 1000 / 60
    out: dict[str, tuple[float, float, str]] = {}
    for i, p in enumerate(places, 1):
        if (0, i) in res:
            dur, dist = res[(0, i)]
            out[p.id] = (dur / 60.0, dist, source)
        else:
            dist = haversine_m(origin[0], origin[1], p.lat, p.lon) * DETOUR
            out[p.id] = (dist / speed_m_min, dist, "estimate")
    return out


def evaluate(pairs: list[tuple[Place, QuerySpec]], etas: dict, filters: JevFilters, now: datetime,
             catalog: Catalog, max_travel: float, strict_hard: bool = True, use_rating: bool = True
             ) -> tuple[list[Evaluated], dict]:
    """Отсев кодом: время в пути, режим работы к прибытию, бюджет времени, рейтинг, жёсткие фильтры."""
    stats = {"too_far": 0, "closed": 0, "budget": 0, "rating": 0, "hard": 0, "kids": 0}
    out: list[Evaluated] = []
    for place, spec in pairs:
        eta_min, dist, src = etas.get(place.id, (None, None, "estimate"))
        if eta_min is not None and eta_min > max_travel:
            stats["too_far"] += 1
            continue
        now_info = open_info(place.schedule, now)
        arrival = open_at(place.schedule, now, eta_min or 0)
        if arrival.state == "closed":
            stats["closed"] += 1
            continue
        arrive_at = now + timedelta(minutes=eta_min or 0)
        req = filters.open
        if (req == "now" and now_info.state == "closed") \
                or (req == "late" and arrival.state == "open" and not is_open_late(arrival, arrive_at)) \
                or (req == "24h" and arrival.state == "open" and not arrival.is_24x7):
            stats["closed"] += 1
            continue
        cat = catalog.get(place.category)
        if filters.company == "kids" and cat is not None and not cat.kids:
            stats["kids"] += 1
            continue
        if filters.time_budget_min:
            visit = cat.visit_min if cat else 30
            need = (eta_min or 0) * (2 if filters.needs_return else 1) + visit
            if need > filters.time_budget_min:
                stats["budget"] += 1
                continue
        if use_rating and filters.min_rating and bayes_rating(place.rating, place.reviews) < filters.min_rating:
            stats["rating"] += 1
            continue
        confirmed: list[str] = []
        unconfirmed: list[str] = []
        failed = False
        for key in filters.hard:
            status = attr_status(place, key, catalog)
            if status == "yes":
                confirmed.append(key)
            elif status == "no" or (strict_hard and key != "indoor"):
                failed = True
                break
            else:
                unconfirmed.append(key)
        if failed:
            stats["hard"] += 1
            continue
        for key in filters.soft:
            status = attr_status(place, key, catalog)
            if status == "yes":
                confirmed.append(key)
            elif status == "unknown":
                unconfirmed.append(key)
        out.append(Evaluated(place=place, category_p=spec.p, role=spec.role, eta_min=eta_min, distance_m=dist,
                             eta_source=src, now_info=now_info, arrival_info=arrival,
                             confirmed=confirmed, unconfirmed=unconfirmed))
    return out, stats


def rank(evals: list[Evaluated], filters: JevFilters, affinity: dict[str, float], catalog: Catalog,
         max_travel: float) -> list[Evaluated]:
    """s = 0.35R + 0.25e^(−t/τ) + 0.20Q + 0.10P + 0.10O; τ — половина лимита времени в пути."""
    tau = max_travel / 2.0
    has_ctx = bool(filters.ambience or filters.price or filters.company)
    for ev in evals:
        ctx = context_bonus(ev.place, catalog, ambience=filters.ambience, price=filters.price,
                            price_max=filters.price_max_tenge, company=filters.company, kids_age=filters.kids_age)
        r = relevance(ev.place, ev.category_p, filters.soft, ctx, catalog, has_ctx)
        ev.q = quality(ev.place.rating, ev.place.reviews)
        p = portrait_fit(ev.place.category, affinity)
        o = 1.0 if ev.arrival_info and ev.arrival_info.state == "open" else 0.5
        t = ev.eta_min if ev.eta_min is not None else max_travel
        ev.score = search_score(r, t, tau, ev.q, p, o)
    return sorted(evals, key=lambda e: e.score, reverse=True)


def open_until_label(info: OpenInfo | None) -> str | None:
    if info is None or info.state != "open":
        return None
    if info.is_24x7:
        return "круглосуточно"
    return info.open_until


def to_candidate(ev: Evaluated, catalog: Catalog, transport: str) -> CandidateOut:
    p = ev.place
    return CandidateOut(
        poi_id=p.id, slot=ev.slot, name=p.name, category=p.category, category_name=category_label(catalog, p),
        lat=p.lat, lon=p.lon, eta_min=None if ev.eta_min is None else max(1, round(ev.eta_min)),
        distance_m=None if ev.distance_m is None else int(ev.distance_m), transport=transport,
        open_state=ev.arrival_info.state if ev.arrival_info else "unknown",
        open_until=open_until_label(ev.arrival_info), rating=p.rating, reviews=p.reviews, address=p.address,
        role=ev.role if ev.role in ("destination", "waypoint") else "destination",
    )


def to_item(ev: Evaluated, catalog: Catalog, transport: str, *, why: str | None = None,
            visit_min: int | None = None, action: str | None = None) -> AnswerItem:
    p = ev.place
    cat = catalog.get(p.category)
    notes: list[str] = []
    state = ev.arrival_info.state if ev.arrival_info else "unknown"
    if ev.now_info and ev.now_info.state == "closed" and state == "open":
        notes.append("Откроется к вашему приходу")
    if state == "unknown":
        notes.append("Режим работы: нет данных")
    if ev.eta_source == "estimate":
        notes.append("Время в пути оценено приблизительно")
    if why and state == "unknown" and _OPEN_CLAIM.search(why):
        why = None  # не утверждаем «открыто» при неизвестном режиме
    return AnswerItem(
        poi_id=p.id, name=p.name, category=p.category, category_name=category_label(catalog, p),
        lat=p.lat, lon=p.lon, address=p.address,
        eta_min=None if ev.eta_min is None else max(1, round(ev.eta_min)), transport=transport,
        eta_source=ev.eta_source if ev.eta_source in ("matrix", "estimate", "demo") else "estimate",
        open_state=state, open_until=open_until_label(ev.arrival_info), rating=p.rating, reviews=p.reviews,
        why=why, visit_min=visit_min if visit_min is not None else (cat.visit_min if cat else None), action=action,
        source="poi_data", confirmed=ev.confirmed, unconfirmed=ev.unconfirmed, notes=notes,
    )


def filters_for_prompt(f: JevFilters, catalog: Catalog) -> dict:
    cat = catalog.get(f.category_primary.id) if f.category_primary else None
    return {
        "intent": f.intent,
        "category": cat.name_ru if cat else None,
        "transport": TRANSPORT_LABELS.get(f.transport or "", f.transport),
        "max_travel_min": f.max_travel_min,
        "time_budget_min": f.time_budget_min,
        "needs_return_trip": f.needs_return,
        "open": f.open,
        "company": f.company,
        "kids_age": f.kids_age,
        "group_size": f.group_size,
        "price": f.price,
        "price_max_tenge": f.price_max_tenge,
        "ambience": f.ambience,
        "required": [NOUL_LABELS.get(k, k) for k in f.hard],
        "wanted": [NOUL_LABELS.get(k, k) for k in f.soft],
    }


def llm_candidate(ev: Evaluated, catalog: Catalog) -> dict:
    p = ev.place
    cat = catalog.get(p.category)
    info = ev.arrival_info
    return {
        "candidate": ev.slot,
        "code_rank": ANSWER_SLOTS.index(ev.slot) + 1 if ev.slot in ANSWER_SLOTS else None,
        "name": p.name,
        "category": category_label(catalog, p),
        "rubrics": p.rubrics[:4],
        "address": p.address,
        "eta_min": None if ev.eta_min is None else max(1, round(ev.eta_min)),
        "distance_m": None if ev.distance_m is None else int(round(ev.distance_m, -1)),
        "open_state": info.state if info else "unknown",
        "open_until": open_until_label(info),
        "closes_in_min_after_arrival": info.closes_in_min if info else None,
        "opens_at": info.opens_at if info else None,
        "rating": p.rating,
        "reviews": p.reviews,
        "avg_check_tenge": p.avg_check,
        "attributes": p.attribute_labels[:16],
        "confirmed": [NOUL_LABELS.get(k, k) for k in ev.confirmed],
        "unconfirmed": [NOUL_LABELS.get(k, k) for k in ev.unconfirmed],
        "typical_visit_min": cat.visit_min if cat else None,
        "indoor": cat.indoor if cat else None,
        "chain": None if p.branch_count is None else p.branch_count > 3,
        "has_phone": bool(p.phone),
    }


def context_for_prompt(ctx: Ctx) -> dict:
    w = ctx.weather or {}
    return {
        "local_time": ctx.now.strftime("%H:%M"),
        "weekday": WEEKDAYS_RU[ctx.now.weekday()],
        "holiday": ctx.catalog.is_holiday(ctx.now.date()),
        "weather": weather_brief(ctx.weather),
        "temp_c": w.get("temp_c"),
        "feels_like_c": w.get("feels_like_c"),
        "wind_ms": w.get("wind_ms"),
        "precip_now": w.get("precip_now"),
        "precip_starts_in_min": w.get("precip_starts_in_min"),
        "minutes_to_sunset": w.get("minutes_to_sunset"),
        "is_dark": w.get("is_dark"),
        "walk_active": ctx.req.walk_active,
        "transport": TRANSPORT_LABELS.get(ctx.transport, ctx.transport),
    }


def _trim(text: str | None, limit: int) -> str | None:
    if not text:
        return None
    text = " ".join(str(text).split())
    return text if len(text) <= limit else text[: limit - 1].rstrip() + "…"


def apply_llm_answer(result: dict, by_slot: dict[str, Evaluated], ctx: Ctx) -> tuple[list[AnswerItem], str | None]:
    """Проверка ответа модели кодом: только кандидаты из списка, ≤ 3, длины строк, время на посещение."""
    items: list[AnswerItem] = []
    seen: set[str] = set()
    for it in (result.get("items") or [])[:3]:
        slot = it.get("candidate")
        if slot not in by_slot or slot in seen:
            continue
        seen.add(slot)
        ev = by_slot[slot]
        cat = ctx.catalog.get(ev.place.category)
        try:
            visit = int(it.get("visit_min") or (cat.visit_min if cat else 30))
        except (TypeError, ValueError):
            visit = cat.visit_min if cat else 30
        visit = max(5, min(visit, 240))
        f = ctx.filters
        if f.time_budget_min and ev.eta_min is not None:
            room = f.time_budget_min - ev.eta_min * (2 if f.needs_return else 1)
            visit = max(5, min(visit, int(room)))
        items.append(to_item(ev, ctx.catalog, ctx.transport, why=_trim(it.get("why"), 320), visit_min=visit,
                             action=_trim(it.get("action"), 180)))
    return items, _trim(result.get("idea"), 220)


RELAX_LABELS = {
    "radius": "Радиус поиска увеличен в 1,5 раза",
    "hard": "Обязательные признаки стали желательными",
    "rating": "Ограничение по рейтингу снято",
}


def empty_message(stats: dict, relaxed: list[str], radius_m: int | None = None) -> str:
    if not any(stats.values()):
        where = f" в радиусе {radius_m} м" if radius_m else ""
        return f"Ничего не нашлось: рядом{where} нет мест по запросу."
    reasons = []
    if stats.get("closed"):
        reasons.append("подходящие места в это время закрыты")
    if stats.get("too_far"):
        reasons.append("подходящие места дальше лимита времени в пути")
    if stats.get("budget"):
        reasons.append("не укладываемся в ваше время")
    if stats.get("hard"):
        reasons.append("у мест нет нужных подтверждённых признаков")
    head = "Ничего не нашлось"
    if reasons:
        head += ": " + "; ".join(reasons)
    return head + "."


async def simple_mode(ctx: Ctx) -> AsyncIterator[tuple[str, dict]]:
    svc, f = ctx.svc, ctx.filters
    max_travel = float(f.max_travel_min or svc.settings.default_max_travel_min)
    radius = search_radius_m(ctx.transport, max_travel, svc.settings.speeds_kmh)
    work_time = work_time_param(f.open, ctx.now, int(min(max_travel, 15)))
    opened_after = (ctx.now - timedelta(days=180)).date() if f.novelty == "new_places" else None
    specs = build_queries(f, ctx.req.query, ctx.catalog)
    relaxed: list[str] = []

    t = time.perf_counter()
    pairs = await fetch_places(svc, specs, ctx.req.lat, ctx.req.lon, radius, work_time, opened_after)
    ctx.timings["places_ms"] = int((time.perf_counter() - t) * 1000)
    t = time.perf_counter()
    etas = await travel_times(svc, ctx.origin, [p for p, _ in pairs][:20], ctx.transport)
    ctx.timings["matrix_ms"] = int((time.perf_counter() - t) * 1000)
    pairs = [(p, s) for p, s in pairs if p.id in etas]
    evals, stats = evaluate(pairs, etas, f, ctx.now, ctx.catalog, max_travel)

    if not evals:
        # Нет кандидатов → радиус × 1,5 один раз (новый запрос к Places и матрице).
        radius = int(radius * 1.5)
        max_travel *= 1.5
        relaxed.append(RELAX_LABELS["radius"])
        more = await fetch_places(svc, specs, ctx.req.lat, ctx.req.lon, radius, work_time, opened_after)
        known = {p.id for p, _ in pairs}
        fresh = [(p, s) for p, s in more if p.id not in known]
        if fresh:
            etas.update(await travel_times(svc, ctx.origin, [p for p, _ in fresh][:20], ctx.transport))
            pairs += [(p, s) for p, s in fresh if p.id in etas]
        evals, stats = evaluate(pairs, etas, f, ctx.now, ctx.catalog, max_travel)
    if not evals and pairs and (f.hard or f.min_rating):
        evals, stats2 = evaluate(pairs, etas, f, ctx.now, ctx.catalog, max_travel, strict_hard=False, use_rating=False)
        if f.hard:
            relaxed.append(RELAX_LABELS["hard"])
        if f.min_rating:
            relaxed.append(RELAX_LABELS["rating"])
        stats = stats if not evals else stats2

    if not evals:
        yield "candidates", {"items": [], "data_source": svc.data_source}
        yield "answer", Answer(mode="empty", relaxed=relaxed, note=empty_message(stats, relaxed, radius),
                               data_source=svc.data_source).model_dump()
        return

    ranked = rank(evals, f, ctx.req.profile_affinity, ctx.catalog, max_travel)[:8]
    for slot, ev in zip(ANSWER_SLOTS, ranked):
        ev.slot = slot
    yield "candidates", {"items": [to_candidate(ev, ctx.catalog, ctx.transport).model_dump() for ev in ranked],
                         "data_source": svc.data_source}

    t = time.perf_counter()
    result = None
    if svc.llm is not None:
        payload = {
            "query": ctx.req.query,
            "language": ctx.lang,
            "filters": filters_for_prompt(f, ctx.catalog),
            "context": context_for_prompt(ctx),
            "profile": ctx.req.profile_summary[:600],
            "candidates": [llm_candidate(ev, ctx.catalog) for ev in ranked],
        }
        result = await svc.llm.answer(payload)
    ctx.timings["llm_ms"] = int((time.perf_counter() - t) * 1000)

    note = None
    items: list[AnswerItem] = []
    idea = None
    if result is not None:
        items, idea = apply_llm_answer(result, {ev.slot: ev for ev in ranked}, ctx)
    if not items:
        items = [to_item(ev, ctx.catalog, ctx.transport) for ev in ranked[:3]]
        note = "Подборка без пояснений: модель не ответила вовремя." if result is None else None
    if f.ambience:
        for it in items:
            it.notes.append("Атмосфера — оценка модели")
    yield "answer", Answer(mode="simple", items=items, idea=idea, relaxed=relaxed, note=note,
                           data_source=svc.data_source).model_dump()


async def place_mode(ctx: Ctx) -> AsyncIterator[tuple[str, dict]]:
    """navigate_to / place_info: карточка места и маршрут без рекомендаций."""
    svc = ctx.svc
    t = time.perf_counter()
    pairs = await fetch_places(svc, [QuerySpec(ctx.req.query, None, 1.0, "place")], ctx.req.lat, ctx.req.lon,
                               PLACE_MODE_RADIUS, page_size=5)
    ctx.timings["places_ms"] = int((time.perf_counter() - t) * 1000)
    if not pairs:
        geo = await svc.places.geocode(ctx.req.query, ctx.req.lat, ctx.req.lon) if hasattr(svc.places, "geocode") else []
        if geo:
            g = geo[0]
            place = Place(id=f"geo:{g.get('id') or 'point'}", name=g.get("title") or ctx.req.query,
                          lat=float(g["lat"]), lon=float(g["lon"]), address=g.get("title"))
            pairs = [(place, QuerySpec(ctx.req.query, None, 1.0, "place"))]
    if not pairs:
        yield "candidates", {"items": [], "data_source": svc.data_source}
        yield "answer", Answer(mode="empty", note="Не нашли такое место или адрес в Астане.",
                               data_source=svc.data_source).model_dump()
        return
    places = [p for p, _ in pairs][:3]
    etas = await travel_times(svc, ctx.origin, places, ctx.transport)
    evals = []
    for i, p in enumerate(places):
        eta, dist, src = etas.get(p.id, (None, None, "estimate"))
        ev = Evaluated(place=p, category_p=1.0, role="place", eta_min=eta, distance_m=dist, eta_source=src,
                       now_info=open_info(p.schedule, ctx.now), arrival_info=open_at(p.schedule, ctx.now, eta or 0))
        ev.slot = ANSWER_SLOTS[i]
        evals.append(ev)
    yield "candidates", {"items": [to_candidate(ev, ctx.catalog, ctx.transport).model_dump() for ev in evals],
                         "data_source": svc.data_source}
    items = []
    for ev in evals:
        item = to_item(ev, ctx.catalog, ctx.transport)
        if ev.arrival_info and ev.arrival_info.state == "closed":
            item.notes.append(f"Сейчас закрыто{', откроется ' + ev.arrival_info.opens_at if ev.arrival_info.opens_at else ''}")
        items.append(item)
    yield "answer", Answer(mode="place", items=items, data_source=svc.data_source).model_dump()


def is_plan_mode(f: JevFilters) -> bool:
    return f.intent == "plan_route" or f.purchase_item is not None or f.category_secondary is not None


async def resolve_filters(req: SearchRequest, svc: Services, weather: dict | None, lang: str) -> JevFilters:
    nums = parse_numbers(req.query)
    if req.filters is not None:
        f = req.filters.model_copy(deep=True)
        f.source = "client"
        return f
    state = jev_state(req.query, req.local_time, weather, req.walk_active, req.transport_hint,
                      req.profile_summary, svc.catalog.is_holiday(req.local_time.date()))
    f = await svc.filters_for(state, nums)
    if is_bad_weather(weather) and "indoor" not in f.hard and "indoor" not in f.soft and f.intent == "find_place":
        f.soft.append("indoor")  # триггер погоды: крытые места получают мягкий бонус
    return f


async def stream_search(req: SearchRequest, svc: Services) -> AsyncIterator[str]:
    from .planner import plan_mode  # циклический импорт: planner использует функции этого модуля

    request_id = uuid.uuid4().hex[:12]
    t0 = time.perf_counter()
    timings: dict[str, int] = {}
    lang = req.lang or detect_lang(req.query)
    weather_task = asyncio.create_task(svc.weather.get(req.lat, req.lon, req.local_time))
    try:
        # Погода из кэша приходит сразу; холодный запрос не задерживает чипы Jev дольше 0,6 с.
        weather = await _await_weather(weather_task, 0.6)
        t = time.perf_counter()
        f = await resolve_filters(req, svc, weather, lang)
        timings["filters_ms"] = int((time.perf_counter() - t) * 1000)
        transport = f.transport or req.transport_hint or "walking"
        chips = build_chips(f, svc.catalog)
        yield sse("filters", {"filters": f.model_dump(), "chips": [c.model_dump() for c in chips],
                              "transport": transport})
        if weather is None:
            weather = await _await_weather(weather_task, 2.5)
        timings["weather_ms"] = int((time.perf_counter() - t0) * 1000)
        ctx = Ctx(req=req, svc=svc, filters=f, transport=transport, weather=weather, now=req.local_time,
                  lang=lang, timings=timings)
        if f.intent in ("navigate_to", "place_info"):
            pipeline = place_mode(ctx)
        elif is_plan_mode(f):
            pipeline = plan_mode(ctx)
        else:
            pipeline = simple_mode(ctx)
        async for event, data in pipeline:
            yield sse(event, data)
    except DgisError as exc:
        log.warning("search %s: 2gis error: %s", request_id, exc)
        yield sse("error", {"code": "places_unavailable", "message": "Справочник 2ГИС сейчас недоступен. Попробуйте ещё раз."})
    except Exception:
        log.exception("search %s failed", request_id)
        yield sse("error", {"code": "internal", "message": "Что-то пошло не так. Попробуйте ещё раз."})
    finally:
        if not weather_task.done():
            weather_task.cancel()
    timings["total_ms"] = int((time.perf_counter() - t0) * 1000)
    log.info("search %s done %s", request_id, timings)
    brief = weather_brief(weather_task.result()) if weather_task.done() and not weather_task.cancelled() \
        and weather_task.exception() is None else None
    yield sse("done", {"request_id": request_id, "timings": timings, "weather": brief})


async def _await_weather(task: asyncio.Task, timeout: float) -> dict | None:
    """Погода не должна останавливать поиск: таймаут или ошибка — работаем без неё."""
    try:
        return await asyncio.wait_for(asyncio.shield(task), timeout=timeout)
    except asyncio.TimeoutError:
        return None
    except Exception as exc:
        log.warning("weather unavailable: %s", type(exc).__name__)
        return None
