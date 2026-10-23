"""Фото мест для колоды «Для вас».

Places API на демо-ключе фото не отдаёт, поэтому берём открытые источники:
1) картинку-превью сайта заведения (og:image / twitter:image — то же, что показывают мессенджеры);
2) для парков, памятников, музеев — фото статьи Википедии рядом с точкой, если её название совпадает с местом.
Источник возвращается вместе со ссылкой: в карточке он подписан. Нет фото — карточка с обложкой категории.
"""
from __future__ import annotations

import asyncio
import html
import logging
import re
from urllib.parse import urljoin, urlparse

import httpx

from .cache import TTLCache
from .dgis import Place

log = logging.getLogger(__name__)

# Википедия требует User-Agent с контактной ссылкой (иначе 403).
UA = {"User-Agent": "ProgulkaBot/0.4 (https://github.com/MAXAWER/MThread-Maps) python-httpx"}
META_IMAGE = re.compile(
    r"""<meta[^>]+(?:property|name)\s*=\s*["'](?:og:image(?::secure_url)?|twitter:image)["'][^>]*>""", re.I)
CONTENT = re.compile(r"""content\s*=\s*["']([^"']+)["']""", re.I)
WIKI_GROUPS = {"culture", "walks", "kids"}
# Соцсети отдают заглушки вместо превью — их картинки не берём.
SKIP_HOSTS = ("instagram.com", "facebook.com", "vk.com", "t.me", "wa.me", "2gis.", "tiktok.com")

_cache = TTLCache(24 * 3600, max_items=2000)


def _words(s: str) -> set[str]:
    return {w for w in re.findall(r"[a-zа-яәіңғүұқөһё0-9]{4,}", s.lower())}


async def _og_image(http: httpx.AsyncClient, site: str) -> str | None:
    url = site if site.startswith(("http://", "https://")) else f"https://{site}"
    if any(h in urlparse(url).netloc for h in SKIP_HOSTS):
        return None
    try:
        r = await http.get(url, headers=UA, timeout=4.0, follow_redirects=True)
    except httpx.HTTPError:
        return None
    if r.status_code >= 400 or "html" not in r.headers.get("content-type", ""):
        return None
    head = r.text[:200_000]
    for tag in META_IMAGE.findall(head):
        m = CONTENT.search(tag)
        if m:
            img = urljoin(str(r.url), html.unescape(m.group(1).strip()))
            if img.startswith("https://") and not img.lower().endswith((".svg", ".ico")):
                return img
    return None


async def _wiki_image(http: httpx.AsyncClient, p: Place) -> str | None:
    """Статья ru-Википедии в 400 м от точки, чьё название пересекается с названием места, — её главное фото."""
    params = {"action": "query", "format": "json", "generator": "geosearch", "ggscoord": f"{p.lat}|{p.lon}",
              "ggsradius": 400, "ggslimit": 10, "prop": "pageimages", "piprop": "thumbnail", "pithumbsize": 900}
    try:
        r = await http.get("https://ru.wikipedia.org/w/api.php", params=params, headers=UA, timeout=4.0)
        pages = (r.json().get("query") or {}).get("pages") or {}
    except (httpx.HTTPError, ValueError):
        return None
    name = _words(p.name)
    for page in pages.values():
        thumb = (page.get("thumbnail") or {}).get("source")
        if thumb and name & _words(page.get("title", "")):
            return thumb
    return None


_osm_cache = TTLCache(3600, max_items=50)
# Публичные серверы Overpass бывают перегружены — пробуем зеркала по очереди.
OVERPASS = ("https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter",
            "https://overpass.private.coffee/api/interpreter")


