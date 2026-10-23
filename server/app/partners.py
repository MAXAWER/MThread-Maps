"""Монетизация: партнёры (такси, доставка, билеты), учёт переходов и рекламные места.

Всё настраивается переменными окружения и файлом promotions.json — без договоров работает как обычные ссылки,
после подключения партнёрских программ те же ссылки несут ваш ID и приносят комиссию (CPA/CPC).
"""
from __future__ import annotations

import json
import logging
import math
import os
import time
from collections import Counter
from pathlib import Path
from urllib.parse import urlencode, urlparse

import httpx

log = logging.getLogger(__name__)

# ---------- такси ----------
# Яндекс Go: официальный формат ссылки на заказ (support/taxi-distr/api/deeplinks). app_code 3 — Яндекс Go,
# appmetrica_tracking_id 1178268795219780156 — без приложения ведёт в магазин приложений.
YANDEX_GO_LINK = "https://3.redirect.appmetrica.yandex.com/route"
YANDEX_GO_STORE_REDIRECT = "1178268795219780156"
YANDEX_TARIFFS = [("econom", "Эконом"), ("business", "Комфорт"), ("comfortplus", "Комфорт+")]
# Оценка без партнёрского API: подача + за км + за минуту, ₸ (порядок цен такси в Астане; честно помечается «оценка»).
ESTIMATE_TARIFFS = {"econom": (450, 110, 18), "business": (650, 150, 24), "comfortplus": (900, 190, 30)}


def yandex_go_link(a: tuple[float, float], b: tuple[float, float], tariff: str = "econom") -> str:
    params = {"start-lat": f"{a[0]:.6f}", "start-lon": f"{a[1]:.6f}", "end-lat": f"{b[0]:.6f}", "end-lon": f"{b[1]:.6f}",
              "tariffClass": tariff, "ref": os.getenv("YANDEX_TAXI_REF", "progulka"),
              "appmetrica_tracking_id": YANDEX_GO_STORE_REDIRECT, "lang": "ru"}
    return f"{YANDEX_GO_LINK}?{urlencode(params)}"


def estimate_price(tariff: str, distance_m: int, duration_s: int) -> tuple[int, int]:
    """Вилка цены по таблице, округлённая до 50 ₸: ±15 % на пробки и спрос."""
    base, per_km, per_min = ESTIMATE_TARIFFS[tariff]
    p = base + per_km * distance_m / 1000 + per_min * duration_s / 60
    lo, hi = p * 0.85, p * 1.15
    return int(math.floor(lo / 50) * 50), int(math.ceil(hi / 50) * 50)


async def yandex_prices(http: httpx.AsyncClient, a: tuple[float, float], b: tuple[float, float]) -> list[dict] | None:
    """Цены Яндекс Go через API расчёта стоимости (нужны YANDEX_TAXI_CLID и YANDEX_TAXI_APIKEY партнёра)."""
    clid, key = os.getenv("YANDEX_TAXI_CLID"), os.getenv("YANDEX_TAXI_APIKEY")
    if not clid or not key:
        return None
    try:
        r = await http.get("https://taxi-routeinfo.taxi.yandex.net/taxi_info", timeout=5.0,
                           headers={"YaTaxi-Api-Key": key},
                           params={"clid": clid, "rll": f"{a[1]},{a[0]}~{b[1]},{b[0]}",
                                   "class": ",".join(t for t, _ in YANDEX_TARIFFS)})
        data = r.json() if r.status_code == 200 else None
    except (httpx.HTTPError, ValueError):
        data = None
    if not data:
        return None
    out = []
    for opt in data.get("options") or []:
        out.append({"tariff": opt.get("class_name"), "label": opt.get("class_text") or opt.get("class_name"),
                    "price_min": int(opt.get("min_price") or opt.get("price") or 0), "price_max": int(opt.get("price") or 0),
                    "price_text": opt.get("price_text"), "wait_min": round((opt.get("waiting_time") or 0) / 60) or None,
                    "source": "yandex"})
    return out or None


def taxi_partners(a: tuple[float, float], b: tuple[float, float], distance_m: int, duration_s: int,
                  yandex: list[dict] | None) -> list[dict]:
    """Партнёры такси: цены по тарифам и ссылка, открывающая их приложение с точками маршрута."""
    prices = yandex or [{"tariff": t, "label": label, **dict(zip(("price_min", "price_max"), estimate_price(t, distance_m, duration_s))),
                         "price_text": None, "wait_min": None, "source": "estimate"} for t, label in YANDEX_TARIFFS]
    partners = [{
        "id": "yandex_go", "name": "Яндекс Go", "prefill": True, "android_package": "ru.yandex.taxi",
        "store_url": "https://play.google.com/store/apps/details?id=ru.yandex.taxi",
        "prices": [{**p, "deeplink": yandex_go_link(a, b, p["tariff"] or "econom")} for p in prices],
    }]
    # inDrive: публичного формата ссылки с точками нет — приложение открывается, адрес назначения копируется.
    partners.append({
        "id": "indrive", "name": "inDrive", "prefill": False, "android_package": "sinet.startup.inDriver",
        "store_url": "https://play.google.com/store/apps/details?id=sinet.startup.inDriver",
        "prices": [{"tariff": "offer", "label": "Своя цена", "price_min": prices[0]["price_min"], "price_max": prices[0]["price_max"],
                    "price_text": None, "wait_min": None, "source": "estimate", "deeplink": None}],
    })
    return partners


