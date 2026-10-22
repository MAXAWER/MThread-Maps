"""Погода: OpenWeather One Call 3.0 (осадки по минутам) + Open-Meteo (перепроверка) — раздел 10."""
from __future__ import annotations

import asyncio
import logging
import math
from datetime import date, datetime, timedelta, timezone

import httpx

from .cache import TTLCache
from .config import Settings
from .geo import geohash
from .models import ASTANA_TZ

log = logging.getLogger(__name__)

OW_URL = "https://api.openweathermap.org/data/3.0/onecall"
OM_URL = "https://api.open-meteo.com/v1/forecast"

RAIN_WINDOW_MIN = 15
RAIN_WINDOW_MM = 0.3
RAIN_SOON_FROM = 15
RAIN_SOON_TO = 45


def sun_times(lat: float, lon: float, d: date, tz: timezone = ASTANA_TZ) -> tuple[datetime | None, datetime | None]:
    """Восход и закат (алгоритм NOAA / Almanac for Computers, точность ±2 мин)."""
    zenith = 90.833
    n = d.timetuple().tm_yday
    lng_hour = lon / 15.0
    out: list[datetime | None] = []
    for rising in (True, False):
        t = n + ((6 if rising else 18) - lng_hour) / 24.0
        m = 0.9856 * t - 3.289
        l = (m + 1.916 * math.sin(math.radians(m)) + 0.020 * math.sin(math.radians(2 * m)) + 282.634) % 360
        ra = math.degrees(math.atan(0.91764 * math.tan(math.radians(l)))) % 360
        ra += (math.floor(l / 90) * 90) - (math.floor(ra / 90) * 90)
        ra /= 15.0
        sin_dec = 0.39782 * math.sin(math.radians(l))
        cos_dec = math.cos(math.asin(sin_dec))
        cos_h = (math.cos(math.radians(zenith)) - sin_dec * math.sin(math.radians(lat))) / (cos_dec * math.cos(math.radians(lat)))
        if cos_h > 1 or cos_h < -1:
            out.append(None)
            continue
        h = (360 - math.degrees(math.acos(cos_h))) if rising else math.degrees(math.acos(cos_h))
        h /= 15.0
        local_mean = h + ra - 0.06571 * t - 6.622
        ut = (local_mean - lng_hour) % 24
        base = datetime(d.year, d.month, d.day, tzinfo=timezone.utc) + timedelta(hours=ut)
        out.append(base.astimezone(tz))
    rise, sset = out
    if rise and rise.date() != d:
        rise = rise.replace(year=d.year, month=d.month, day=d.day)
    if sset and sset.date() != d:
        sset = sset.replace(year=d.year, month=d.month, day=d.day)
    return rise, sset


def rain_onset_from_minutely(values_mmh: list[float]) -> tuple[int | None, float | None]:
    """Первая минута, с которой за 15 минут выпадет ≥ 0,3 мм. Значения — мм/ч по минутам."""
    n = len(values_mmh)
    for s in range(n):
        if values_mmh[s] <= 0:
            continue
        window = values_mmh[s : s + RAIN_WINDOW_MIN]
        if sum(v / 60.0 for v in window) >= RAIN_WINDOW_MM:
            return s, max(window)
    return None, None


