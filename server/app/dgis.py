"""Клиент API 2ГИС: Places, Suggest, Geocoder, Distance Matrix, Routing, Public Transport.

Точные имена полей ответа (режим работы, рейтинг, атрибуты, число филиалов) сверить
со справочником в первый день (раздел 4) — разбор ниже защищён от отсутствующих полей.
"""
from __future__ import annotations

import logging
import re
import time
from dataclasses import dataclass, field
from datetime import date

import httpx

from .cache import TTLCache
from .catalog import Catalog
from .config import Settings

log = logging.getLogger(__name__)

_WKT_PAIR = re.compile(r"(-?\d+(?:\.\d+)?)\s+(-?\d+(?:\.\d+)?)")
_AVG_CHECK = re.compile(r"(\d[\d\s]{2,6})")


class DgisError(Exception):
    pass


@dataclass
class Place:
    id: str
    name: str
    lat: float
    lon: float
    address: str | None = None
    rubrics: list[str] = field(default_factory=list)
    category: str | None = None
    schedule: dict | None = None
    rating: float | None = None
    reviews: int | None = None
    attributes: list[str] = field(default_factory=list)  # «tag|название» в нижнем регистре
    attribute_labels: list[str] = field(default_factory=list)
    branch_count: int | None = None
    phone: str | None = None
    website: str | None = None
    avg_check: int | None = None
    rank: int = 0
    demo: bool = False

    def card(self) -> dict:
        return {
            "poi_id": self.id,
            "name": self.name,
            "lat": self.lat,
            "lon": self.lon,
            "address": self.address,
            "rubrics": self.rubrics,
            "category": self.category,
            "rating": self.rating,
            "reviews": self.reviews,
            "attributes": self.attribute_labels,
            "branch_count": self.branch_count,
            "phone": self.phone,
            "website": self.website,
            "avg_check": self.avg_check,
            "schedule": self.schedule,
        }


class UsageCounters:
    """Счётчик запросов по сервисам 2ГИС; при 80% лимита — предупреждение в лог (раздел 14)."""

    def __init__(self, quota: int):
        self.quota = quota
        self.counts: dict[str, int] = {}
        self._warned: set[str] = set()

    def hit(self, service: str) -> None:
        n = self.counts.get(service, 0) + 1
        self.counts[service] = n
        if self.quota and n >= 0.8 * self.quota and service not in self._warned:
            self._warned.add(service)
            log.warning("2gis quota: service %s used %d of %d (80%%+)", service, n, self.quota)

    def snapshot(self) -> dict:
        return {"quota_per_service": self.quota, "used": dict(self.counts)}


def parse_wkt(wkt: str) -> list[list[float]]:
    """LINESTRING(lon lat, lon lat) → [[lon, lat], ...]."""
    return [[float(a), float(b)] for a, b in _WKT_PAIR.findall(wkt or "")]


def _first(*values):
    for v in values:
        if v is not None:
            return v
    return None


