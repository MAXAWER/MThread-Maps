"""Режим плана: маршрут «промежуточные точки → цель» с ограничением по дождю (раздел 6 ТЗ).

Оптимум считает код полным перебором. Claude Sonnet 5.5 переформулирует поиск,
когда кандидатов нет, и объясняет итог.
"""
from __future__ import annotations

import asyncio
import logging
import re
import time
from dataclasses import dataclass, field
from datetime import timedelta
from itertools import permutations, product
from typing import AsyncIterator

from .dgis import DgisError, Place
from .geo import search_radius_m
from .models import Answer, AnswerItem, JevFilters, Plan, PlanLeg, Scored
from .schedule import open_at, open_info
from .search import (Ctx, Evaluated, QuerySpec, category_label, fetch_places, open_until_label, simple_mode,
                     to_candidate, to_item)

log = logging.getLogger(__name__)

PER_GROUP = 6
_RAIN_WORDS = re.compile(r"дожд|ливен|ливн|снег|осадк|rain|snow|жаңбыр|қар", re.IGNORECASE)


@dataclass
class Group:
    role: str                 # purchase | secondary
    label: str                # «купить зонт», «аптека»
    queries: list[QuerySpec]
    solves_rain: bool = False
    candidates: list[Place] = field(default_factory=list)
    used_query: str | None = None


@dataclass
class PlanOption:
    seq: list[Place]          # промежуточные точки по порядку + цель последней (если есть)
    purposes: list[str]
    legs_min: list[float]
    arrivals_min: list[float]
    total_min: float
    rain_exposure_min: float
    feasible: bool

    @property
    def key(self) -> tuple:
        return tuple(p.id for p in self.seq)


def optimize(groups: list[Group], dests: list[Place], times: dict[tuple[str, str], float], *,
             t_rain: float | None, purchase_min: float, margin: float, now, catalog,
             dest_purpose: str) -> list[PlanOption]:
    """Полный перебор: порядки групп (до 4 → 24 варианта) × кандидаты × цели."""
    options: list[PlanOption] = []
    dest_list: list[Place | None] = list(dests) if dests else [None]
    for perm in permutations(range(len(groups))):
        pools = [groups[g].candidates[:PER_GROUP if len(groups) <= 2 else 4] for g in perm]
        for combo in product(*pools):
            for dest in dest_list:
                seq = list(combo) + ([dest] if dest else [])
                if len({p.id for p in seq}) != len(seq):
                    continue
                opt = _simulate(seq, [groups[g] for g in perm], dest is not None, times, t_rain=t_rain,
                                purchase_min=purchase_min, margin=margin, now=now, catalog=catalog,
                                dest_purpose=dest_purpose)
                if opt:
                    options.append(opt)
    feasible = sorted((o for o in options if o.feasible), key=lambda o: o.total_min)
    if feasible:
        ordered = feasible
    else:  # допустимых нет: сначала минимум времени под дождём, потом общее время
        ordered = sorted(options, key=lambda o: (o.rain_exposure_min, o.total_min))
    out: list[PlanOption] = []
    seen_dest: set[str] = set()
    for o in ordered:
        sig = o.seq[-1].id
        if out and sig in seen_dest and len(ordered) > 3:
            continue
        seen_dest.add(sig)
        out.append(o)
        if len(out) == 3:
            break
    return out


def _simulate(seq: list[Place], groups: list[Group], has_dest: bool, times: dict, *, t_rain, purchase_min,
              margin, now, catalog, dest_purpose: str) -> PlanOption | None:
    t, prev = 0.0, "user"
    exposure = 0.0
    sheltered = False
    feasible = True
    legs, arrivals, purposes = [], [], []
    for idx, place in enumerate(seq):
        leg = times.get((prev, place.id))
        if leg is None:
            return None
        start, end = t, t + leg
        if t_rain is not None and not sheltered:
            exposure += max(0.0, end - max(start, t_rain))
        if open_at(place.schedule, now, end).state == "closed":
            return None
        legs.append(leg)
        arrivals.append(end)
        t = end
        is_waypoint = idx < len(groups)
        if is_waypoint:
            group = groups[idx]
            purposes.append(group.label)
            if group.solves_rain:
                if t_rain is not None and end > t_rain - margin:
                    feasible = False
                sheltered = True
            t += purchase_min
        else:
            purposes.append(dest_purpose)
            cat = catalog.get(place.category)
            if t_rain is not None and not sheltered and (cat is None or not cat.indoor or end > t_rain - margin):
                feasible = False
        prev = place.id
    if not has_dest and t_rain is not None and not sheltered and seq:
        last_end = arrivals[-1]
        if last_end > t_rain - margin:
            feasible = False
    total = t if has_dest else t - (purchase_min if groups else 0)
    return PlanOption(seq=seq, purposes=purposes, legs_min=legs, arrivals_min=arrivals, total_min=total,
                      rain_exposure_min=exposure, feasible=feasible)


