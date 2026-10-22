"""Демо-справочник мест вместо 2ГИС: синтетические места вокруг ориентиров Астаны.

Только для разработки и показа без ключа 2ГИС. Названия помечены «Демо», данные выдуманы;
в интерфейсе ответы идут с меткой «Демо-данные», а не «Данные 2ГИС».
"""
from __future__ import annotations

import hashlib
import math
import random

from ..catalog import Catalog
from ..dgis import Place, UsageCounters
from ..geo import haversine_m

DAYS = ("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

ANCHORS = [
    ("MSW", "Mega Silk Way", 51.0890, 71.4086),
    ("KSH", "Хан Шатыр", 51.1324, 71.4036),
    ("BTR", "Байтерек", 51.1283, 71.4305),
    ("EXP", "Экспо", 51.0889, 71.4158),
    ("NQZ", "Аэропорт", 51.0222, 71.4669),
    ("OLD", "Старый город", 51.1694, 71.4491),
]

# Ориентиры для поиска по названию; координаты приблизительные, режим работы и рейтинг не указаны.
LANDMARKS = [
    ("Хан Шатыр", "mall", 51.1324, 71.4036),
    ("Байтерек", "viewpoint", 51.1283, 71.4305),
    ("Mega Silk Way", "mall", 51.0890, 71.4086),
    ("Нур Алем", "museum", 51.0899, 71.4161),
    ("Аэропорт Нурсултан Назарбаев", "architecture", 51.0222, 71.4669),
]

PURCHASE_WORDS = {
    "зонт": ("supermarket", "mall"),
    "дождевик": ("supermarket", "mall"),
    "повербанк": ("mobile_store", "mall"),
    "продукты": ("supermarket",),
    "подарки": ("souvenirs", "mall"),
}

ATTRS = {
    "wifi": ("wifi", "Wi-Fi"),
    "card": ("card_payment", "Оплата картой"),
    "terrace": ("summer_terrace", "Летняя веранда"),
    "parking": ("parking", "Парковка"),
    "ramp": ("ramp", "Пандус"),
    "halal": ("halal", "Халал"),
    "veg": ("vegetarian_menu", "Вегетарианское меню"),
    "kids_room": ("kids_room", "Детская комната"),
    "toilet": ("toilet", "Туалет"),
    "pets": ("pets_allowed", "Можно с животными"),
}


def _daily(start: str, end: str, days=DAYS) -> dict:
    return {d: {"working_hours": [{"from": start, "to": end}]} for d in days}


SCHEDULES = {
    "coffee": _daily("08:00", "22:00"), "bakery": _daily("07:30", "21:00"), "confectionery": _daily("09:00", "22:00"),
    "cafe": _daily("10:00", "23:00"), "canteen": _daily("09:00", "20:00"), "chaikhana": _daily("11:00", "23:30"),
    "fastfood": _daily("09:00", "23:00"), "restaurant": _daily("12:00", "24:00"), "bar": _daily("18:00", "02:00"),
    "pub": _daily("17:00", "02:00"), "museum": _daily("10:00", "18:00", DAYS[1:]),
    "gallery": _daily("11:00", "19:00", DAYS[1:]), "interactive_museum": _daily("10:00", "20:00"),
    "theatre": _daily("11:00", "21:00"), "concert_hall": _daily("11:00", "21:00"), "cinema": _daily("10:00", "02:00"),
    "library": _daily("09:00", "20:00", DAYS[:6]), "mall": _daily("10:00", "22:00"),
    "supermarket": _daily("08:00", "23:00"), "bookstore": _daily("10:00", "21:00"), "souvenirs": _daily("10:00", "21:00"),
    "market": _daily("08:00", "18:00"), "zoo": _daily("10:00", "19:00"), "oceanarium": _daily("10:00", "20:00"),
    "botanical_garden": _daily("09:00", "20:00"), "attractions": _daily("10:00", "21:00"),
    "kids_center": _daily("10:00", "22:00"), "skating_rink": _daily("10:00", "22:00"), "bowling": _daily("12:00", "02:00"),
    "billiards": _daily("14:00", "04:00"), "quest": _daily("10:00", "23:00"), "karaoke": _daily("19:00", "05:00"),
    "vr_club": _daily("11:00", "23:00"), "fitness": _daily("07:00", "23:00"), "pool": _daily("07:00", "22:00"),
    "climbing": _daily("10:00", "22:00"), "bike_rental": _daily("09:00", "21:00"),
    "nightclub": _daily("22:00", "05:00", ("Fri", "Sat")), "lounge": _daily("16:00", "03:00"),
    "cafe_24h": {"is_24x7": True}, "atm": {"is_24x7": True}, "pharmacy": _daily("08:00", "23:00"),
    "mobile_store": _daily("10:00", "21:00"), "flowers": _daily("08:00", "22:00"),
    "public_toilet": _daily("08:00", "22:00"), "viewpoint": _daily("10:00", "22:00"),
}
ALWAYS_OPEN = {"park", "square", "embankment", "monument", "architecture", "pedestrian_street", "playground"}


def _pid(*parts: str) -> str:
    return "demo_" + hashlib.md5("|".join(parts).encode("utf-8")).hexdigest()[:12]


def _offset(lat: float, lon: float, dist_m: float, bearing: float) -> tuple[float, float]:
    dlat = dist_m * math.cos(bearing) / 111_320.0
    dlon = dist_m * math.sin(bearing) / (111_320.0 * math.cos(math.radians(lat)))
    return lat + dlat, lon + dlon


def _attributes(rnd: random.Random, cat) -> list[tuple[str, str]]:
    probs: dict[str, float] = {"card": 0.7, "ramp": 0.3, "parking": 0.3}
    if cat.group == "food":
        probs.update(wifi=0.65, card=0.9, terrace=0.3, parking=0.4, toilet=0.6, halal=0.3, veg=0.15, kids_room=0.15)
        if cat.id in ("chaikhana", "canteen", "cafe", "fastfood"):
            probs["halal"] = 0.6
        if cat.id == "coffee":
            probs["pets"] = 0.25
    elif cat.id == "mall":
        probs.update(wifi=0.8, card=1.0, parking=0.9, ramp=0.8, toilet=0.95, kids_room=0.4)
    elif cat.group in ("culture", "kids", "fun"):
        probs.update(toilet=0.6, wifi=0.3)
    return [ATTRS[k] for k, p in probs.items() if rnd.random() < p]


class FakeDgis:
    name = "demo"

    def __init__(self, catalog: Catalog, counters: UsageCounters):
        self._catalog = catalog
        self.counters = counters
        self._places = self._generate()
        self._by_id = {p.id: p for p in self._places}

    def _generate(self) -> list[Place]:
        out: list[Place] = []
        for code, anchor, alat, alon in ANCHORS:
            for cat in self._catalog.categories.values():
                rnd = random.Random(f"{code}|{cat.id}")
                n = 3 if cat.group == "food" else (2 if cat.group in ("walks", "services", "shopping") else 1)
                for k in range(n):
                    near, far = (120, 1600)
                    if code == "NQZ" and cat.id == "coffee":
                        near, far = (1750, 2150)  # у аэропорта кофеен рядом нет — сценарий 6
                    lat, lon = _offset(alat, alon, rnd.uniform(near, far), rnd.uniform(0, 2 * math.pi))
                    if cat.id in ALWAYS_OPEN:
                        schedule = {"is_24x7": True} if rnd.random() < 0.5 else None
                    else:
                        schedule = None if rnd.random() < 0.1 else SCHEDULES.get(cat.id, _daily("10:00", "22:00"))
                    rating = reviews = None
                    if rnd.random() > 0.15:
                        rating = round(rnd.uniform(3.5, 5.0), 1)
                        reviews = int(rnd.expovariate(1 / 250)) + (0 if rnd.random() < 0.2 else 5)
                    attrs = _attributes(rnd, cat)
                    avg = None
                    if cat.group == "food":
                        low = rnd.choice([1500, 2000, 3000, 4500, 7000])
                        attrs.append(("avg_price", f"Средний чек {low}–{low + 1500} тнг"))
                        avg = low
                    branches = rnd.choice([1, 1, 1, 2, 8, 25]) if cat.group == "food" else 1
                    out.append(Place(
                        id=_pid(code, cat.id, str(k)),
                        name=f"{cat.name_ru} «Демо {code}-{k + 1}»",
                        lat=lat, lon=lon,
                        address=f"Демо-адрес рядом с ориентиром «{anchor}»",
                        rubrics=[cat.name_ru], category=cat.id, schedule=schedule,
                        rating=rating, reviews=reviews,
                        attributes=[f"{t}|{n}".lower() for t, n in attrs],
                        attribute_labels=[n for _, n in attrs],
                        branch_count=branches, phone="+7 700 000 00 00", avg_check=avg, demo=True,
                    ))
        for name, cat_id, lat, lon in LANDMARKS:
            cat = self._catalog.categories[cat_id]
            out.append(Place(id=_pid("landmark", name), name=name, lat=lat, lon=lon,
                             address="Астана (демо: координаты приблизительные)", rubrics=[cat.name_ru],
                             category=cat_id, demo=True))
        return out

    def _categories_for(self, q: str) -> set[str] | None:
        q = q.lower().strip()
        for word, cats in PURCHASE_WORDS.items():
            if word in q:
                return set(cats)
        for cat in self._catalog.categories.values():
            if q == cat.q or q.endswith(" " + cat.q):
                return {cat.id}
        cid = self._catalog.match_rubrics([q])
        return {cid} if cid else None

    async def places(self, *, q: str, lat: float, lon: float, radius_m: int, sort: str | None = None,
                     work_time: str | None = None, page_size: int | None = None, page: int = 1,
                     opened_after=None, extra: dict | None = None) -> list[Place]:
        self.counters.hit("places")
        ql = q.lower()
        cats = self._categories_for(ql)
        words = [w for w in ql.replace("«", " ").replace("»", " ").split() if len(w) >= 3]
        need = [k for k, w in (("halal", "халяль"), ("vegetarian", "вегетариан")) if w in ql]
        found: list[tuple[float, Place]] = []
        for p in self._places:
            d = haversine_m(lat, lon, p.lat, p.lon)
            if d > radius_m:
                continue
            if cats is not None:
                if p.category not in cats:
                    continue
            elif not words or not all(w[:5] in p.name.lower() for w in words[:2]):
                continue
            if any(not any(k in a for a in p.attributes) for k in need):
                continue
            found.append((d, p))
        if sort == "rating":
            found.sort(key=lambda x: -(x[1].rating or 0))
        else:
            found.sort(key=lambda x: x[0])
        size = min(page_size or 10, 10)
        return [p for _, p in found[(page - 1) * size : page * size]]

    async def place_by_id(self, place_id: str) -> Place | None:
        return self._by_id.get(place_id)

    async def suggest(self, q: str, lat: float, lon: float) -> list[dict]:
        self.counters.hit("suggest")
        ql = q.lower().strip()
        items = []
        for cat in self._catalog.categories.values():
            if cat.name_ru.lower().startswith(ql):
                items.append({"id": None, "title": cat.name_ru, "subtitle": "Категория", "type": "rubric",
                              "lat": None, "lon": None})
        near = sorted((p for p in self._places if ql in p.name.lower()),
                      key=lambda p: haversine_m(lat, lon, p.lat, p.lon))[:8]
        items += [{"id": p.id, "title": p.name, "subtitle": p.address, "type": "branch", "lat": p.lat, "lon": p.lon}
                  for p in near]
        return items[:10]

    async def geocode(self, q: str, lat: float, lon: float) -> list[dict]:
        self.counters.hit("geocoder")
        ql = q.lower()
        return [{"id": code, "title": name, "type": "landmark", "lat": alat, "lon": alon}
                for code, name, alat, alon in ANCHORS if name.lower() in ql or ql in name.lower()]

    async def matrix(self, points, sources, targets, transport) -> dict:
        self.counters.hit("matrix")
        speeds = {"walking": 4.5, "bicycle": 15.0, "scooter": 12.0, "driving": 25.0, "taxi": 25.0,
                  "public_transport": 15.0}
        speed = speeds.get(transport, 4.5) * 1000 / 3600
        extra = {"public_transport": 360, "driving": 180, "taxi": 300}.get(transport, 0)
        out = {}
        for s in sources:
            for t in targets:
                if s == t:
                    continue
                dist = haversine_m(points[s][0], points[s][1], points[t][0], points[t][1]) * 1.3
                out[(s, t)] = (dist / speed + extra, dist)
        return out

    async def route(self, points, transport) -> dict:
        """Демо-маршрут: «Г-образные» отрезки между точками, чтобы навигатору было что показать."""
        self.counters.hit("routing")
        speeds = {"walking": 4.5, "bicycle": 15.0, "scooter": 12.0, "driving": 25.0, "taxi": 25.0,
                  "public_transport": 15.0}
        speed = speeds.get(transport, 4.5) * 1000 / 3600
        geometry: list[list[float]] = []
        maneuvers: list[dict] = []
        total = 0.0
        for (alat, alon), (blat, blon) in zip(points, points[1:]):
            corner = (alat, blon)
            leg1 = haversine_m(alat, alon, *corner)
            leg2 = haversine_m(*corner, blat, blon)
            if not geometry:
                geometry.append([alon, alat])
                maneuvers.append({"text": "Начните движение", "icon": "start", "type": "begin", "turn_direction": None,
                                  "lat": alat, "lon": alon, "distance_m": int(leg1), "duration_s": int(leg1 / speed)})
            geometry += [[corner[1], corner[0]], [blon, blat]]
            east = blon > alon
            north = blat > alat
            turn = "left" if (east and north) or (not east and not north) else "right"
            maneuvers.append({"text": f"Поверните {'налево' if turn == 'left' else 'направо'}", "icon": f"turn_{turn}",
                              "type": "crossroad", "turn_direction": turn, "lat": corner[0], "lon": corner[1],
                              "distance_m": int(leg2), "duration_s": int(leg2 / speed)})
            total += leg1 + leg2
        maneuvers.append({"text": "Вы на месте", "icon": "finish", "type": "end", "turn_direction": None,
                          "lat": points[-1][0], "lon": points[-1][1], "distance_m": 0, "duration_s": 0})
        return {"total_distance_m": int(total), "total_duration_s": int(total / speed), "geometry": geometry,
                "maneuvers": maneuvers, "source": "demo"}
