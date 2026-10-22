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
from urllib.parse import urlparse

from .dgis import DgisError, Place, label_route_options
from .geo import haversine_m
from .jev import TRANSPORT_LABELS
from .models import Answer, AnswerItem, Chip, Evidence, JevFilters, Plan, PlanLeg, RememberOffer, SearchRequest
from .schedule import open_at, open_info
from .search import (DETOUR, WEEKDAYS_RU, Evaluated, category_label, open_until_label, to_candidate, to_item,
                     travel_times)
from .services import Services

log = logging.getLogger(__name__)

MATRIX_TRANSPORTS = {"walking", "bicycle", "scooter", "driving", "taxi"}
# Квота демо-ключа 2ГИС: не больше трёх проверок маршрута на один запрос.
MAX_ROUTE_CHECKS = 3
MAX_TRIP_CHECKS = 3
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
        self.trip_checks = 0
        self.legs: dict[tuple, dict] = {}
        self.shown: dict[str, Evaluated] = {}
        # Прошлый ответ (уточнение «добавь аптеку», «убери парк») — его места доступны по тем же id.
        for it in (req.previous.items if req.previous else []):
            self.places.setdefault(it.poi_id, Place(id=it.poi_id, name=it.name, lat=it.lat, lon=it.lon))
        # Сохранённые места пользователя — как обычные места с id saved:N.
        for i, sp in enumerate(req.saved_places):
            self.places[f"saved:{i}"] = Place(id=f"saved:{i}", name=sp.name, lat=sp.lat, lon=sp.lon, address=sp.address)

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
            "saved_places": [{"place_id": f"saved:{i}", "name": sp.name, "address": sp.address}
                             for i, sp in enumerate(self.req.saved_places)],
            "previous": None if not self.req.previous else {
                "query": self.req.previous.query, "summary": self.req.previous.summary, "trip": self.req.previous.trip,
                "items": [{"place_id": it.poi_id, "name": it.name, "purpose": it.purpose, "transport": it.transport}
                          for it in self.req.previous.items]},
        }

    # ---------- инструменты ----------
    async def execute(self, name: str, args: dict) -> dict:
        if name == "search_places":
            return await self._search(str(args["query"]), int(args.get("radius_m") or 3000))
        if name == "check_trip":
            return await self._trip([str(p) for p in args.get("place_ids") or []],
                                    [int(v) for v in args.get("visit_min") or []],
                                    [str(v) for v in args.get("transports") or []])
        if name == "find_address":
            return await self._address(str(args["query"]))
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

    async def _leg(self, a: tuple[float, float], b: tuple[float, float], transport: str) -> dict:
        """Один этап поездки со всеми вариантами 2ГИС (автобусы — до 6, остальное — с альтернативами); кэшируется."""
        key = (round(a[0], 4), round(a[1], 4), round(b[0], 4), round(b[1], 4), transport)
        if key in self.legs:
            return self.legs[key]
        try:
            data = await self.svc.places.route([a, b], transport)
            options = label_route_options(data) if data.get("options") else [{**data, "id": "0"}]
            leg = {"options": options, "estimate": False}
        except DgisError:
            dist = haversine_m(a[0], a[1], b[0], b[1]) * DETOUR
            speed = self.svc.settings.speeds_kmh.get(transport if transport in MATRIX_TRANSPORTS else "walking", 4.5)
            leg = {"options": [{"id": "est", "label": "оценка по прямой", "total_duration_s": int(dist / (speed * 1000 / 3600)),
                                "total_distance_m": int(dist), "geometry": [[a[1], a[0]], [b[1], b[0]]], "maneuvers": [],
                                "legs": []}], "estimate": True}
        self.legs[key] = leg
        return leg

    async def _trip(self, place_ids: list[str], visits: list[int], transports: list[str]) -> dict:
        """Маршрут через несколько точек в заданном порядке: по каждому этапу — варианты дороги (какой автобус,
        отправление, пересадки), прибытие и уход, открыто ли к приходу. Время считается по самому быстрому варианту."""
        if not 1 <= len(place_ids) <= 6:
            return {"error": "нужно от 1 до 6 точек"}
        if self.trip_checks >= MAX_TRIP_CHECKS:
            return {"error": f"лимит проверок маршрута ({MAX_TRIP_CHECKS}) исчерпан — выбери из уже проверенных"}
        self.trip_checks += 1
        places = []
        for pid in place_ids:
            p = self.places.get(pid) or await self.svc.places.place_by_id(pid)
            if p is None:
                return {"error": f"место {pid} не найдено"}
            self.places[p.id] = p
            places.append(p)
        await self.emit(("progress", {"text": "Проверяю порядок: " + " → ".join(p.name for p in places)[:120]}))
        stops, here, clock = [], self.origin, 0.0
        for i, p in enumerate(places):
            transport = transports[i] if i < len(transports) else (transports[-1] if transports else self.transport)
            leg = await self._leg(here, (p.lat, p.lon), transport)
            best = min(leg["options"], key=lambda o: o.get("total_duration_s") or 0)
            clock += (best.get("total_duration_s") or 0) / 60
            info = open_at(p.schedule, self.now, clock)
            visit = max(5, min(visits[i] if i < len(visits) else 30, 480))
            stops.append({"place_id": p.id, "name": p.name, "transport": transport,
                          "route_options": [self._option_summary(o) for o in leg["options"][:4]],
                          "fastest_option_id": best.get("id"), "estimate": leg["estimate"],
                          "arrive_at": (self.now + timedelta(minutes=clock)).strftime("%H:%M"),
                          "open_on_arrival": info.state if info else "unknown",
                          "leave_at": (self.now + timedelta(minutes=clock + visit)).strftime("%H:%M")})
            clock += visit
            here = (p.lat, p.lon)
        return {"stops": stops, "total_min": round(clock), "finish_at": (self.now + timedelta(minutes=clock)).strftime("%H:%M"),
                "note": "Для каждой точки выбери route_option_id из route_options её этапа (по умолчанию — самый быстрый)."}

    async def _plan(self, items: list[AnswerItem], visits: list[int], purposes: list[str], option_ids: list[str | None]) -> Plan:
        """План по точкам ответа в его порядке: на каждом этапе — выбранный агентом вариант дороги целиком
        (линия, манёвры, автобусы и остановки), расписание прибытия и ухода."""
        legs, geometry, here, clock = [], [], self.origin, 0.0
        for i, it in enumerate(items):
            p = self.places[it.poi_id]
            leg = await self._leg(here, (p.lat, p.lon), it.transport)
            opt = next((o for o in leg["options"] if str(o.get("id")) == str(option_ids[i])), None) or \
                min(leg["options"], key=lambda o: o.get("total_duration_s") or 0)
            dur = (opt.get("total_duration_s") or 0) / 60
            clock += dur
            info = open_at(p.schedule, self.now, clock)
            visit = visits[i]
            geometry += opt.get("geometry") or []
            route = None if leg["estimate"] else {
                "id": str(opt.get("id")), "label": opt.get("label"), "total_distance_m": opt.get("total_distance_m") or 0,
                "total_duration_s": opt.get("total_duration_s") or 0, "geometry": opt.get("geometry") or [],
                "maneuvers": opt.get("maneuvers") or [], "legs": opt.get("legs") or [], "transfers": opt.get("transfers"),
                "walk_text": opt.get("walk_text"), "departure": opt.get("departure"),
                "departure_in_min": opt.get("departure_in_min"), "next_departures": opt.get("next_departures") or [],
                "interval_min": opt.get("interval_min")}
            legs.append(PlanLeg(to=p.id, name=p.name, purpose=purposes[i] or "Остановка", transport=it.transport,
                                eta_min=max(1, round(dur)), lat=p.lat, lon=p.lon,
                                open_state=info.state if info else "unknown", open_until=open_until_label(info),
                                arrive_at=(self.now + timedelta(minutes=clock)).strftime("%H:%M"),
                                leave_at=(self.now + timedelta(minutes=clock + visit)).strftime("%H:%M"),
                                visit_min=visit, distance_m=opt.get("total_distance_m") or 0,
                                route=route, route_label=_route_label(opt, it.transport)))
            clock += visit
            here = (p.lat, p.lon)
        return Plan(legs=legs, total_min=round(clock), rain_exposure_min=0, geometry=geometry)

    def validate(self, result: dict) -> str | None:
        """Проверка ответа перед показом: каждый шаг запроса закрыт местом или объяснён, места — из инструментов."""
        unknown = [str(it.get("place_id")) for it in result.get("items") or [] if str(it.get("place_id")) not in self.places]
        if unknown:
            return (f"place_id {', '.join(unknown)} не встречались в результатах инструментов. Используй только id из "
                    "search_places, find_address, saved_places или previous и снова вызови submit_answer.")
        steps = [s for s in result.get("requested_steps") or [] if str(s).strip()]
        items = result.get("items") or []
        skipped = result.get("skipped") or []
        if len(steps) >= 2 and len(items) + len(skipped) < len(steps):
            return (f"В запросе {len(steps)} шагов ({'; '.join(map(str, steps))}), а в ответе мест {len(items)} и пропусков "
                    f"{len(skipped)}. Добавь место на каждый шаг (trip = true, порядок посещения) или перечисли невыполнимые "
                    "шаги в skipped с причиной, затем снова вызови submit_answer.")
        return None
    async def _address(self, query: str) -> dict:
        await self.emit(("progress", {"text": f"Ищу адрес: {query}"}))
        try:
            found = await self.svc.places.geocode(query, self.origin[0], self.origin[1])
        except DgisError as exc:
            return {"error": f"геокодер недоступен: {exc}"}
        out = []
        for g in found[:3]:
            pid = f"addr:{len([k for k in self.places if k.startswith('addr:')])}"
            p = Place(id=pid, name=g.get("title") or query, lat=float(g["lat"]), lon=float(g["lon"]), address=g.get("title"))
            self.places[pid] = p
            out.append({"place_id": pid, "name": p.name, "type": g.get("type"),
                        "distance_m": int(round(haversine_m(self.origin[0], self.origin[1], p.lat, p.lon), -1))})
        return {"query": query, "results": out, "note": None if out else "Адрес не найден — уточни улицу и дом."}

    async def _details(self, place_id: str) -> dict:
        p = self.places.get(place_id) or await self.svc.places.place_by_id(place_id)
        if p is None:
            return {"error": "место не найдено"}
        self.places[p.id] = p
        await self.emit(("progress", {"text": f"Смотрю подробности: {p.name}"}))
        return {"id": p.id, "name": p.name, "menu_page": _menu_page(p), "rubrics": p.rubrics, "address": p.address, "schedule": p.schedule,
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
        for i, it in enumerate((result.get("items") or [])[:5]):
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
            item = to_item(ev, self.svc.catalog, transport, why=_trim(it.get("why"), 400), visit_min=visit,
                           action=_trim(it.get("action"), 260))
            item.evidence = [Evidence(fact=_trim(e.get("fact"), 160) or "", source=_url(e.get("source")))
                             for e in (it.get("evidence") or [])[:6] if e.get("fact")]
            items.append(item)
            if i == 0 and result.get("show_route") and route and option:
                d = route["data"]
                route_out = {"transport": transport, "total_distance_m": option["total_distance_m"],
                             "total_duration_s": option["total_duration_s"], "geometry": option["geometry"],
                             "maneuvers": option["maneuvers"], "source": d.get("source", "routing"),
                             "note": d.get("note"), "options": route["options"]}
                route_opt = option["id"]
        plan = None
        if result.get("trip") and len(items) >= 2:
            raw = (result.get("items") or [])[:5]
            kept = {it.poi_id for it in items}
            src = [r for r in raw if str(r.get("place_id")) in kept]
            plan = await self._plan(items, [max(5, min(int(r.get("visit_min") or 30), 480)) for r in src],
                                    [_trim(r.get("purpose"), 40) or "" for r in src],
                                    [r.get("route_option_id") for r in src])
            route_out, route_opt = None, None
        remember = None
        rem = result.get("remember") or None
        if rem and rem.get("name"):
            rp = self.places.get(str(rem.get("place_id") or ""))
            if rp is not None:
                remember = RememberOffer(name=_trim(rem["name"], 40) or "Место", icon=str(rem.get("icon") or "star"),
                                         address=rp.address or rp.name, lat=rp.lat, lon=rp.lon)
        mode = "plan" if plan else ("simple" if items else "empty")
        return Answer(mode=mode, items=items, idea=_trim(result.get("idea"), 300), data_source=self.svc.data_source,
                      summary=_trim(result.get("summary"), 300), understood=[str(u)[:40] for u in (result.get("understood") or [])[:5]],
                      route=route_out, route_option_id=route_opt, remember=remember, plan=plan,
                      skipped=[f"{_trim(s.get('step'), 60)} — {_trim(s.get('reason'), 140)}"
                               for s in (result.get("skipped") or [])[:5] if s.get("step")],
                      note=None if items else (_trim(result.get("summary"), 300) or "Подходящих мест не нашлось."))


def _route_label(o: dict, transport: str) -> str:
    """Подпись выбранного варианта: «Автобус 47 · отправление 17:13» или «Пешком · быстрее всего»."""
    rides = [leg for leg in o.get("legs") or [] if leg.get("kind") != "walk"]
    if rides:
        parts = [f"{LEG_LABELS.get(leg.get('kind'), 'транспорт').capitalize()} {', '.join(leg.get('names') or [])}".strip()
                 for leg in rides]
        tail = (f" · отправление {o['departure']}" if o.get("departure")
                else f" · каждые ~{o['interval_min']} мин" if o.get("interval_min") else "")
        return " → ".join(parts) + tail
    label = TRANSPORT_LABELS.get(transport, transport)
    return f"{label} · {o['label'].lower()}" if o.get("label") else label


def _menu_page(p: Place) -> str | None:
    """Страница цен и меню места в 2ГИС — первое, что стоит прочитать через web_fetch."""
    firm = p.id.split("_")[0]
    return f"https://2gis.kz/astana/firm/{firm}/tab/prices" if firm.isdigit() else None


def _url(value) -> str | None:
    """Источник факта — только http(s)-ссылка, иначе не показываем."""
    v = str(value or "").strip()
    return v[:500] if v.startswith(("https://", "http://")) else None


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

    async def on_web(name: str, args: dict) -> None:
        if name == "web_search":
            await queue.put(("progress", {"text": f"Ищу в интернете: {_trim(args.get('query'), 80) or '…'}"}))
        elif name == "web_fetch":
            host = urlparse(str(args.get("url") or "")).netloc.removeprefix("www.")
            await queue.put(("progress", {"text": f"Читаю страницу: {host or 'сайт'}"}))

    async def run() -> None:
        try:
            result = await svc.llm.agent_loop(agent.payload(), agent.execute, on_turn=on_turn, on_web=on_web,
                                              validate=agent.validate)
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