class WeatherService:
    def __init__(self, http: httpx.AsyncClient, settings: Settings):
        self._http = http
        self._s = settings
        self._cache = TTLCache(600, max_items=500)
        self.calls = {"openweather": 0, "open_meteo": 0}

    async def get(self, lat: float, lon: float, now: datetime | None = None) -> dict:
        now = now or datetime.now(ASTANA_TZ)
        cell = geohash(lat, lon, 5)
        cached = self._cache.get(cell)
        if cached is None:
            cached = await self._fetch(lat, lon)
            self._cache.set(cell, cached)
        result = self._derive(cached, lat, lon, now)
        result["geohash"] = cell
        if self._s.weather_override:
            result = apply_override(result, self._s.weather_override)
        return result

    async def _fetch(self, lat: float, lon: float) -> dict:
        tasks = [self._open_meteo(lat, lon)]
        if self._s.openweather_key:
            tasks.append(self._openweather(lat, lon))
        res = await asyncio.gather(*tasks, return_exceptions=True)
        om = res[0] if not isinstance(res[0], Exception) else None
        ow = res[1] if len(res) > 1 and not isinstance(res[1], Exception) else None
        for r in res:
            if isinstance(r, Exception):
                log.warning("weather source failed: %s", type(r).__name__)
        return {"om": om, "ow": ow, "fetched_at": datetime.now(timezone.utc).timestamp()}

    async def _openweather(self, lat: float, lon: float) -> dict:
        self.calls["openweather"] += 1
        r = await self._http.get(OW_URL, params={"lat": lat, "lon": lon, "appid": self._s.openweather_key,
                                                 "units": "metric", "exclude": "alerts,daily"}, timeout=4.0)
        r.raise_for_status()
        return r.json()

    async def _open_meteo(self, lat: float, lon: float) -> dict:
        self.calls["open_meteo"] += 1
        params = {
            "latitude": lat,
            "longitude": lon,
            "hourly": "temperature_2m,apparent_temperature,precipitation_probability,precipitation,wind_speed_10m",
            "current": "temperature_2m,apparent_temperature,precipitation,wind_speed_10m",
            "wind_speed_unit": "ms",
            "timeformat": "unixtime",
            "forecast_days": 2,
            "timezone": "GMT",
        }
        r = await self._http.get(OM_URL, params=params, timeout=4.0)
        r.raise_for_status()
        return r.json()

    def _derive(self, raw: dict, lat: float, lon: float, now: datetime) -> dict:
        ow, om = raw.get("ow"), raw.get("om")
        now_ts = now.timestamp()
        out: dict = {
            "temp_c": None, "feels_like_c": None, "wind_ms": None,
            "precip_now": False, "precip_starts_in_min": None, "precip_intensity_mmh": None,
            "precision": None, "confirmed": False, "rain_soon": False, "precip_next_3h": False,
            "hourly": [], "source": "unavailable",
        }
        om_hours = _om_hours(om) if om else []
        ow_hours = _ow_hours(ow) if ow else []
        if ow:
            cur = ow.get("current") or {}
            out.update(temp_c=cur.get("temp"), feels_like_c=cur.get("feels_like"), wind_ms=cur.get("wind_speed"))
            minutely = ow.get("minutely") or []
            if minutely:
                shift = int(round((minutely[0].get("dt", now_ts) - now_ts) / 60.0))
                vals = [float(m.get("precipitation") or 0.0) for m in minutely]
                onset, intensity = rain_onset_from_minutely(vals)
                if onset is not None:
                    out["precip_starts_in_min"] = max(0, onset + shift)
                    out["precip_intensity_mmh"] = round(intensity or 0.0, 2)
                    out["precision"] = "minutely"
                out["precip_now"] = bool(vals and vals[0] > 0.1)
            out["source"] = "openweather"
        elif om:
            cur = om.get("current") or {}
            out.update(temp_c=cur.get("temperature_2m"), feels_like_c=cur.get("apparent_temperature"),
                       wind_ms=cur.get("wind_speed_10m"))
            out["precip_now"] = float(cur.get("precipitation") or 0.0) > 0.1
            out["source"] = "open-meteo"
        if out["precip_starts_in_min"] is None and om_hours:
            for h in om_hours:
                start = h["ts"] - 3600  # почасовые осадки Open-Meteo — сумма за предыдущий час
                if start + 3600 <= now_ts or start - now_ts > 3 * 3600:
                    continue
                if (h["precip_mm"] or 0) >= RAIN_WINDOW_MM and (h["pop"] or 0) >= 50:
                    out["precip_starts_in_min"] = max(0, int((start - now_ts) / 60))
                    out["precision"] = "hourly"
                    break
        t_rain = out["precip_starts_in_min"]
        if t_rain is not None:
            hour_ts = now_ts + t_rain * 60
            om_ok = any(h["ts"] - 3600 <= hour_ts < h["ts"] and (h["pop"] or 0) >= 50 for h in om_hours)
            ow_ok = any(h["ts"] <= hour_ts < h["ts"] + 3600 and (h["precip_mm"] or 0) >= 0.5 for h in ow_hours)
            out["confirmed"] = om_ok or ow_ok
            out["rain_soon"] = (out["precision"] == "minutely" and out["confirmed"]
                                and RAIN_SOON_FROM <= t_rain <= RAIN_SOON_TO)
        hours = ow_hours or [dict(h, pop=(h["pop"] or 0) / 100.0) for h in om_hours]
        upcoming = [h for h in hours if h["ts"] > now_ts - 3600][:3]
        out["hourly"] = [{
            "t": datetime.fromtimestamp(h["ts"], ASTANA_TZ).strftime("%H:%M"),
            "temp_c": h["temp_c"], "feels_like_c": h["feels_like_c"], "pop": h["pop"],
            "precip_mm": h["precip_mm"], "wind_ms": h["wind_ms"],
        } for h in upcoming]
        out["precip_next_3h"] = bool(
            (t_rain is not None and t_rain <= 180)
            or any((h["pop"] or 0) >= 0.5 or (h["precip_mm"] or 0) >= RAIN_WINDOW_MM for h in upcoming)
        )
        if ow and om:
            out["source"] = "openweather+open-meteo"
        rise, sset = sun_times(lat, lon, now.astimezone(ASTANA_TZ).date())
        out["sunrise"] = rise.strftime("%H:%M") if rise else None
        out["sunset"] = sset.strftime("%H:%M") if sset else None
        out["minutes_to_sunset"] = int((sset - now).total_seconds() // 60) if sset else None
        out["is_dark"] = bool(rise and sset and not (rise <= now <= sset))
        return out


def _om_hours(om: dict) -> list[dict]:
    h = om.get("hourly") or {}
    times = h.get("time") or []
    get = lambda name, i: (h.get(name) or [None] * len(times))[i]  # noqa: E731
    return [{
        "ts": times[i],
        "temp_c": get("temperature_2m", i),
        "feels_like_c": get("apparent_temperature", i),
        "pop": get("precipitation_probability", i),
        "precip_mm": get("precipitation", i),
        "wind_ms": get("wind_speed_10m", i),
    } for i in range(len(times))]


def _ow_hours(ow: dict) -> list[dict]:
    out = []
    for h in ow.get("hourly") or []:
        precip = float((h.get("rain") or {}).get("1h") or 0.0) + float((h.get("snow") or {}).get("1h") or 0.0)
        out.append({"ts": h.get("dt"), "temp_c": h.get("temp"), "feels_like_c": h.get("feels_like"),
                    "pop": h.get("pop"), "precip_mm": precip, "wind_ms": h.get("wind_speed")})
    return out


def apply_override(result: dict, override: dict) -> dict:
    """WEATHER_OVERRIDE: подставной прогноз для тестовых сценариев (раздел 15)."""
    out = dict(result)
    out.update(override)
    t_rain = out.get("precip_starts_in_min")
    if "precip_starts_in_min" in override and t_rain is not None:
        out.setdefault("precision", "minutely")
        out["precision"] = override.get("precision", "minutely")
        out["confirmed"] = override.get("confirmed", True)
        out["rain_soon"] = override.get("rain_soon", RAIN_SOON_FROM <= t_rain <= RAIN_SOON_TO)
        out["precip_next_3h"] = True
    out["source"] = "override"
    return out


def weather_brief(w: dict | None) -> str:
    """Короткая сводка для промптов."""
    if not w:
        return "нет данных о погоде"
    parts = []
    if w.get("temp_c") is not None:
        parts.append(f"{round(w['temp_c'])} °C")
    if w.get("feels_like_c") is not None:
        parts.append(f"ощущается {round(w['feels_like_c'])} °C")
    if w.get("wind_ms") is not None:
        parts.append(f"ветер {round(w['wind_ms'])} м/с")
    if w.get("precip_now"):
        parts.append("сейчас осадки")
    elif w.get("precip_starts_in_min") is not None:
        parts.append(f"по прогнозу осадки через {w['precip_starts_in_min']} мин")
    elif w.get("precip_next_3h"):
        parts.append("возможны осадки в ближайшие 3 часа")
    if w.get("sunset"):
        parts.append(f"закат в {w['sunset']}")
    return ", ".join(parts) or "нет данных о погоде"


def is_bad_weather(w: dict | None) -> bool:
    if not w:
        return False
    cold = w.get("feels_like_c") if w.get("feels_like_c") is not None else w.get("temp_c")
    return bool(
        w.get("precip_now")
        or (w.get("precip_starts_in_min") is not None and w["precip_starts_in_min"] <= 60)
        or (cold is not None and cold < -15)
        or (w.get("wind_ms") or 0) >= 12
    )