# ---------- партнёрские ссылки из найденного ИИ ----------
# Домены, на которые ИИ сослался в фактах (меню, билеты), превращаются в кнопки действий. Параметры
# партнёрских программ подставляются из окружения, например AFF_GLOVOAPP_COM="utm_source=progulka&aff=123".
LINK_KINDS = {
    "glovoapp.com": ("delivery", "Glovo", "Заказать в Glovo"),
    "wolt.com": ("delivery", "Wolt", "Заказать в Wolt"),
    "eda.yandex.kz": ("delivery", "Яндекс Еда", "Заказать в Яндекс Еде"),
    "eda.yandex.ru": ("delivery", "Яндекс Еда", "Заказать в Яндекс Еде"),
    "chocofood.kz": ("delivery", "Chocofood", "Заказать в Chocofood"),
    "ticketon.kz": ("tickets", "Ticketon", "Билеты на Ticketon"),
    "kino.kz": ("tickets", "Kino.kz", "Билеты на Kino.kz"),
    "restoplace.kz": ("booking", "Restoplace", "Забронировать столик"),
}


def with_affiliate(url: str) -> str:
    host = urlparse(url).netloc.removeprefix("www.")
    extra = os.getenv("AFF_" + host.upper().replace(".", "_").replace("-", "_"))
    if not extra:
        return url
    return url + ("&" if "?" in url else "?") + extra


def partner_links(sources: list[str | None]) -> list[dict]:
    """Кнопки «Заказать доставку», «Билеты», «Забронировать» — только на реальные страницы, найденные ИИ."""
    out, seen = [], set()
    for src in sources:
        if not src:
            continue
        host = urlparse(src).netloc.removeprefix("www.")
        match = next((v for k, v in LINK_KINDS.items() if host == k or host.endswith("." + k)), None)
        if match and match[0] not in seen:
            seen.add(match[0])
            out.append({"kind": match[0], "partner": match[1], "label": match[2], "url": with_affiliate(src)})
    return out


# ---------- реклама (продвигаемые места) ----------
# promotions.json (или переменная PROMOTIONS_JSON): [{"poi_id" | "brand": ..., "label": "Реклама", "offer": "Скидка 10% по коду",
#  "promo_code": "PROGULKA", "boost": 0.15, "until": "2026-12-31"}]. По закону РК о рекламе такая карточка помечается «Реклама».
_promos: list[dict] | None = None


def promotions() -> list[dict]:
    global _promos
    if _promos is None:
        raw = os.getenv("PROMOTIONS_JSON")
        path = Path(__file__).resolve().parent.parent / "data" / "promotions.json"
        try:
            _promos = json.loads(raw) if raw else (json.loads(path.read_text("utf-8")) if path.exists() else [])
        except (ValueError, OSError) as exc:
            log.warning("promotions unreadable: %s", exc)
            _promos = []
    today = time.strftime("%Y-%m-%d")
    return [p for p in _promos if not p.get("until") or p["until"] >= today]


def promotion_for(poi_id: str, name: str) -> dict | None:
    brand = name.split(",")[0].strip().lower()
    for p in promotions():
        if p.get("poi_id") == poi_id or (p.get("brand") and p["brand"].strip().lower() == brand):
            return {"label": p.get("label") or "Реклама", "offer": p.get("offer"), "promo_code": p.get("promo_code"),
                    "boost": float(p.get("boost") or 0.1)}
    return None


# ---------- учёт переходов и показов ----------
class ClickStats:
    """Переходы к партнёрам и показы рекламы: основа для счётов партнёрам (CPC/CPA). Пишется в журнал и в память."""

    def __init__(self) -> None:
        self.counts: Counter[str] = Counter()

    def hit(self, kind: str, partner: str, poi_id: str | None = None, value: int | None = None) -> None:
        self.counts[f"{kind}:{partner}"] += 1
        log.info("clickout %s", json.dumps({"kind": kind, "partner": partner, "poi_id": poi_id, "value": value}, ensure_ascii=False))


STATS = ClickStats()