def rain_minutes(ctx: Ctx) -> tuple[float | None, str | None]:
    """t_rain: слова пользователя («через 30 минут дождь») важнее прогноза."""
    f = ctx.filters
    if f.event_offset_min is not None and _RAIN_WORDS.search(ctx.req.query):
        return float(f.event_offset_min), f"По вашим словам дождь через {f.event_offset_min} мин"
    w = ctx.weather or {}
    if w.get("precip_starts_in_min") is not None and w.get("precip_starts_in_min") <= 90:
        return float(w["precip_starts_in_min"]), f"Осадки по прогнозу через {w['precip_starts_in_min']} мин"
    return None, None


def build_groups(ctx: Ctx) -> tuple[list[Group], QuerySpec | None, str]:
    f, catalog = ctx.filters, ctx.catalog
    groups: list[Group] = []
    if f.purchase_item:
        item = catalog.purchase_items.get(f.purchase_item.id)
        if item:
            groups.append(Group(
                role="purchase", label=f"купить: {item.name_ru}", solves_rain=item.solves_rain,
                queries=[QuerySpec(q, item.categories[min(i, len(item.categories) - 1)] if item.categories else None,
                                   f.purchase_item.p, "waypoint") for i, q in enumerate(item.queries)],
            ))
    if f.category_secondary:
        cat = catalog.get(f.category_secondary.id)
        if cat:
            groups.append(Group(role="secondary", label=cat.name_ru.lower(),
                                queries=[QuerySpec(cat.q, cat.id, f.category_secondary.p, "waypoint")]))
    dest_spec, dest_purpose = None, "цель"
    if f.category_primary:
        cat = catalog.get(f.category_primary.id)
        if cat:
            dest_spec = QuerySpec(cat.q, cat.id, f.category_primary.p, "destination")
            dest_purpose = cat.name_ru.lower()
    return groups, dest_spec, dest_purpose


async def _search_group(ctx: Ctx, group: Group, radius: int, first_only: bool = False) -> None:
    for spec in group.queries[:1] if first_only else group.queries:
        pairs = await fetch_places(ctx.svc, [spec], ctx.req.lat, ctx.req.lon, radius)
        open_ok = [p for p, _ in pairs if open_info(p.schedule, ctx.now).state != "closed"
                   or open_at(p.schedule, ctx.now, 15).state != "closed"]
        if open_ok:
            group.candidates = open_ok[:PER_GROUP]
            group.used_query = spec.q
            return


async def _times(ctx: Ctx, places: list[Place], sources_from: list[Place]) -> dict[tuple[str, str], float]:
    """Один запрос к матрице: точки = пользователь + W + D; sources = пользователь и W; targets = W и D."""
    points = [ctx.origin] + [(p.lat, p.lon) for p in places]
    index = {p.id: i + 1 for i, p in enumerate(places)}
    sources = [0] + [index[p.id] for p in sources_from]
    targets = list(range(1, len(points)))
    try:
        res = await ctx.svc.places.matrix(points, sources, targets, ctx.transport)
    except DgisError as exc:
        log.warning("plan matrix failed: %s", exc)
        res = {}
    ids = ["user"] + [p.id for p in places]
    return {(ids[s], ids[t]): dur / 60.0 for (s, t), (dur, _dist) in res.items() if s != t}


def _leg_items(ctx: Ctx, option: PlanOption) -> list[PlanLeg]:
    legs = []
    for place, purpose, leg_min, arrival in zip(option.seq, option.purposes, option.legs_min, option.arrivals_min):
        info = open_at(place.schedule, ctx.now, arrival)
        legs.append(PlanLeg(to=place.id, name=place.name, purpose=purpose, transport=ctx.transport,
                            eta_min=max(1, round(leg_min)), lat=place.lat, lon=place.lon, open_state=info.state,
                            open_until=open_until_label(info)))
    return legs


