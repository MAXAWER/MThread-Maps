"""Раздел «Для вас»: кандидаты и тексты для колоды (раздел 9 ТЗ).

Сервер ищет места по категориям, считает время в пути и контекст K, пишет «почему».
Окончательное ранжирование (θ ~ Beta, новизна N) и отбор 20 карточек — на устройстве.
"""
from __future__ import annotations

import asyncio
import logging
import math
from datetime import datetime, timedelta

from .catalog import WALK_OUTDOOR, Catalog, Category
from .dgis import DgisError, Place
from .geo import search_radius_m
from .models import DeckCard, DeckRequest
from .prompts import DECK_SLOTS
from .ranking import quality
from .schedule import open_at, open_info
from .search import QuerySpec, fetch_places, open_until_label, travel_times
from .services import Services
from .weather import sun_times, weather_brief

log = logging.getLogger(__name__)

DECK_TRAVEL_MIN = 15
WHY_TEMPLATES = {
    "food": "Хорошее место, чтобы перекусить и согреться по пути",
    "culture": "Немного культуры в прогулке — без долгой дороги",
    "walks": "Пройтись и подышать воздухом недалеко от вас",
    "kids": "Развлечение для всей семьи неподалёку",
    "shopping": "Можно заглянуть по делам и заодно погреться",
    "fun": "Чтобы весело провести время",
    "sport": "Размяться и зарядиться энергией",
    "night": "Если хочется продолжить вечер",
    "services": "Полезное место рядом с вами",
}


def context_fit(cat: Category, weather: dict | None, now: datetime, is_day_off: bool,
                after_sunset: bool) -> tuple[float, str | None]:
    """K по таблице раздела 9: первое сработавшее условие."""
    w = weather or {}
    cold = w.get("feels_like_c") if w.get("feels_like_c") is not None else w.get("temp_c")
    precip = bool(w.get("precip_next_3h") or w.get("precip_now"))
    windy = (w.get("wind_ms") or 0) > 12
    frost = cold is not None and cold < -15
    if precip or frost or windy:
        if precip:
            t = w.get("precip_starts_in_min")
            chip = f"Дождь через {t} мин — крытое место" if t is not None and t <= 120 else "Осадки скоро — крытое место"
        elif frost:
            chip = "Мороз — тёплое место"
        else:
            chip = "Сильный ветер — место под крышей"
        return (1.0, chip) if cat.indoor else (0.2, None)
    if after_sunset:
        if cat.indoor or cat.night:
            return 1.0, ("Вечерняя программа" if cat.night else "Вечер — тёплое место")
        return 0.3, None
    if not is_day_off and 12 <= now.hour < 14:
        if cat.indoor:
            return (1.0, "Время обеда") if cat.is_food else (0.7, None)
        return 0.7, None
    if is_day_off and now.hour < 12:
        if cat.indoor:
            return (1.0, "Утро выходного") if cat.id == "coffee" else (0.6, None)
        if cat.id in WALK_OUTDOOR:
            return 1.0, "Утро выходного — время для прогулки"
        return 0.7, None
    return 0.7, None


