"""Агент подбора на Claude Sonnet 5.5 (основной режим поиска).

Модель сама ищет места в 2ГИС, сравнивает их по всем параметрам, сверяет варианты поездки — в том числе
автобусы — и проверяет себя. Код только исполняет инструменты, переводит шаги агента в события прогресса
для приложения и собирает ответ из фактов 2ГИС. Если Claude недоступен, работает прежний конвейер.
"""
from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timedelta
from typing import AsyncIterator

from .dgis import DgisError, Place, label_route_options
from .geo import haversine_m
from .jev import TRANSPORT_LABELS
from .models import Answer, AnswerItem, Chip, JevFilters, SearchRequest
from .schedule import open_at, open_info
from .search import (DETOUR, WEEKDAYS_RU, Evaluated, category_label, open_until_label, to_candidate, to_item,
                     travel_times)
from .services import Services

log = logging.getLogger(__name__)

MATRIX_TRANSPORTS = {"walking", "bicycle", "scooter", "driving", "taxi"}
# Квота демо-ключа 2ГИС: не больше трёх проверок маршрута на один запрос.
MAX_ROUTE_CHECKS = 3
LEG_LABELS = {"bus": "автобус", "trolleybus": "троллейбус", "tram": "трамвай", "shuttle_bus": "маршрутка",
              "metro": "метро", "light_rail": "ЛРТ", "lrt": "ЛРТ"}


