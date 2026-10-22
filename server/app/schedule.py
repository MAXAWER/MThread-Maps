"""Режим работы из 2ГИС → три состояния: «открыто», «закрыто», «нет данных» (раздел 5)."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta

DAY_KEYS = ("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
WEEK = 7 * 1440


@dataclass(frozen=True)
class OpenInfo:
    state: str  # open | closed | unknown
    open_until: str | None = None
    closes_in_min: int | None = None
    opens_at: str | None = None
    is_24x7: bool = False


UNKNOWN = OpenInfo("unknown")


def _hm(value: str | None) -> int | None:
    if not value or ":" not in value:
        return None
    try:
        h, m = value.split(":", 1)
        return int(h) * 60 + int(m[:2])
    except ValueError:
        return None


def _fmt(minutes: int) -> str:
    minutes %= 1440
    return f"{minutes // 60:02d}:{minutes % 60:02d}"


def _intervals(schedule: dict) -> list[tuple[int, int]] | None:
    """Интервалы работы в минутах от понедельника 00:00. None — данных о днях нет."""
    if not any(k in schedule for k in DAY_KEYS):
        return None
    out: list[tuple[int, int]] = []
    for i, key in enumerate(DAY_KEYS):
        day = schedule.get(key) or {}
        for wh in day.get("working_hours") or []:
            start, end = _hm(wh.get("from")), _hm(wh.get("to"))
            if start is None or end is None:
                continue
            if end <= start:  # через полночь, либо 00:00–00:00 = сутки
                end += 1440
            out.append((i * 1440 + start, i * 1440 + end))
    return out


def _merged(intervals: list[tuple[int, int]]) -> list[tuple[int, int]]:
    shifted = intervals + [(s - WEEK, e - WEEK) for s, e in intervals] + [(s + WEEK, e + WEEK) for s, e in intervals]
    shifted.sort()
    merged: list[list[int]] = []
    for s, e in shifted:
        if merged and s <= merged[-1][1]:
            merged[-1][1] = max(merged[-1][1], e)
        else:
            merged.append([s, e])
    return [(s, e) for s, e in merged]


def open_info(schedule: dict | None, when: datetime) -> OpenInfo:
    if not isinstance(schedule, dict) or not schedule:
        return UNKNOWN
    if schedule.get("is_24x7"):
        return OpenInfo("open", is_24x7=True)
    intervals = _intervals(schedule)
    if intervals is None:
        return UNKNOWN
    if not intervals:
        return OpenInfo("closed")
    now_m = when.weekday() * 1440 + when.hour * 60 + when.minute
    merged = _merged(intervals)
    for start, end in merged:
        if start <= now_m < end:
            if end - start >= WEEK:
                return OpenInfo("open", is_24x7=True)
            return OpenInfo("open", open_until=_fmt(end), closes_in_min=end - now_m)
    upcoming = [s for s, _ in merged if s > now_m]
    if not upcoming:
        return OpenInfo("closed")
    nxt = min(upcoming)
    day_shift = (nxt // 1440) - (now_m // 1440)
    label = _fmt(nxt)
    if day_shift == 1:
        label = f"завтра {label}"
    elif day_shift > 1:
        label = f"{DAY_LABELS_RU[(nxt // 1440) % 7]} {label}"
    return OpenInfo("closed", opens_at=label)


DAY_LABELS_RU = ("пн", "вт", "ср", "чт", "пт", "сб", "вс")


def open_at(schedule: dict | None, now: datetime, offset_min: float) -> OpenInfo:
    """Состояние к моменту прибытия: сейчас + время в пути."""
    return open_info(schedule, now + timedelta(minutes=max(0.0, offset_min)))


def is_open_late(info: OpenInfo, when: datetime) -> bool:
    """«Допоздна»: открыто как минимум до 23:00 этого вечера (или круглосуточно)."""
    if info.state != "open":
        return False
    if info.is_24x7:
        return True
    if info.closes_in_min is None:
        return False
    minutes_to_23 = (23 * 60) - (when.hour * 60 + when.minute)
    return info.closes_in_min >= max(0, minutes_to_23)


def work_time_param(requirement: str | None, now: datetime, offset_min: int = 0) -> str | None:
    """Значение параметра work_time для Places API (формат сверить в первый день)."""
    if requirement == "now":
        return "now"
    if requirement in ("at_arrival", "late"):
        t = now + timedelta(minutes=offset_min if requirement == "at_arrival" else 0)
        if requirement == "late":
            t = now.replace(hour=23, minute=0)
        return f"today,{t.hour:02d}:{t.minute:02d}"
    return None