async def osm_index(http: httpx.AsyncClient, lat: float, lon: float, radius_m: int) -> list[dict]:
    """Объекты OpenStreetMap вокруг точки, у которых есть сайт, фото или статья Википедии (один запрос на колоду)."""
    key = (round(lat, 2), round(lon, 2), radius_m // 500)
    cached = _osm_cache.get(key)
    if cached is not None:
        return cached
    around = f"(around:{radius_m},{lat},{lon})"
    q = ("[out:json][timeout:6];(" + "".join(f'nwr{around}["name"]["{k}"];' for k in
         ("website", "contact:website", "image", "wikimedia_commons", "wikipedia")) + ");out tags center 800;")
    elements = None
    for endpoint in OVERPASS:
        try:
            r = await http.post(endpoint, data={"data": q}, headers=UA, timeout=4.0)
            if r.status_code == 200:
                elements = r.json().get("elements") or []
                break
        except (httpx.HTTPError, ValueError):
            continue
    if elements is None:
        return []  # все зеркала недоступны — не кэшируем, в следующий раз попробуем снова
    out = []
    for e in elements:
        c = e.get("center") or {"lat": e.get("lat"), "lon": e.get("lon")}
        tags = e.get("tags") or {}
        if c.get("lat") is None:
            continue
        out.append({"lat": c["lat"], "lon": c["lon"], "names": {tags.get(k, "").lower() for k in ("name", "name:ru", "name:en", "brand") if tags.get(k)},
                    "website": tags.get("website") or tags.get("contact:website"), "image": tags.get("image"),
                    "commons": tags.get("wikimedia_commons"), "wikipedia": tags.get("wikipedia")})
    _osm_cache.set(key, out)
    return out


def osm_match(p: Place, index: list[dict]) -> dict | None:
    """Объект OSM, который и есть это место: имя пересекается, до 200 м."""
    from .geo import haversine_m
    want = _words(p.name.split(",")[0])
    best, best_d = None, 201.0
    for o in index:
        d = haversine_m(p.lat, p.lon, o["lat"], o["lon"])
        if d < best_d and any(want & _words(n) for n in o["names"]):
            best, best_d = o, d
    return best


def _commons_url(value: str) -> str | None:
    """«File:Photo.jpg» из тега wikimedia_commons → прямая ссылка на картинку шириной 900."""
    if not value.lower().startswith("file:"):
        return None
    return "https://commons.wikimedia.org/wiki/Special:FilePath/" + value[5:].strip().replace(" ", "_") + "?width=900"


async def _wiki_title_image(http: httpx.AsyncClient, tag: str) -> str | None:
    """Тег wikipedia «ru:Название» → главное фото статьи."""
    lang, _, title = tag.partition(":")
    if not title:
        return None
    try:
        r = await http.get(f"https://{lang}.wikipedia.org/w/api.php", headers=UA, timeout=4.0, params={
            "action": "query", "format": "json", "titles": title, "prop": "pageimages", "piprop": "thumbnail", "pithumbsize": 900})
        pages = (r.json().get("query") or {}).get("pages") or {}
    except (httpx.HTTPError, ValueError):
        return None
    return next(((pg.get("thumbnail") or {}).get("source") for pg in pages.values() if pg.get("thumbnail")), None)


async def photo_for(http: httpx.AsyncClient, p: Place, group: str | None, osm: dict | None = None) -> tuple[str | None, str | None]:
    """(ссылка на фото, источник для подписи) или (None, None)."""
    cached = _cache.get(p.id)
    if cached is not None:
        return cached
    url, source = None, None
    if osm and osm.get("image", "").startswith("https://"):
        url, source = osm["image"], "OpenStreetMap"
    if url is None and osm and osm.get("commons"):
        url = _commons_url(osm["commons"])
        source = "Wikimedia Commons" if url else None
    if url is None and osm and osm.get("wikipedia"):
        url = await _wiki_title_image(http, osm["wikipedia"])
        source = "Википедия" if url else None
    site = p.website or (osm or {}).get("website")
    if url is None and site:
        url = await _og_image(http, site)
        source = urlparse(url).netloc.removeprefix("www.") if url else None
    if url is None and group in WIKI_GROUPS:
        url = await _wiki_image(http, p)
        source = "Википедия" if url else None
    _cache.set(p.id, (url, source))
    return url, source


async def photos_for(http: httpx.AsyncClient, items: list[tuple[Place, str | None]], limit: float = 9.0,
                     center: tuple[float, float] | None = None, radius_m: int = 3000) -> dict[str, tuple]:
    """Фото для пачки мест параллельно (до 6 запросов разом), не дольше limit секунд в сумме."""
    gate = asyncio.Semaphore(6)
    try:
        index = await asyncio.wait_for(osm_index(http, center[0], center[1], radius_m), 7.0) if center else []
    except asyncio.TimeoutError:
        index = []

    async def one(p: Place, group: str | None):
        async with gate:
            try:
                return p.id, await photo_for(http, p, group, osm_match(p, index))
            except Exception as exc:  # фото — украшение: любая ошибка не ломает колоду
                log.warning("photo failed for %s: %s", p.id, type(exc).__name__)
                return p.id, (None, None)

    tasks = [asyncio.create_task(one(p, g)) for p, g in items]
    done, pending = await asyncio.wait(tasks, timeout=limit)
    for t in pending:
        t.cancel()
    return dict(t.result() for t in done if not t.cancelled() and t.exception() is None)