class SearchAgent:
    def __init__(self, req: SearchRequest, svc: Services, weather: dict | None, lang: str, emit):
        self.req, self.svc, self.weather, self.lang, self.emit = req, svc, weather, lang, emit
        self.origin = (req.lat, req.lon)
        self.now = req.local_time
        self.transport = req.transport_hint or "walking"
        self.places: dict[str, Place] = {}
        self.etas: dict[tuple[str, str], tuple[float, float, str]] = {}
        self.routes: dict[tuple[str, str], dict] = {}
        self.route_checks = 0
        self.shown: dict[str, Evaluated] = {}

    # ---------- контекст для модели ----------
    def payload(self) -> dict:
        w = self.weather or {}
        return {
            "query": self.req.query,
            "language": self.lang,
            "context": {
                "local_time": self.now.strftime("%H:%M"),
                "date": self.now.strftime("%Y-%m-%d"),
                "weekday": WEEKDAYS_RU[self.now.weekday()],
                "holiday": self.svc.catalog.is_holiday(self.now.date()),
                "weather": {k: w.get(k) for k in ("temp_c", "feels_like_c", "wind_ms", "precip_now",
                                                  "precip_starts_in_min", "precip_next_3h", "sunset",
                                                  "minutes_to_sunset", "is_dark")},
                "walk_active": self.req.walk_active,
                "default_transport": TRANSPORT_LABELS.get(self.transport, self.transport),
                "user_location": "Астана, точка пользователя известна инструментам (округлена до ~110 м)",
            },
            "profile": self.req.profile_summary[:1500] or "портрет пока пуст",
            "category_interest": dict(sorted(self.req.profile_affinity.items(), key=lambda kv: -kv[1])[:8]),
        }

    # ---------- инструменты ----------
    async def execute(self, name: str, args: dict) -> dict:
        if name == "search_places":
            return await self._search(str(args["query"]), int(args.get("radius_m") or 3000))
        if name == "place_details":
            return await self._details(str(args["place_id"]))
        if name == "route_options":
            return await self._route(str(args["place_id"]), str(args["transport"]))
        return {"error": f"неизвестный инструмент {name}"}

    async def _search(self, query: str, radius: int) -> dict:
        radius = max(300, min(radius, 30_000))
        await self.emit(("progress", {"text": f"Ищу: {query}"}))
        places = await self.svc.places.places(q=query, lat=self.origin[0], lon=self.origin[1], radius_m=radius,
                                              page_size=10)
        mode = self.transport if self.transport in MATRIX_TRANSPORTS else "walking"
        etas = await travel_times(self.svc, self.origin, places, mode)
        out = []
        for p in places:
            self.places[p.id] = p
            eta, dist, src = etas.get(p.id, (None, None, "estimate"))
            self.etas[(p.id, mode)] = (eta, dist, src)
            ev = self._evaluated(p, eta, dist, src)
            self.shown.setdefault(p.id, ev)
            out.append(self._place_facts(p, ev, mode))
        if places:
            await self.emit(("candidates", {
                "items": [to_candidate(ev, self.svc.catalog, mode).model_dump() for ev in list(self.shown.values())[:15]],
                "data_source": self.svc.data_source}))
        return {"query": query, "radius_m": radius, "eta_transport": TRANSPORT_LABELS.get(mode, mode),
                "places": out, "note": None if out else "Ничего не найдено — попробуй другую формулировку или радиус."}

    async def _details(self, place_id: str) -> dict:
        p = self.places.get(place_id) or await self.svc.places.place_by_id(place_id)
        if p is None:
            return {"error": "место не найдено"}
        self.places[p.id] = p
        await self.emit(("progress", {"text": f"Смотрю подробности: {p.name}"}))
        return {"id": p.id, "name": p.name, "rubrics": p.rubrics, "address": p.address, "schedule": p.schedule,
                "attributes": p.attribute_labels, "phone": p.phone, "website": p.website, "avg_check_tenge": p.avg_check,
                "rating": p.rating, "reviews": p.reviews, "branches": p.branch_count}

    async def _route(self, place_id: str, transport: str) -> dict:
        p = self.places.get(place_id) or await self.svc.places.place_by_id(place_id)
        if p is None:
            return {"error": "место не найдено"}
        self.places[p.id] = p
        if (p.id, transport) not in self.routes:
            if self.route_checks >= MAX_ROUTE_CHECKS:
                return {"error": f"лимит проверок маршрута на запрос ({MAX_ROUTE_CHECKS}) исчерпан — выбирай из уже проверенных"}
            self.route_checks += 1
        await self.emit(("progress", {"text": f"Проверяю, как добраться ({TRANSPORT_LABELS.get(transport, transport).lower()}): {p.name}"}))
        try:
            data = await self.svc.places.route([self.origin, (p.lat, p.lon)], transport)
        except DgisError as exc:
            return {"error": f"маршрут недоступен: {exc}"}
        options = label_route_options(data)
        self.routes[(p.id, transport)] = {"data": data, "options": options, "transport": transport}
        return {"place": p.name, "transport": transport, "options": [self._option_summary(o) for o in options]}

    # ---------- представление фактов ----------
    def _evaluated(self, p: Place, eta: float | None, dist: float | None, src: str) -> Evaluated:
        if eta is None:
            dist = haversine_m(self.origin[0], self.origin[1], p.lat, p.lon) * DETOUR
            eta, src = dist / (self.svc.settings.speeds_kmh.get("walking", 4.5) * 1000 / 60), "estimate"
        return Evaluated(place=p, category_p=1.0, eta_min=eta, distance_m=dist, eta_source=src,
                         now_info=open_info(p.schedule, self.now), arrival_info=open_at(p.schedule, self.now, eta or 0))

    def _place_facts(self, p: Place, ev: Evaluated, mode: str) -> dict:
        info = ev.arrival_info
        return {
            "id": p.id, "name": p.name, "category": category_label(self.svc.catalog, p), "rubrics": p.rubrics[:4],
            "address": p.address, "distance_m": None if ev.distance_m is None else int(round(ev.distance_m, -1)),
            f"eta_min_{mode}": None if ev.eta_min is None else max(1, round(ev.eta_min)),
            "open_now": ev.now_info.state if ev.now_info else "unknown",
            "open_on_arrival": info.state if info else "unknown",
            "open_until": open_until_label(info), "closes_in_min_after_arrival": info.closes_in_min if info else None,
            "opens_at": info.opens_at if info else None,
            "rating": p.rating, "reviews": p.reviews, "avg_check_tenge": p.avg_check,
            "attributes": p.attribute_labels[:12],
            "chain": None if p.branch_count is None else p.branch_count > 3,
        }

    @staticmethod
    def _option_summary(o: dict) -> dict:
        legs = []
        for leg in o.get("legs") or []:
            minutes = max(1, round(leg["duration_s"] / 60)) if leg["duration_s"] else None
            if leg["kind"] == "walk":
                legs.append(f"пешком {minutes or '?'} мин до «{leg.get('to_name') or 'точки'}»")
            else:
                kind = LEG_LABELS.get(leg["kind"], leg["kind"])
                legs.append(f"{kind} {', '.join(leg['names'])}: «{leg.get('from_name')}» → «{leg.get('to_name')}», "
                            f"{max(0, len(leg['stops']) - 1)} ост., {minutes or '?'} мин в пути, "
                            f"ожидание ~{max(0, round(leg['wait_s'] / 60))} мин")
        return {
            "id": o["id"], "label": o.get("label"), "total_min": max(1, round(o["total_duration_s"] / 60)),
            "distance_m": o["total_distance_m"], "transfers": o.get("transfers"), "walk": o.get("walk_text"),
            "departure": o.get("departure"), "departure_in_min": o.get("departure_in_min"),
            "next_departures": o.get("next_departures") or [], "interval_min": o.get("interval_min"), "legs": legs,
        }

    # ---------- ответ ----------
    async def build_answer(self, result: dict) -> Answer:
        items: list[AnswerItem] = []
        route_out, route_opt = None, None
        for i, it in enumerate((result.get("items") or [])[:3]):
            pid = str(it.get("place_id") or "")
            p = self.places.get(pid) or await self.svc.places.place_by_id(pid)
            if p is None:
                continue
            self.places[p.id] = p
            transport = it.get("transport") or self.transport
            route = self.routes.get((p.id, transport))
            option = None
            if route:
                option = next((o for o in route["options"] if o["id"] == it.get("route_option_id")), route["options"][0])
                ev = self._evaluated(p, option["total_duration_s"] / 60, option["total_distance_m"], "matrix")
            else:
                eta = self.etas.get((p.id, transport))
                if eta is None and transport in MATRIX_TRANSPORTS:
                    eta = (await travel_times(self.svc, self.origin, [p], transport)).get(p.id)
                ev = self._evaluated(p, *(eta or (None, None, "estimate")))
            visit = max(5, min(int(it.get("visit_min") or 30), 480))
            items.append(to_item(ev, self.svc.catalog, transport, why=_trim(it.get("why"), 400), visit_min=visit,
                                 action=_trim(it.get("action"), 260)))
            if i == 0 and result.get("show_route") and route and option:
                d = route["data"]
                route_out = {"transport": transport, "total_distance_m": option["total_distance_m"],
                             "total_duration_s": option["total_duration_s"], "geometry": option["geometry"],
                             "maneuvers": option["maneuvers"], "source": d.get("source", "routing"),
                             "note": d.get("note"), "options": route["options"]}
                route_opt = option["id"]
        mode = "simple" if items else "empty"
        return Answer(mode=mode, items=items, idea=_trim(result.get("idea"), 300), data_source=self.svc.data_source,
                      summary=_trim(result.get("summary"), 300), understood=[str(u)[:40] for u in (result.get("understood") or [])[:5]],
                      route=route_out, route_option_id=route_opt,
                      note=None if items else (_trim(result.get("summary"), 300) or "Подходящих мест не нашлось."))