def parse_place(item: dict, catalog: Catalog, rank: int = 0) -> Place | None:
    point = item.get("point") or {}
    lat, lon = point.get("lat"), point.get("lon")
    if lat is None or lon is None or not item.get("id"):
        return None
    rubrics_raw = item.get("rubrics") or []
    rubrics_sorted = sorted(rubrics_raw, key=lambda r: 0 if r.get("kind") == "primary" else 1)
    rubrics = [r.get("name", "") for r in rubrics_sorted if r.get("name")]
    reviews = item.get("reviews") or {}
    rating = _first(reviews.get("general_rating"), reviews.get("rating"), reviews.get("org_rating"), item.get("rating"))
    count = _first(reviews.get("general_review_count"), reviews.get("review_count"), reviews.get("org_review_count"))
    attrs, labels = [], []
    avg_check = None
    for group in item.get("attribute_groups") or []:
        gname = (group.get("name") or "").strip()
        for a in group.get("attributes") or []:
            tag = (a.get("tag") or "").strip()
            name = (a.get("name") or gname).strip()
            if not tag and not name:
                continue
            attrs.append(f"{tag}|{name}".lower())
            if name:
                labels.append(name)
            if "чек" in name.lower() or "check" in tag.lower():
                m = _AVG_CHECK.search(name)
                if m:
                    avg_check = int(re.sub(r"\s+", "", m.group(1)))
    phone = website = None
    for cg in item.get("contact_groups") or []:
        for c in cg.get("contacts") or []:
            ctype = c.get("type")
            if ctype == "phone" and not phone:
                phone = c.get("value") or c.get("text")
            elif ctype in ("website", "site") and not website:
                website = c.get("url") or c.get("value") or c.get("text")
    org = item.get("org") or {}
    name = item.get("name") or item.get("full_name") or "Без названия"
    try:
        rating_f = float(rating) if rating is not None else None
    except (TypeError, ValueError):
        rating_f = None
    try:
        count_i = int(count) if count is not None else None
    except (TypeError, ValueError):
        count_i = None
    return Place(
        id=str(item["id"]),
        name=name,
        lat=float(lat),
        lon=float(lon),
        address=item.get("address_name") or item.get("full_address_name"),
        rubrics=rubrics,
        category=catalog.match_rubrics(rubrics, ""),
        schedule=item.get("schedule"),
        rating=rating_f,
        reviews=count_i,
        attributes=attrs,
        attribute_labels=labels,
        branch_count=org.get("branch_count"),
        phone=phone,
        website=website,
        avg_check=avg_check,
        rank=rank,
    )


def parse_route(data: dict) -> dict:
    """Ответ Routing API → основной вариант (геометрия и манёвры) и все альтернативы в options."""
    results = data.get("result") or []
    if not results:
        raise DgisError(data.get("message") or data.get("status") or "route not found")
    options = [_route_result(res) | {"id": str(i)} for i, res in enumerate(results)]
    return {**options[0], "options": options}


def _route_result(res: dict) -> dict:
    """Один вариант Routing API: геометрия и манёвры. Формат манёвров сверен по ответам 2ГИС (раздел 7)."""
    geometry: list[list[float]] = []
    maneuvers: list[dict] = []
    begin: list[str] = []
    _collect_selections(res.get("begin_pedestrian_path"), begin)
    for s in begin:
        geometry.extend(parse_wkt(s))
    for m in res.get("maneuvers") or []:
        path = m.get("outcoming_path") or {}
        coords: list[list[float]] = []
        for g in path.get("geometry") or []:
            coords.extend(parse_wkt(g.get("selection", "")))
        if coords:
            geometry.extend(coords[1:] if geometry and geometry[-1] == coords[0] else coords)
        point = coords[0] if coords else (geometry[-1] if geometry else None)
        if point is None:
            continue
        maneuvers.append({
            "text": m.get("comment") or m.get("outcoming_path_comment") or "",
            "icon": m.get("icon"),
            "type": m.get("type"),
            "turn_direction": m.get("turn_direction"),
            "lat": point[1],
            "lon": point[0],
            "distance_m": int(path.get("distance") or 0),
            "duration_s": int(path.get("duration") or 0),
        })
    if maneuvers and geometry:
        last = maneuvers[-1]
        if last["distance_m"] == 0:
            last["lat"], last["lon"] = geometry[-1][1], geometry[-1][0]
    return {
        "total_distance_m": int(res.get("total_distance") or 0),
        "total_duration_s": int(res.get("total_duration") or 0),
        "geometry": geometry,
        "maneuvers": maneuvers,
    }


def _collect_selections(node, out: list[str]) -> None:
    if isinstance(node, dict):
        for k, v in node.items():
            if k == "selection" and isinstance(v, str):
                out.append(v)
            else:
                _collect_selections(v, out)
    elif isinstance(node, list):
        for v in node:
            _collect_selections(v, out)