async def build_deck(req: DeckRequest, svc: Services) -> dict:
    catalog = svc.catalog
    now = req.local_time
    try:
        weather = await asyncio.wait_for(svc.weather.get(req.lat, req.lon, now), timeout=3.0)
    except Exception as exc:
        log.warning("deck weather unavailable: %s", type(exc).__name__)
        weather = None
    is_holiday = catalog.is_holiday(now.date())
    is_day_off = now.weekday() >= 5 or is_holiday
    sunrise, sunset = sun_times(req.lat, req.lon, now.date())
    after_sunset = bool(sunset and now >= sunset)
    categories = [catalog.categories[c] for c in req.categories if c in catalog.categories][: svc.settings.deck_categories_max]
    radius = search_radius_m(req.transport, DECK_TRAVEL_MIN, svc.settings.speeds_kmh)
    areas = [(req.lat, req.lon)]
    if not svc.settings.dgis_demo_key:  # с платным ключом — ещё до 3 частых районов
        areas += [(a.lat, a.lon) for a in req.extra_areas]
    specs_by_area = [(lat, lon, QuerySpec(c.q, c.id, 1.0)) for lat, lon in areas for c in categories]
    results = await asyncio.gather(*[
        fetch_places(svc, [spec], lat, lon, radius) for lat, lon, spec in specs_by_area
    ], return_exceptions=True)
    seen: set[str] = set()
    places: list[Place] = []
    for res in results:
        if isinstance(res, Exception):
            log.warning("deck places failed: %s", type(res).__name__)
            continue
        for p, _spec in res:
            if p.id not in seen and p.category in catalog.categories:
                seen.add(p.id)
                places.append(p)
    etas = await travel_times(svc, (req.lat, req.lon), places, req.transport) if places else {}
    scored = []
    for p in places:
        cat = catalog.categories[p.category]
        eta, _dist, _src = etas.get(p.id, (None, None, "estimate"))
        info = open_info(p.schedule, now)
        arrival = open_at(p.schedule, now, eta or 0)
        if arrival.state == "closed" and open_at(p.schedule, now, 60).state != "open":
            continue
        k, chip = context_fit(cat, weather, now, is_day_off, after_sunset)
        q = quality(p.rating, p.reviews)
        pre = 0.2 * k + 0.2 * math.exp(-(eta or DECK_TRAVEL_MIN) / 10.0) + 0.15 * q
        scored.append((pre, p, cat, eta, info, arrival, k, chip, q))
    scored.sort(key=lambda x: x[0], reverse=True)
    scored = scored[: len(DECK_SLOTS)]
    cards: list[DeckCard] = []
    for slot, (_pre, p, cat, eta, info, arrival, k, chip, q) in zip(DECK_SLOTS, scored):
        state_info = arrival if arrival.state != "closed" else info
        cards.append(DeckCard(
            slot=slot, poi_id=p.id, name=p.name, category=cat.id, category_name=cat.name_ru, group=cat.group,
            lat=p.lat, lon=p.lon, eta_min=None if eta is None else max(1, round(eta)), transport=req.transport,
            open_state=state_info.state, open_until=open_until_label(state_info), rating=p.rating, reviews=p.reviews,
            q=round(q, 3), k=k, indoor=cat.indoor, context_chip=chip, address=p.address,
        ))
    texts = None
    if cards and svc.llm is not None:
        texts = await svc.llm.deck_texts({
            "context": {"local_time": now.strftime("%H:%M"), "weekday": now.strftime("%A"), "day_off": is_day_off,
                        "weather": weather_brief(weather)},
            "profile": req.profile_summary[:400],
            "cards": [{"slot": c.slot, "name": c.name, "category": c.category_name, "context": c.context_chip,
                       "open_state": c.open_state, "indoor": c.indoor} for c in cards],
        })
    for c in cards:
        why = (texts or {}).get(c.slot)
        if why:
            why = " ".join(why.split())
            c.why = why if len(why) <= 100 else why[:99].rstrip() + "…"
        else:
            c.why = WHY_TEMPLATES.get(c.group, "Интересное место рядом")
    w = weather or {}
    return {
        "generated_at": datetime.now(now.tzinfo).isoformat(timespec="seconds"),
        "weather": {"brief": weather_brief(weather), "precip_next_3h": w.get("precip_next_3h"),
                    "temp_c": w.get("temp_c"), "is_dark": w.get("is_dark")},
        "sun": {"sunrise": sunrise.strftime("%H:%M") if sunrise else None,
                "sunset": sunset.strftime("%H:%M") if sunset else None},
        "is_weekend": now.weekday() >= 5,
        "is_holiday": is_holiday,
        "cards": [c.model_dump() for c in cards],
        "data_source": svc.data_source,
    }