def _template_explanation(option: PlanOption, groups: list[Group], rain_note: str | None) -> str:
    parts = []
    if len(option.seq) == 1 and groups and option.purposes[0] not in [g.label for g in groups]:
        parts.append(f"До места {round(option.legs_min[0])} мин — успеваете напрямую, без остановки.")
    else:
        names = " → ".join(p.name for p in option.seq)
        parts.append(f"Маршрут: {names}, всего около {round(option.total_min)} мин.")
    if rain_note:
        if option.rain_exposure_min > 0.5:
            parts.append(f"Под осадками придётся идти около {round(option.rain_exposure_min)} мин.")
        else:
            parts.append("До начала осадков по прогнозу успеваете.")
    return " ".join(parts)


async def plan_mode(ctx: Ctx) -> AsyncIterator[tuple[str, dict]]:
    svc, f, catalog = ctx.svc, ctx.filters, ctx.catalog
    groups, dest_spec, dest_purpose = build_groups(ctx)
    if dest_spec is None and len(groups) <= 1:
        # Только покупка без цели — это обычный поиск места.
        if f.purchase_item and (item := catalog.purchase_items.get(f.purchase_item.id)) and item.categories:
            ctx.filters = f.model_copy(update={"category_primary": Scored(id=item.categories[0], p=f.purchase_item.p),
                                               "purchase_item": None, "intent": "find_place"})
        async for ev in simple_mode(ctx):
            yield ev
        return

    t_rain, rain_note = rain_minutes(ctx)
    max_travel = float(f.max_travel_min or svc.settings.default_max_travel_min)
    margin = svc.settings.rain_margin_min
    # Промежуточную точку с зонтом нужно успеть до дождя; цель может быть дальше — путь идёт через точку.
    w_minutes = max(5.0, t_rain - margin) if (t_rain is not None and any(g.solves_rain for g in groups)) else max_travel
    radius = search_radius_m(ctx.transport, min(max_travel, w_minutes), svc.settings.speeds_kmh)
    dest_radius = search_radius_m(ctx.transport, max(max_travel, 30.0), svc.settings.speeds_kmh)
    t = time.perf_counter()
    dest_pairs: list = []
    # Первые запросы — параллельно; запасные запросы для покупки — по очереди.
    tasks = [_search_group(ctx, g, radius, first_only=True) for g in groups]
    dest_task = fetch_places(svc, [dest_spec], ctx.req.lat, ctx.req.lon, dest_radius) if dest_spec else None
    results = await asyncio.gather(*tasks, *( [dest_task] if dest_task else [] ), return_exceptions=True)
    if dest_task:
        dest_res = results[-1]
        dest_pairs = [] if isinstance(dest_res, Exception) else dest_res
    for g in groups:
        if not g.candidates and len(g.queries) > 1:
            g.queries = g.queries[1:]
            await _search_group(ctx, g, radius)
    dests = [p for p, _ in dest_pairs if open_at(p.schedule, ctx.now, max(max_travel, 30.0)).state != "closed"][:PER_GROUP]
    ctx.timings["places_ms"] = int((time.perf_counter() - t) * 1000)

    need_loop = any(not g.candidates for g in groups) or (dest_spec is not None and not dests)
    llm_text = None
    tools_state = None
    if need_loop and svc.llm is not None and hasattr(svc.llm, "plan_tool_loop"):
        tools_state = PlannerTools(ctx, groups, dests, t_rain, max_travel, radius, dest_purpose)
        t = time.perf_counter()
        llm_text = await svc.llm.plan_tool_loop(tools_state.user_payload(), tools_state.tool_defs(),
                                                tools_state.execute)
        ctx.timings["planner_ms"] = int((time.perf_counter() - t) * 1000)
        groups, dests = tools_state.groups, tools_state.dests

    if any(not g.candidates for g in groups) or (dest_spec is not None and not dests):
        missing = [g.label for g in groups if not g.candidates]
        if dest_spec is not None and not dests:
            missing.append(dest_purpose)
        yield "candidates", {"items": [], "data_source": svc.data_source}
        yield "answer", Answer(mode="empty", note=llm_text or f"Не нашли поблизости: {', '.join(missing)}.",
                               relaxed=[f"Пробовали запросы: {', '.join(q.q for g in groups for q in g.queries)}"],
                               data_source=svc.data_source).model_dump()
        return

    t = time.perf_counter()
    waypoints = [p for g in groups for p in g.candidates]
    all_places = list({p.id: p for p in waypoints + dests}.values())
    times = await _times(ctx, all_places, list({p.id: p for p in waypoints}.values()))
    ctx.timings["matrix_ms"] = int((time.perf_counter() - t) * 1000)

    options: list[PlanOption] = []
    rain_groups = [g for g in groups if g.solves_rain]
    if t_rain is not None and rain_groups and dests:
        # Прямой вариант: лучшая цель достижима до дождя — маршрут без промежуточной точки.
        reachable = [d for d in dests if (m := times.get(("user", d.id))) is not None and m <= t_rain - margin
                     and (catalog.get(d.category) and catalog.get(d.category).indoor)
                     and open_at(d.schedule, ctx.now, m).state != "closed"]
        if reachable:
            best = min(reachable, key=lambda d: times[("user", d.id)])
            m = times[("user", best.id)]
            options.append(PlanOption(seq=[best], purposes=[dest_purpose], legs_min=[m], arrivals_min=[m],
                                      total_min=m, rain_exposure_min=0.0, feasible=True))
    if not options:
        options = optimize(groups, dests, times, t_rain=t_rain, purchase_min=svc.settings.purchase_minutes,
                           margin=margin, now=ctx.now, catalog=catalog, dest_purpose=dest_purpose)
    if not options:
        yield "candidates", {"items": [], "data_source": svc.data_source}
        yield "answer", Answer(mode="empty", note="Не удалось собрать маршрут: нет данных о времени в пути.",
                               data_source=svc.data_source).model_dump()
        return

    best = options[0]
    cand_evals = []
    waypoint_ids = {p.id for p in waypoints}
    chosen = {p.id for p in best.seq}
    all_places.sort(key=lambda p: 0 if p.id in chosen else 1)  # точки плана — всегда в списке кандидатов
    for p in all_places:
        eta = times.get(("user", p.id))
        role = "waypoint" if p.id in waypoint_ids else "destination"
        ev = Evaluated(place=p, category_p=1.0, role=role, eta_min=eta, eta_source="matrix" if eta is not None else "estimate",
                       now_info=open_info(p.schedule, ctx.now), arrival_info=open_at(p.schedule, ctx.now, eta or 0))
        cand_evals.append(ev)
    for i, ev in enumerate(cand_evals[:8]):
        ev.slot = f"c{i + 1}"
    yield "candidates", {"items": [to_candidate(ev, catalog, ctx.transport).model_dump() for ev in cand_evals[:12]],
                         "data_source": svc.data_source}

    notes: list[str] = []
    for g in groups:
        if g.role == "purchase" and any(p.id in best.key for p in g.candidates):
            item_name = g.label.split(": ", 1)[-1]
            notes.append(f"Наличие товара «{item_name}» не подтверждено")
            if g.used_query and g.used_query != g.queries[0].q:
                notes.append(f"Ищем там, где обычно продают: «{g.used_query}»")
    if rain_note:
        notes.append(rain_note)
    if not best.feasible:
        notes.append("До начала осадков не успеть — выбран вариант с наименьшим временем под дождём")

    geometry: list[list[float]] = []
    t = time.perf_counter()
    try:
        route = await svc.places.route([ctx.origin] + [(p.lat, p.lon) for p in best.seq], ctx.transport)
        geometry = route.get("geometry") or []
    except DgisError as exc:
        log.warning("plan routing failed: %s", exc)
    if not geometry:
        geometry = [[ctx.req.lon, ctx.req.lat]] + [[p.lon, p.lat] for p in best.seq]
    ctx.timings["routing_ms"] = int((time.perf_counter() - t) * 1000)

    explanation = llm_text if (llm_text and tools_state is not None) else None
    if explanation is None and svc.llm is not None and hasattr(svc.llm, "plan_explain"):
        t = time.perf_counter()
        explanation = await svc.llm.plan_explain({
            "query": ctx.req.query,
            "language": ctx.lang,
            "plan": [{"name": p.name, "purpose": purpose, "minutes": round(leg)}
                     for p, purpose, leg in zip(best.seq, best.purposes, best.legs_min)],
            "purchase_minutes": svc.settings.purchase_minutes if len(best.seq) > 1 else 0,
            "total_min": round(best.total_min),
            "rain": rain_note,
            "rain_exposure_min": round(best.rain_exposure_min),
            "notes": notes,
        })
        ctx.timings["llm_ms"] = int((time.perf_counter() - t) * 1000)
    if not explanation:
        explanation = _template_explanation(best, groups, rain_note)

    alternatives = [{
        "legs": [leg.model_dump() for leg in _leg_items(ctx, o)],
        "total_min": round(o.total_min),
        "rain_exposure_min": round(o.rain_exposure_min),
    } for o in options[1:3]]
    plan = Plan(legs=_leg_items(ctx, best), total_min=round(best.total_min),
                rain_exposure_min=round(best.rain_exposure_min), notes=notes, explanation=explanation,
                geometry=geometry, alternatives=alternatives)
    items: list[AnswerItem] = []
    by_id = {ev.place.id: ev for ev in cand_evals}
    for p, purpose in zip(best.seq, best.purposes):
        ev = by_id.get(p.id)
        if ev:
            items.append(to_item(ev, catalog, ctx.transport, why=f"Этап маршрута: {purpose}"))
    yield "answer", Answer(mode="plan", plan=plan, items=items, data_source=svc.data_source).model_dump()


