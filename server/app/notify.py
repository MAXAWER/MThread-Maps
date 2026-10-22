"""Кандидаты для контекстных уведомлений (раздел 10): тот же конвейер, фильтры задаёт триггер, Jev не вызывается."""
from __future__ import annotations

import asyncio
import logging

from .geo import search_radius_m
from .jev import TRANSPORT_LABELS
from .models import JevFilters, NotifyRequest
from .search import QuerySpec, evaluate, fetch_places, rank, to_item, travel_times
from .services import Services

log = logging.getLogger(__name__)

TRIGGER_CATEGORIES = {
    "rain": ["coffee", "cafe", "mall", "museum", "bookstore", "gallery", "bakery", "chaikhana", "cinema", "library"],
    "cold": ["coffee", "cafe", "chaikhana", "restaurant", "mall", "bakery", "museum"],
    "long_walk": ["coffee", "bakery", "chaikhana", "cafe", "confectionery", "library"],
    "meal": ["cafe", "canteen", "restaurant", "chaikhana", "fastfood", "bakery"],
    "sunset": ["viewpoint", "embankment", "park", "pedestrian_street"],
    "low_battery": ["coffee", "cafe", "fastfood", "mall"],
    "closing": [],
}


def _pick_categories(req: NotifyRequest, svc: Services) -> list[str]:
    pool = list(TRIGGER_CATEGORIES.get(req.trigger, []))
    if req.trigger == "closing":
        target = svc.catalog.get(req.target_category)
        if target:
            pool = [target.id] + [c.id for c in svc.catalog.categories.values()
                                  if c.group == target.group and c.id != target.id][:3]
    if not pool:
        return []
    order = sorted(pool, key=lambda c: -(req.profile_affinity.get(c, 0.5)))
    return order[:2]


def _template(req: NotifyRequest, name: str, eta: int | None, extra: dict) -> str:
    eta_txt = f"{eta} мин" if eta is not None else "рядом"
    if "«" in name:  # кавычки внутри кавычек по-русски — «ёлочки» снаружи, „лапки“ внутри
        name = name.replace("«", "„").replace("»", "“")
    if req.trigger == "rain":
        t = extra.get("t_rain")
        head = f"По прогнозу дождь через {t} мин." if t is not None else "По прогнозу скоро дождь."
        text = f"{head} Укрыться: «{name}», {eta_txt}"
    elif req.trigger == "cold":
        text = f"Похолодало. Согреться можно в «{name}», {eta_txt}"
    elif req.trigger == "long_walk":
        text = f"Вы гуляете уже долго. Передохнуть: «{name}», {eta_txt}"
    elif req.trigger == "meal":
        text = f"Время поесть: «{name}», {eta_txt}"
    elif req.trigger == "sunset":
        m = extra.get("minutes_to_sunset")
        text = f"Закат через {m} мин — успеете к «{name}»" if m is not None else f"Скоро закат — «{name}», {eta_txt}"
    elif req.trigger == "low_battery":
        text = f"Заряд на исходе: Wi-Fi в «{name}», розетки не подтверждены"
    else:
        text = f"Цель закроется раньше, чем вы дойдёте. Рядом «{name}», {eta_txt}"
    return text if len(text) <= 90 else text[:89].rstrip() + "…"


async def notify_candidates(req: NotifyRequest, svc: Services) -> dict:
    now = req.local_time
    try:
        weather = await asyncio.wait_for(svc.weather.get(req.lat, req.lon, now), timeout=3.0)
    except Exception:
        weather = None
    w = weather or {}
    margin = svc.settings.rain_margin_min
    max_travel = 10.0
    extra: dict = {}
    hard: list[str] = []
    soft: list[str] = []
    if req.trigger == "rain":
        t_rain = w.get("precip_starts_in_min")
        extra["t_rain"] = t_rain
        max_travel = float(max(3, min(15, (t_rain - margin) if t_rain is not None else 15)))
        hard = ["indoor"]
    elif req.trigger == "cold":
        hard = ["indoor"]
    elif req.trigger == "sunset":
        m = w.get("minutes_to_sunset")
        extra["minutes_to_sunset"] = m
        max_travel = float(max(5, min(20, (m - 5) if m is not None else 15)))
    elif req.trigger == "low_battery":
        soft = ["wifi", "sockets"]
    elif req.trigger == "closing":
        max_travel = 15.0
    categories = _pick_categories(req, svc)
    if not categories:
        return {"trigger": req.trigger, "items": [], "text": None, "suggest_plan": False, "data_source": svc.data_source}
    filters = JevFilters(source="none", open="at_arrival", hard=hard, soft=soft, max_travel_min=int(max_travel),
                         category_primary=None)
    radius = search_radius_m(req.transport, max_travel, svc.settings.speeds_kmh)
    specs = [QuerySpec(svc.catalog.categories[c].q, c, 0.8) for c in categories]
    pairs = await fetch_places(svc, specs, req.lat, req.lon, radius)
    pairs = [(p, s) for p, s in pairs if p.id not in set(req.exclude_ids)]
    etas = await travel_times(svc, (req.lat, req.lon), [p for p, _ in pairs][:20], req.transport)
    pairs = [(p, s) for p, s in pairs if p.id in etas]
    evals, _stats = evaluate(pairs, etas, filters, now, svc.catalog, max_travel, strict_hard=False)
    ranked = rank(evals, filters, req.profile_affinity, svc.catalog, max_travel)[:3]
    items = [to_item(ev, svc.catalog, req.transport) for ev in ranked]
    if req.trigger == "low_battery":
        for it in items:
            if "sockets" not in it.unconfirmed:
                it.unconfirmed.append("sockets")
    if not items:
        return {"trigger": req.trigger, "items": [], "text": None, "suggest_plan": req.trigger == "rain",
                "data_source": svc.data_source}
    first = items[0]
    text = None
    if svc.llm is not None:
        text = await svc.llm.notify_text({
            "trigger": req.trigger,
            "weather": {"rain_in_min": w.get("precip_starts_in_min"), "temp_c": w.get("temp_c"),
                        "wind_ms": w.get("wind_ms"), "minutes_to_sunset": w.get("minutes_to_sunset")},
            "transport": TRANSPORT_LABELS.get(req.transport, req.transport),
            "places": [{"name": it.name, "category": it.category_name, "eta_min": it.eta_min,
                        "open_state": it.open_state} for it in items],
        })
    if text:
        text = " ".join(text.split())
        text = text if len(text) <= 90 else text[:89].rstrip() + "…"
    else:
        text = _template(req, first.name, first.eta_min, extra)
    return {"trigger": req.trigger, "items": [i.model_dump() for i in items], "text": text, "suggest_plan": False,
            "data_source": svc.data_source}