def label_route_options(data: dict) -> list[dict]:
    """Варианты маршрута с подписями: самый быстрый, меньше пешком, разница во времени с лучшим."""
    options = [dict(o) for o in data.get("options") or []] or [{
        "id": "0", "total_distance_m": data["total_distance_m"], "total_duration_s": data["total_duration_s"],
        "geometry": data["geometry"], "maneuvers": data["maneuvers"]}]
    best = min(o["total_duration_s"] for o in options)
    walks = [sum(leg["distance_m"] for leg in o.get("legs") or [] if leg["kind"] == "walk") for o in options]
    least_walk = walks.index(min(walks)) if any(o.get("legs") for o in options) else -1
    for i, o in enumerate(options):
        if o["total_duration_s"] == best:
            o["label"] = "Быстрее всего"
        elif i == least_walk:
            o["label"] = "Меньше пешком"
        else:
            o["label"] = f"+{max(1, round((o['total_duration_s'] - best) / 60))} мин"
    return options


# Запасная прокладка по дорогам OpenStreetMap (FOSSGIS OSRM), если 2ГИС не строит маршрут (лимит ключа, сбой).
OSM_PROFILES = {"walking": "routed-foot", "bicycle": "routed-bike", "scooter": "routed-bike",
                "driving": "routed-car", "taxi": "routed-car"}
OSM_TURNS = {"left": "налево", "right": "направо", "slight left": "плавно налево", "slight right": "плавно направо",
             "sharp left": "резко налево", "sharp right": "резко направо", "uturn": "на разворот", "straight": "прямо"}


def _osm_step_text(step: dict) -> str:
    m = step.get("maneuver") or {}
    kind, mod = m.get("type"), m.get("modifier")
    road = step.get("name") or ""
    on = f" на {road}" if road else ""
    if kind == "depart":
        return f"Начните движение{(' по ' + road) if road else ''}"
    if kind == "arrive":
        return "Вы на месте"
    if kind in ("roundabout", "rotary"):
        n = m.get("exit")
        return f"На круге — {n}-й съезд{on}" if n else f"Проезжайте круг{on}"
    turn = OSM_TURNS.get(mod or "", "")
    if not turn or turn == "прямо":
        return f"Продолжайте прямо{on}"
    return f"Поверните {turn}{on}"


def parse_osm_route(data: dict) -> dict:
    """Ответ OSRM → тот же формат, что у 2ГИС: варианты с линией [lon, lat] и манёврами по-русски."""
    routes = data.get("routes") or []
    if data.get("code") != "Ok" or not routes:
        raise DgisError(f"osm: {data.get('code') or 'no route'}")
    options = []
    for i, r in enumerate(routes[:3]):
        maneuvers = []
        for leg in r.get("legs") or []:
            for st in leg.get("steps") or []:
                loc = (st.get("maneuver") or {}).get("location") or [0, 0]
                mod = (st.get("maneuver") or {}).get("modifier") or ""
                maneuvers.append({"text": _osm_step_text(st), "icon": None, "type": (st.get("maneuver") or {}).get("type"),
                                  "turn_direction": mod.replace("slight ", "").replace("sharp ", "") if "left" in mod or "right" in mod else None,
                                  "lat": loc[1], "lon": loc[0], "distance_m": int(st.get("distance") or 0),
                                  "duration_s": int(st.get("duration") or 0)})
        options.append({"id": str(i), "total_distance_m": int(r.get("distance") or 0), "total_duration_s": int(r.get("duration") or 0),
                        "geometry": (r.get("geometry") or {}).get("coordinates") or [], "maneuvers": maneuvers})
    return {**options[0], "options": options}


PT_MAX_OPTIONS = 6


def parse_public_transport(data, origin: tuple[float, float], now_utc: float | None = None) -> dict:
    """Ответ Public Transport API → основной вариант и до 6 вариантов в options.

    Вариант — этапы (пешком, автобус и т. п.) с номерами маршрутов, их цветом, остановками посадки и выхода,
    промежуточными остановками с координатами и временем ближайшего отправления по расписанию.
    """
    variants = data if isinstance(data, list) else (data.get("result") or data.get("routes") or [])
    if not variants:
        raise DgisError("public transport route not found")
    options = [_pt_variant(v, origin, now_utc) | {"id": str(v.get("id") or i)} for i, v in enumerate(variants[:PT_MAX_OPTIONS])]
    return {**options[0], "options": options}