class PlannerTools:
    """Инструменты Sonnet в режиме плана: search_places, travel_matrix, plan_route, weather_nowcast."""

    def __init__(self, ctx: Ctx, groups: list[Group], dests: list[Place], t_rain: float | None,
                 max_travel: float, radius: int, dest_purpose: str):
        self.ctx = ctx
        self.groups = groups
        self.dests = dests
        self.t_rain = t_rain
        self.max_travel = max_travel
        self.radius = radius
        self.dest_purpose = dest_purpose
        self.registry: dict[str, Place] = {p.id: p for g in groups for p in g.candidates}
        self.registry.update({p.id: p for p in dests})

    def user_payload(self) -> dict:
        return {
            "query": self.ctx.req.query,
            "language": self.ctx.lang,
            "transport": self.ctx.transport,
            "local_time": self.ctx.now.strftime("%H:%M"),
            "rain_in_min": self.t_rain,
            "need": [{"role": g.role, "label": g.label, "tried_queries": [q.q for q in g.queries],
                      "found": len(g.candidates)} for g in self.groups],
            "destination": {"label": self.dest_purpose, "found": len(self.dests)},
            "search_radius_m": self.radius,
        }

    def tool_defs(self) -> list[dict]:
        cat_ids = list(self.ctx.catalog.categories) + ["none"]
        return [
            {
                "name": "search_places",
                "description": "Поиск мест в справочнике 2ГИС вокруг пользователя. Возвращает до 8 мест с id, названием, рейтингом и режимом работы сейчас.",
                "strict": True,
                "input_schema": {
                    "type": "object", "additionalProperties": False,
                    "required": ["q", "category_id", "role", "radius_m"],
                    "properties": {
                        "q": {"type": "string", "description": "Текст запроса, например «зонт», «супермаркет», «кофейня»"},
                        "category_id": {"type": "string", "enum": cat_ids, "description": "Категория мест или none"},
                        "role": {"type": "string", "enum": ["waypoint", "destination"]},
                        "radius_m": {"type": "integer", "description": "Радиус поиска в метрах, 300–20000"},
                    },
                },
            },
            {
                "name": "travel_matrix",
                "description": "Время в пути в минутах от пользователя до каждого места и между местами (транспорт пользователя).",
                "strict": True,
                "input_schema": {
                    "type": "object", "additionalProperties": False, "required": ["place_ids"],
                    "properties": {"place_ids": {"type": "array", "items": {"type": "string"}}},
                },
            },
            {
                "name": "plan_route",
                "description": "Оптимизатор маршрута: выбирает промежуточную точку и цель с учётом дождя. Возвращает лучший план и до 2 альтернатив.",
                "strict": True,
                "input_schema": {
                    "type": "object", "additionalProperties": False, "required": ["waypoint_ids", "destination_ids"],
                    "properties": {
                        "waypoint_ids": {"type": "array", "items": {"type": "string"}},
                        "destination_ids": {"type": "array", "items": {"type": "string"}},
                    },
                },
            },
            {
                "name": "weather_nowcast",
                "description": "Через сколько минут начнутся осадки и насколько сильные (по прогнозу).",
                "strict": True,
                "input_schema": {"type": "object", "additionalProperties": False, "required": [], "properties": {}},
            },
        ]

    async def execute(self, name: str, args: dict) -> dict:
        if name == "search_places":
            return await self._search(args)
        if name == "travel_matrix":
            return await self._matrix(args)
        if name == "plan_route":
            return await self._plan(args)
        if name == "weather_nowcast":
            w = self.ctx.weather or {}
            return {"precip_starts_in_min": self.t_rain, "intensity_mmh": w.get("precip_intensity_mmh"),
                    "confirmed": w.get("confirmed"), "source": w.get("source")}
        return {"error": f"Неизвестный инструмент {name}"}

    async def _search(self, args: dict) -> dict:
        role = args.get("role", "destination")
        cat = args.get("category_id")
        radius = int(max(300, min(int(args.get("radius_m") or self.radius), 20_000)))
        spec = QuerySpec(str(args.get("q") or ""), None if cat in (None, "none") else cat, 0.8, role)
        pairs = await fetch_places(self.ctx.svc, [spec], self.ctx.req.lat, self.ctx.req.lon, radius)
        places = [p for p, _ in pairs if open_at(p.schedule, self.ctx.now, 15).state != "closed"][:8]
        for p in places:
            self.registry[p.id] = p
        if role == "waypoint":
            target = next((g for g in self.groups if not g.candidates), self.groups[0] if self.groups else None)
            if target is not None and places:
                target.candidates = places[:PER_GROUP]
                target.used_query = spec.q
        elif places:
            self.dests = places[:PER_GROUP]
        return {"count": len(places), "places": [{
            "id": p.id, "name": p.name, "category": category_label(self.ctx.catalog, p), "rating": p.rating,
            "reviews": p.reviews, "open_now": open_info(p.schedule, self.ctx.now).state,
        } for p in places]}

    async def _matrix(self, args: dict) -> dict:
        places = [self.registry[i] for i in args.get("place_ids") or [] if i in self.registry][:20]
        if not places:
            return {"error": "Нет известных id: сначала search_places"}
        times = await _times(self.ctx, places, places)
        return {"minutes_from_user": {p.id: round(times[("user", p.id)]) for p in places if ("user", p.id) in times},
                "between": [{"from": a, "to": b, "min": round(m)} for (a, b), m in times.items() if a != "user"][:60]}

    async def _plan(self, args: dict) -> dict:
        wps = [self.registry[i] for i in args.get("waypoint_ids") or [] if i in self.registry]
        dps = [self.registry[i] for i in args.get("destination_ids") or [] if i in self.registry]
        if wps and self.groups:
            self.groups[0].candidates = wps[:PER_GROUP]
        if dps:
            self.dests = dps[:PER_GROUP]
        groups = [g for g in self.groups if g.candidates]
        if not groups and not self.dests:
            return {"error": "Нет кандидатов"}
        places = list({p.id: p for p in [p for g in groups for p in g.candidates] + self.dests}.values())
        times = await _times(self.ctx, places, [p for g in groups for p in g.candidates])
        opts = optimize(groups, self.dests, times, t_rain=self.t_rain,
                        purchase_min=self.ctx.svc.settings.purchase_minutes,
                        margin=self.ctx.svc.settings.rain_margin_min, now=self.ctx.now, catalog=self.ctx.catalog,
                        dest_purpose=self.dest_purpose)
        if not opts:
            return {"error": "Маршрут не собрать: нет времени в пути между точками"}
        return {"best": self._describe(opts[0]), "alternatives": [self._describe(o) for o in opts[1:3]]}

    @staticmethod
    def _describe(o: PlanOption) -> dict:
        return {"stops": [{"id": p.id, "name": p.name, "purpose": pur, "leg_min": round(m)}
                          for p, pur, m in zip(o.seq, o.purposes, o.legs_min)],
                "total_min": round(o.total_min), "rain_exposure_min": round(o.rain_exposure_min),
                "reaches_shelter_before_rain": o.feasible}
