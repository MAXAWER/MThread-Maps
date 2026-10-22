"""Геоутилиты: geohash, расстояния, округление координат, радиус поиска."""
from __future__ import annotations

import math

_BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"
EARTH_R_M = 6_371_000.0


def geohash(lat: float, lon: float, precision: int = 5) -> str:
    lat_lo, lat_hi = -90.0, 90.0
    lon_lo, lon_hi = -180.0, 180.0
    bits = [16, 8, 4, 2, 1]
    out: list[str] = []
    bit, ch, even = 0, 0, True
    while len(out) < precision:
        if even:
            mid = (lon_lo + lon_hi) / 2
            if lon >= mid:
                ch |= bits[bit]
                lon_lo = mid
            else:
                lon_hi = mid
        else:
            mid = (lat_lo + lat_hi) / 2
            if lat >= mid:
                ch |= bits[bit]
                lat_lo = mid
            else:
                lat_hi = mid
        even = not even
        if bit < 4:
            bit += 1
        else:
            out.append(_BASE32[ch])
            bit, ch = 0, 0
    return "".join(out)


def geohash_center(code: str) -> tuple[float, float]:
    lat_lo, lat_hi = -90.0, 90.0
    lon_lo, lon_hi = -180.0, 180.0
    even = True
    for c in code:
        idx = _BASE32.index(c)
        for mask in (16, 8, 4, 2, 1):
            if even:
                mid = (lon_lo + lon_hi) / 2
                if idx & mask:
                    lon_lo = mid
                else:
                    lon_hi = mid
            else:
                mid = (lat_lo + lat_hi) / 2
                if idx & mask:
                    lat_lo = mid
                else:
                    lat_hi = mid
            even = not even
    return (lat_lo + lat_hi) / 2, (lon_lo + lon_hi) / 2


def haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = p2 - p1
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * EARTH_R_M * math.asin(min(1.0, math.sqrt(a)))


def round_coord(value: float, digits: int = 3) -> float:
    """Округление до 3 знаков ≈ 110 м: сервер не получает точную геолокацию."""
    return round(value, digits)


def search_radius_m(transport: str, minutes: float, speeds_kmh: dict[str, float]) -> int:
    """Радиус = скорость × время. Он лишь сужает поиск; время считает матрица 2ГИС."""
    speed = speeds_kmh.get(transport, speeds_kmh.get("walking", 4.5))
    radius = speed * 1000.0 * minutes / 60.0
    return int(max(300.0, min(radius, 20_000.0)))