def _trim(text, limit: int) -> str | None:
    if not text:
        return None
    text = " ".join(str(text).split())
    return text if len(text) <= limit else text[: limit - 1].rstrip() + "…"


async def agent_mode(req: SearchRequest, svc: Services, weather: dict | None, lang: str) -> AsyncIterator[tuple[str, dict]]:
    """События агента для SSE: progress → candidates → filters (как понял запрос) → answer.

    Если агент не дал ответа (ошибка, таймаут, отказ), последним событием будет ("fallback", {}).
    """
    queue: asyncio.Queue = asyncio.Queue()
    agent = SearchAgent(req, svc, weather, lang, emit=queue.put)

    async def on_turn(calls: int) -> None:
        await queue.put(("progress", {"text": "Думаю над запросом…" if calls == 0 else "Сравниваю варианты…"}))

    async def run() -> None:
        try:
            result = await svc.llm.agent_loop(agent.payload(), agent.execute, on_turn=on_turn)
            if result is None:
                await queue.put(("fallback", {}))
                return
            answer = await agent.build_answer(result)
            chips = [Chip(key=f"agent:{i}", label=u, kind="info", removable=False).model_dump()
                     for i, u in enumerate(answer.understood)]
            await queue.put(("filters", {"filters": JevFilters(source="agent").model_dump(), "chips": chips,
                                         "transport": answer.items[0].transport if answer.items else agent.transport}))
            if answer.items:
                await queue.put(("candidates", {"items": [to_candidate(agent._evaluated(
                    agent.places[it.poi_id], it.eta_min, None, "matrix"), svc.catalog, it.transport).model_dump()
                    for it in answer.items], "data_source": svc.data_source}))
            await queue.put(("answer", answer.model_dump()))
        except Exception:
            log.exception("agent failed")
            await queue.put(("fallback", {}))
        finally:
            await queue.put(("__end__", {}))

    task = asyncio.create_task(run())
    try:
        while True:
            event, data = await queue.get()
            if event == "__end__":
                break
            yield event, data
    finally:
        if not task.done():
            task.cancel()