def _pt_variant(v: dict, origin: tuple[float, float], now_utc: float | None) -> dict:
    movements = v.get("movements") or []
    geometry: list[list[float]] = []
    steps: list[dict] = []
    legs: list[dict] = []
    cursor = [origin[1], origin[0]]
    for i, mv in enumerate(movements):
        wp = mv.get("waypoint") or {}
        nxt = (movements[i + 1].get("waypoint") or {}) if i + 1 < len(movements) else {}
        routes = mv.get("routes") or []
        names = [n for r in routes for n in (r.get("names") or [])]
        kind = mv.get("type")
        alt = (mv.get("alternatives") or [{}])[0]
        mv_sel: list[str] = []
        _collect_selections(alt.get("geometry") or mv.get("geometry") or [], mv_sel)
        mv_coords = [c for s in mv_sel for c in parse_wkt(s)]
        if mv_coords:
            geometry.extend(mv_coords[1:] if geometry and geometry[-1] == mv_coords[0] else mv_coords)
        point = mv_coords[0] if mv_coords else cursor
        if mv_coords:
            cursor = mv_coords[-1]
        to_name = nxt.get("name") if nxt.get("subtype") != "finish" else "цели"
        if kind == "walkway" or not routes:
            if not mv.get("distance"):
                continue  # нулевой этап: финиш или пересадка на той же остановке
            text = f"Пешком до «{to_name or 'остановки'}»"
            leg_kind = "walk"
        else:
            text = f"{_pt_subtype(routes)} {', '.join(names)} до «{to_name or 'остановки'}»" if names else (to_name or "Дальше")
            leg_kind = ((routes[0].get("subtype") if routes else None) or "transit").lower()
        steps.append({"text": text, "icon": kind, "type": kind, "turn_direction": None, "lat": point[1], "lon": point[0],
                      "distance_m": int(mv.get("distance") or 0),
                      "duration_s": int(mv.get("moving_duration") or mv.get("duration") or 0)})
        stops: list[dict] = []
        if leg_kind != "walk":
            pts = [parse_wkt(p.get("geometry") or "") for p in alt.get("platforms") or []]
            pts = [p[0] for p in pts if p]
            mid_names = list((mv.get("platforms") or {}).get("names") or []) if isinstance(mv.get("platforms"), dict) else []
            for j, (lon, lat) in enumerate(pts):
                if j == 0:
                    name = wp.get("name")
                elif j == len(pts) - 1:
                    name = nxt.get("name")
                else:
                    name = mid_names[j - 1] if len(mid_names) == len(pts) - 2 else None
                stops.append({"lat": lat, "lon": lon, "name": name})
        legs.append({
            "kind": leg_kind,
            "names": names,
            "color": (routes[0].get("color") if routes else None),
            "from_name": wp.get("name") if wp.get("subtype") != "start" else None,
            "to_name": to_name,
            "duration_s": int(mv.get("moving_duration") or 0),
            "wait_s": int(mv.get("waiting_duration") or 0),
            "distance_m": int(mv.get("distance") or 0),
            "geometry": mv_coords,
            "stops": stops,
        })
    if geometry:
        steps.append({"text": "Вы на месте", "icon": "finish", "type": "end", "turn_direction": None,
                      "lat": geometry[-1][1], "lon": geometry[-1][0], "distance_m": 0, "duration_s": 0})
    # Расписание: precise — ближайшие отправления по времени, periodic — только интервал движения.
    schedules = v.get("schedules") or []
    precise = [s for s in schedules if s.get("type") == "precise" and s.get("precise_time")]
    periodic = next((s for s in schedules if s.get("type") == "periodic" and s.get("period")), None)
    departure_in = None
    if precise and precise[0].get("start_time_utc") and now_utc is not None:
        departure_in = max(0, round((float(precise[0]["start_time_utc"]) - now_utc) / 60))
    return {
        "total_distance_m": int(v.get("total_distance") or 0),
        "total_duration_s": int(v.get("total_duration") or 0),
        "geometry": geometry,
        "maneuvers": steps,
        "legs": legs,
        "transfers": v.get("transfer_count"),
        "walk_text": v.get("total_walkway_distance"),
        "departure": precise[0]["precise_time"] if precise else None,
        "departure_in_min": departure_in,
        "next_departures": [s["precise_time"] for s in precise[1:4]],
        "interval_min": int(periodic["period"]) if periodic and not precise else None,
    }


