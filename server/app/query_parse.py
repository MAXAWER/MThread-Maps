"""Числа из запроса (минуты, тенге, число людей, возраст детей) — регулярными выражениями.

Jev возвращает только корзины; точное значение из текста заменяет корзину того же смысла (раздел 3).
"""
from __future__ import annotations

import re
from dataclasses import dataclass

_NUM = r"(\d+(?:[.,]\d+)?)"
_UNIT = r"(мин\w*|minutes?|mins?|час\w*|ч\b|ч\.|hours?|hrs?|сағат\w*)"
_DURATION = re.compile(rf"{_NUM}\s*(?:(?:-|–|—|до)\s*{_NUM}\s*)?{_UNIT}", re.IGNORECASE)
_WORD_DURATIONS = [
    (re.compile(r"полтора\s+час", re.I), 90),
    (re.compile(r"пар[уа]\s+час", re.I), 120),
    (re.compile(r"полчаса", re.I), 30),
    (re.compile(r"(?:\bна|\bесть|у меня|погулять|прогуляться|свободен|свободна|\bза)\s+час(?:ик)?\b", re.I), 60),
    (re.compile(r"\bhalf an hour\b", re.I), 30),
    (re.compile(r"\ban hour\b", re.I), 60),
]
_EVENT_WORDS = re.compile(r"дожд|ливен|ливн|снег|осадк|закро|начн|сеанс|встреч|rain|snow|жаңбыр|қар", re.I)
_TRAVEL_AFTER = re.compile(
    r"^\s*(?:\w+\s+)?(пешком|ходьбы|ходу|езды|на машине|на авто|на такси|на велосипеде|на самокате|от меня|отсюда|walk|drive|by car|жаяу)",
    re.I,
)
_TRAVEL_BEFORE = re.compile(r"(?:\bв|\bза|\bдо|не дальше|не больше|максимум|в пределах|within)\s*$", re.I)
_BUDGET_BEFORE = re.compile(r"(?:\bна|есть|у меня|свободн\w*|прогулят\w*|погулят\w*|провести|потратить|have|for)\s*$", re.I)
_RETURN = re.compile(r"до вылета|до рейса|до самол[её]та|до поезда|вернуться|обратно|успеть назад|до отправления|before (?:my )?flight", re.I)
_TENGE = re.compile(r"(\d[\d\s]{0,8}\d|\d)\s*(?:тг|тенге|теңге|₸|kzt)\b", re.I)
_TENGE_K = re.compile(r"(\d+(?:[.,]\d+)?)\s*(?:к|тыс\.?|тысяч\w*)\s*(?:тг|тенге|теңге|₸)", re.I)
_GROUP = re.compile(r"(?:\bнас|компани\w*\s+из|\bна)\s+(\d{1,2})\s*(?:человек|чел\b|друз\w*|персон\w*)", re.I)
_GROUP_WORDS = [
    (re.compile(r"вдво[её]м", re.I), 2),
    (re.compile(r"втро[её]м", re.I), 3),
    (re.compile(r"вчетвером", re.I), 4),
    (re.compile(r"большой компани", re.I), 8),
]
_KIDS_AGE = re.compile(r"(\d{1,2})\s*(?:-?\s*)(?:лет|год\w*|жас\w*|years?|y\.?o\.?)", re.I)
_KIDS_WORDS = re.compile(r"реб[её]н|сын|доч|дет|малыш|бала|kid|child|son|daughter", re.I)
_KK_LETTERS = re.compile(r"[әғқңөұүһі]", re.I)
_CYR = re.compile(r"[а-яё]", re.I)


@dataclass
class ParsedNumbers:
    max_travel_min: int | None = None
    time_budget_min: int | None = None
    event_offset_min: int | None = None
    price_max_tenge: int | None = None
    group_size: int | None = None
    kids_age_years: int | None = None
    needs_return: bool = False


def _to_minutes(value: str, unit: str) -> int:
    num = float(value.replace(",", "."))
    unit = unit.lower()
    if unit.startswith(("ч", "час", "h", "сағат")):
        return int(round(num * 60))
    return int(round(num))


def parse_numbers(text: str) -> ParsedNumbers:
    out = ParsedNumbers()
    out.needs_return = bool(_RETURN.search(text))
    for m in _DURATION.finditer(text):
        lo = _to_minutes(m.group(1), m.group(3))
        hi = _to_minutes(m.group(2), m.group(3)) if m.group(2) else lo
        before = text[max(0, m.start() - 30) : m.start()]
        after = text[m.end() : m.end() + 30]
        if re.search(r"через\s*$", before, re.I) or (re.search(r"\bin\s*$", before, re.I) and _EVENT_WORDS.search(after)):
            out.event_offset_min = lo
        elif _TRAVEL_AFTER.search(after) or (_TRAVEL_BEFORE.search(before) and not m.group(2) and not _RETURN.search(after)):
            out.max_travel_min = hi
        else:
            out.time_budget_min = max(hi, out.time_budget_min or 0)
    if out.time_budget_min is None and out.event_offset_min is None:
        for pattern, minutes in _WORD_DURATIONS:
            if pattern.search(text):
                out.time_budget_min = minutes
                break
    m = _TENGE_K.search(text)
    if m:
        out.price_max_tenge = int(float(m.group(1).replace(",", ".")) * 1000)
    else:
        m = _TENGE.search(text)
        if m:
            out.price_max_tenge = int(re.sub(r"\s+", "", m.group(1)))
    m = _GROUP.search(text)
    if m:
        out.group_size = int(m.group(1))
    else:
        for pattern, size in _GROUP_WORDS:
            if pattern.search(text):
                out.group_size = size
                break
    for m in _KIDS_AGE.finditer(text):
        window = text[max(0, m.start() - 30) : m.end() + 10]
        if _KIDS_WORDS.search(window):
            out.kids_age_years = int(m.group(1))
            break
    return out


def kids_age_bucket(years: int | None) -> str | None:
    if years is None:
        return None
    if years < 3:
        return "under_3"
    if years <= 7:
        return "3_7"
    if years <= 12:
        return "8_12"
    return "teen"


def max_travel_bucket(minutes: int | None) -> int | None:
    if minutes is None:
        return None
    for b in (5, 10, 20, 40):
        if minutes <= b:
            return b
    return 40


def detect_lang(text: str) -> str:
    if _KK_LETTERS.search(text):
        return "kk"
    if _CYR.search(text):
        return "ru"
    return "en"