def _pt_subtype(routes: list[dict]) -> str:
    sub = (routes[0].get("subtype") or "").lower() if routes else ""
    return {"bus": "Автобус", "trolleybus": "Троллейбус", "tram": "Трамвай", "metro": "Метро", "shuttle_bus": "Маршрутка",
            "light_rail": "ЛРТ", "lrt": "ЛРТ"}.get(sub, "Транспорт")


class DgisClient:
    """Реальный клиент 2ГИС поверх httpx. Ответы кэшируются до 24 ч (раздел 13)."""

    name = "2gis"

    def __init__(self, http: httpx.AsyncClient, settings: Settings, catalog: Catalog, counters: UsageCounters,
                 locale: str = "ru_KZ"):
        self._http = http
        self._s = settings
        self._catalog = catalog
        self.counters = counters
        self._locale = locale
        self._places_cache = TTLCache(24 * 3600)
        self._short_cache = TTLCache(600)

    async def _get(self, service: str, url: str, params: dict) -> dict:
        self.counters.hit(service)
        params = {**params, "key": self._s.dgis_key}
        try:
            r = await self._http.get(url, params=params, timeout=4.0)
        except httpx.HTTPError as exc:
            raise DgisError(f"{service}: {type(exc).__name__}") from exc
        if r.status_code >= 500:
            raise DgisError(f"{service}: http {r.status_code}")
        try:
            return r.json()
        except ValueError as exc:
            raise DgisError(f"{service}: bad json") from exc

    async def _post(self, service: str, url: str, body: dict, params: dict | None = None, timeout: float = 6.0):
        self.counters.hit(service)
        params = {**(params or {}), "key": self._s.dgis_key}
        try:
            r = await self._http.post(url, params=params, json=body, timeout=timeout)
        except httpx.HTTPError as exc:
            raise DgisError(f"{service}: {type(exc).__name__}") from exc
        if r.status_code >= 400:
            raise DgisError(f"{service}: http {r.status_code} {r.text[:300]}")
        return r.json()

    def _items(self, data: dict) -> list[dict]:
        meta = data.get("meta") or {}
        code = meta.get("code", 200)
        if code == 404:
            return []
        if code != 200:
            err = (meta.get("error") or {}).get("message") or str(code)
            raise DgisError(f"places: {err}")
        return (data.get("result") or {}).get("items") or []

    async def places(self, *, q: str, lat: float, lon: float, radius_m: int, sort: str | None = None,
                     work_time: str | None = None, page_size: int | None = None, page: int = 1,
                     opened_after: date | None = None, extra: dict | None = None) -> list[Place]:
        size = min(page_size or self._s.places_page_size, 10 if self._s.dgis_demo_key else 50)
        params = {
            "q": q,
            "point": f"{lon},{lat}",
            "radius": radius_m,
            "fields": self._s.dgis_place_fields,
            "page_size": size,
            "page": page,
            "locale": self._locale,
        }
        if sort:
            params["sort"] = sort
        if work_time:
            params["work_time"] = work_time
        if opened_after:
            params["opened_after_date"] = opened_after.isoformat()
        if extra:
            params.update(extra)
        key = ("places", tuple(sorted(params.items())))
        cached = self._places_cache.get(key)
        if cached is not None:
            return cached
        data = await self._get("places", f"{self._s.dgis_catalog_url}/3.0/items", params)
        places = [p for i, it in enumerate(self._items(data)) if (p := parse_place(it, self._catalog, i))]
        self._places_cache.set(key, places)
        return places

    async def place_by_id(self, place_id: str) -> Place | None:
        key = ("byid", place_id)
        cached = self._places_cache.get(key)
        if cached is not None:
            return cached
        data = await self._get("places", f"{self._s.dgis_catalog_url}/3.0/items/byid",
                               {"id": place_id, "fields": self._s.dgis_place_fields, "locale": self._locale})
        items = self._items(data)
        place = parse_place(items[0], self._catalog) if items else None
        if place:
            self._places_cache.set(key, place)
        return place

    async def suggest(self, q: str, lat: float, lon: float) -> list[dict]:
        key = ("suggest", q.lower(), round(lat, 2), round(lon, 2))
        cached = self._short_cache.get(key)
        if cached is not None:
            return cached
        data = await self._get("suggest", f"{self._s.dgis_catalog_url}/3.0/suggests",
                               {"q": q, "location": f"{lon},{lat}", "fields": "items.point", "locale": self._locale})
        out = []
        for it in self._items(data):
            point = it.get("point") or {}
            out.append({
                "id": it.get("id"),
                "title": it.get("name") or it.get("full_name") or "",
                "subtitle": it.get("address_name") or it.get("full_name") or "",
                "type": it.get("type"),
                "lat": point.get("lat"),
                "lon": point.get("lon"),
            })
        self._short_cache.set(key, out)
        return out

    async def geocode(self, q: str, lat: float, lon: float) -> list[dict]:
        data = await self._get("geocoder", f"{self._s.dgis_catalog_url}/3.0/items/geocode",
                               {"q": q, "location": f"{lon},{lat}", "fields": "items.point", "locale": self._locale})
        out = []
        for it in self._items(data):
            point = it.get("point") or {}
            if point:
                out.append({"id": it.get("id"), "title": it.get("full_name") or it.get("name"), "type": it.get("type"),
                            "lat": point.get("lat"), "lon": point.get("lon")})
        return out

    async def matrix(self, points: list[tuple[float, float]], sources: list[int], targets: list[int],
                     transport: str) -> dict[tuple[int, int], tuple[float, float]]:
        """Время (с) и расстояние (м) между точками.

        Источники и цели режутся на пачки: у демо-ключа матрица не больше 10 × 10 (иначе HTTP 403).
        """
        result: dict[tuple[int, int], tuple[float, float]] = {}
        if not targets or not sources:
            return result
        blocks = [(sources[i : i + self._s.matrix_max_sources], targets[j : j + self._s.matrix_max_targets])
                  for i in range(0, len(sources), max(1, self._s.matrix_max_sources))
                  for j in range(0, len(targets), max(1, self._s.matrix_max_targets))]
        for src, chunk in blocks:
            used = sorted(set(src) | set(chunk))
            remap = {orig: n for n, orig in enumerate(used)}
            body = {
                "points": [{"lat": points[k][0], "lon": points[k][1]} for k in used],
                "sources": [remap[s] for s in src],
                "targets": [remap[t] for t in chunk],
                "transport": transport,
            }
            if transport in ("driving", "taxi"):
                body["type"] = "jam"
            key = ("matrix", transport, tuple((round(points[k][0], 5), round(points[k][1], 5)) for k in used),
                   tuple(body["sources"]), tuple(body["targets"]))
            data = self._short_cache.get(key)
            if data is None:
                data = await self._post("matrix", f"{self._s.dgis_routing_url}/get_dist_matrix", body,
                                        params={"version": "2.0"})
                self._short_cache.set(key, data)
            back = {n: orig for orig, n in remap.items()}
            for route in data.get("routes") or []:
                if route.get("status", "OK") != "OK":
                    continue
                s, t = back.get(route.get("source_id")), back.get(route.get("target_id"))
                if s is None or t is None or route.get("duration") is None:
                    continue
                result[(s, t)] = (float(route["duration"]), float(route.get("distance") or 0))
        return result

    async def route(self, points: list[tuple[float, float]], transport: str) -> dict:
        if transport == "public_transport":
            return await self.public_transport(points[0], points[-1])
        try:
            return await self._dgis_route(points, transport)
        except DgisError as exc:
            if transport not in OSM_PROFILES:
                raise
            log.warning("2gis routing failed (%s), using OpenStreetMap", str(exc)[:80])
            out = await self._osm_route(points, transport)
            out["note"] = "Маршрут по картам OpenStreetMap: прокладка 2ГИС сейчас недоступна."
            return out

    async def _osm_route(self, points: list[tuple[float, float]], transport: str) -> dict:
        key = ("osm", transport, tuple((round(lat, 4), round(lon, 4)) for lat, lon in points))
        data = self._short_cache.get(key)
        if data is None:
            coords = ";".join(f"{lon},{lat}" for lat, lon in points)
            url = f"https://routing.openstreetmap.de/{OSM_PROFILES[transport]}/route/v1/driving/{coords}"
            try:
                r = await self._http.get(url, params={"overview": "full", "geometries": "geojson", "steps": "true",
                                                      "alternatives": "true" if len(points) == 2 else "false"},
                                         headers={"User-Agent": "Progulka-hackathon/0.4 (github.com/MAXAWER/MThread-Maps)"},
                                         timeout=10.0)
            except httpx.HTTPError as exc:
                raise DgisError(f"osm: {type(exc).__name__}") from exc
            if r.status_code >= 400:
                raise DgisError(f"osm: http {r.status_code}")
            data = r.json()
            self._short_cache.set(key, data)
        out = parse_osm_route(data)
        out["source"] = "osm"
        return out

    async def _dgis_route(self, points: list[tuple[float, float]], transport: str) -> dict:
        body = {
            "points": [{"type": "stop", "lat": lat, "lon": lon} for lat, lon in points],
            "transport": transport,
            "output": "detailed",
            "locale": "ru",
        }
        if transport in ("driving", "taxi"):
            body["traffic_mode"] = "jam"
        if len(points) == 2:
            body["alternative"] = 2  # до двух альтернатив к основному варианту
        # Квота демо-ключа 2ГИС мала: тот же маршрут (точки с точностью ~10 м) 10 минут берём из кэша —
        # агент, повторные открытия шторки и смена вариантов не тратят запросы.
        key = ("route", transport, tuple((round(lat, 4), round(lon, 4)) for lat, lon in points))
        data = self._short_cache.get(key)
        if data is None:
            data = await self._post("routing", f"{self._s.dgis_routing_url}/routing/7.0.0/global", body, timeout=8.0)
            self._short_cache.set(key, data)
        out = parse_route(data)
        out["source"] = "routing"
        return out

    async def public_transport(self, a: tuple[float, float], b: tuple[float, float]) -> dict:
        body = {
            "locale": "ru",
            "source": {"name": "Старт", "point": {"lat": a[0], "lon": a[1]}},
            "target": {"name": "Финиш", "point": {"lat": b[0], "lon": b[1]}},
            "transport": ["bus", "trolleybus", "tram", "shuttle_bus", "metro", "light_rail"],
        }
        # Ответ кэшируется, а разбирается каждый раз заново: «через N минут» считается от текущего времени.
        key = ("public_transport", round(a[0], 4), round(a[1], 4), round(b[0], 4), round(b[1], 4))
        data = self._short_cache.get(key)
        if data is None:
            data = await self._post("public_transport", f"{self._s.dgis_routing_url}/public_transport/2.0", body,
                                    timeout=8.0)
            self._short_cache.set(key, data)
        out = parse_public_transport(data, a, now_utc=time.time())
        out["source"] = "public_transport"
        return out
