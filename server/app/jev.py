"""Jev (TypeSafe System One): определение фильтров по запросу (раздел 3 ТЗ).

Один вызов на запрос, только с сервера. На входе состояние без координат,
на выходе типизированные ответы с вероятностями; код собирает из них JevFilters.
"""
from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass

import httpx

from .catalog import Catalog
from .models import Chip, JevFilters, Scored
from .query_parse import ParsedNumbers, kids_age_bucket, max_travel_bucket

log = logging.getLogger(__name__)

CHOICE_MIN = 0.55
# Покупка по пути превращает поиск в план из двух точек — ошибка дорогая, поэтому порог выше.
PURCHASE_MIN = 0.75
NOUL_HARD = 0.80
NOUL_SOFT = 0.55

# Атрибуты, которые 2ГИС может подтвердить: при p ≥ 0,80 — жёсткий фильтр.
HARD_CAPABLE = {"indoor", "wifi", "card_payment", "parking", "terrace", "accessible", "pets", "halal", "vegetarian", "exclude_chains"}
# Признаки без атрибута в 2ГИС — только мягкий бонус и пометка «не подтверждено».
SOFT_ONLY = {"sockets", "toilet"}
NOUL_KEYS = ["indoor", "wifi", "sockets", "card_payment", "parking", "terrace", "accessible", "pets", "halal", "vegetarian", "toilet", "exclude_chains"]

CHOICE_DEFAULTS = {
    "intent": "other",
    "category_primary": "none",
    "category_secondary": "none",
    "purchase_item": "none",
    "transport": "unspecified",
    "max_travel": "unspecified",
    "time_budget": "unspecified",
    "open_requirement": "unspecified",
    "company": "unspecified",
    "kids_age": "none",
    "price": "unspecified",
    "ambience": "any",
    "min_rating": "any",
    "novelty": "any",
}

TRANSPORT_LABELS = {
    "walking": "Пешком",
    "bicycle": "Велосипед",
    "scooter": "Самокат",
    "driving": "Авто",
    "taxi": "Такси",
    "public_transport": "Общественный транспорт",
}
OPEN_LABELS = {"now": "Открыто сейчас", "at_arrival": "Открыто к приходу", "late": "Допоздна", "24h": "Круглосуточно"}
COMPANY_LABELS = {"solo": "Один", "pair": "Вдвоём", "kids": "С детьми", "group_small": "Небольшая компания", "group_large": "Большая компания"}
KIDS_LABELS = {"under_3": "до 3 лет", "3_7": "3–7 лет", "8_12": "8–12 лет", "teen": "подростки"}
PRICE_LABELS = {"free": "Бесплатно", "budget": "Недорого", "mid": "Средний чек", "premium": "Премиум"}
AMBIENCE_LABELS = {"quiet": "Тихо", "lively": "Оживлённо", "romantic": "Романтично", "family": "Для семьи", "work": "Для работы"}
NOVELTY_LABELS = {"new_places": "Новые места", "not_visited": "Где не был"}
NOUL_LABELS = {
    "indoor": "Крытое место",
    "wifi": "Wi-Fi",
    "sockets": "Розетки",
    "card_payment": "Оплата картой",
    "parking": "Парковка",
    "terrace": "Веранда",
    "accessible": "Доступная среда",
    "pets": "С питомцами",
    "halal": "Халяль",
    "vegetarian": "Вегетарианское",
    "toilet": "Туалет",
    "exclude_chains": "Без сетевых",
}


def build_questions(catalog: Catalog) -> dict:
    """Набор вопросов: один запрос к Jev на все вопросы сразу (вопросы оцениваются независимо)."""
    cats = {c.id: f"{c.name_en} ({c.name_ru})" for c in catalog.categories.values()}
    cats["none"] = "No particular type of place"
    lang_note = " The query may be in Russian, Kazakh or English; context fields only help to resolve ambiguity."
    q: dict = {
        "intent": {
            "type": "choice",
            "instructions": "What does the user want right now?" + lang_note,
            "criteria": {
                "find_place": "Get recommendations: where to go nearby now (one place is enough)",
                "plan_route": "Plan a route with several stops, or buy/do something on the way before reaching the destination",
                "navigate_to": "Go to one specific named place, organization or address",
                "place_info": "Learn details about one specific named place (opening hours, phone, address)",
                "other": "Anything else",
            },
        },
        "category_primary": {
            "type": "choice",
            "instructions": "Main type of place the user wants to end up at." + lang_note,
            "criteria": cats,
        },
        "category_secondary": {
            "type": "choice",
            "instructions": "A second, different type of place the user also needs (on the way or in addition). Choose none if only one type is needed." + lang_note,
            "criteria": cats,
        },
        "purchase_item": {
            "type": "choice",
            "instructions": ("Something the user explicitly says they need to buy or get on the way, in addition to the "
                             "destination (for example: no umbrella, need water, phone is dying). The destination itself "
                             "(coffee, food, a cafe) is NOT a purchase. If the user did not mention buying anything, "
                             "choose none.") + lang_note,
            "criteria": {
                "umbrella": "An umbrella (no umbrella, rain is coming)",
                "raincoat": "A raincoat",
                "powerbank": "A power bank or phone charger",
                "pharmacy": "Medicine from a pharmacy",
                "water_snack": "Water, drinks or a snack from a shop",
                "flowers": "Flowers",
                "gift": "A gift or souvenir",
                "sim_topup": "A SIM card or mobile top-up",
                "cash_atm": "Cash from an ATM",
                "none": "Nothing to buy",
            },
        },
        "transport": {
            "type": "choice",
            "instructions": "How the user will get there." + lang_note,
            "criteria": {
                "walking": "On foot",
                "bicycle": "By bicycle",
                "scooter": "By kick scooter",
                "driving": "By own car",
                "taxi": "By taxi",
                "public_transport": "By bus or other public transport",
                "unspecified": "Not stated",
            },
        },
        "max_travel": {
            "type": "choice",
            "instructions": "Maximum one-way travel time the user accepts, in minutes." + lang_note,
            "criteria": {
                "5": "About 5 minutes, right here",
                "10": "About 10 minutes, nearby",
                "20": "About 20 minutes",
                "40": "Up to 40 minutes or further",
                "unspecified": "Not stated",
            },
        },
        "time_budget": {
            "type": "choice",
            "instructions": "Total time the user has for the outing, in minutes." + lang_note,
            "criteria": {
                "15": "About 15 minutes",
                "30": "About half an hour",
                "60": "About an hour",
                "120": "About two hours",
                "more": "More than two hours, half a day",
                "unspecified": "Not stated",
            },
        },
        "open_requirement": {
            "type": "choice",
            "instructions": "Opening-hours requirement." + lang_note,
            "criteria": {
                "now": "Must be open right now",
                "at_arrival": "Must be open when the user arrives, or soon (e.g. in 30 minutes)",
                "late": "Must stay open late in the evening",
                "24h": "Must be open around the clock or in the middle of the night",
                "unspecified": "Not stated",
            },
        },
        "company": {
            "type": "choice",
            "instructions": "Who is going with the user." + lang_note,
            "criteria": {
                "solo": "Alone",
                "pair": "Two people, a date or with a friend",
                "kids": "With children",
                "group_small": "Small group of 3-5 people",
                "group_large": "Large group of 6 or more",
                "unspecified": "Not stated",
            },
        },
        "kids_age": {
            "type": "choice",
            "instructions": "Age of the children, if any." + lang_note,
            "criteria": {
                "under_3": "Toddler under 3",
                "3_7": "Child 3 to 7 years old",
                "8_12": "Child 8 to 12 years old",
                "teen": "Teenager",
                "none": "No children or age not stated",
            },
        },
        "price": {
            "type": "choice",
            "instructions": "Price level the user wants." + lang_note,
            "criteria": {
                "free": "Free of charge",
                "budget": "Cheap, inexpensive",
                "mid": "Average prices",
                "premium": "Expensive, premium",
                "unspecified": "Not stated",
            },
        },
        "ambience": {
            "type": "choice",
            "instructions": "Desired atmosphere." + lang_note,
            "criteria": {
                "quiet": "Quiet, calm",
                "lively": "Lively, busy, fun",
                "romantic": "Romantic",
                "family": "Family friendly",
                "work": "Good for working on a laptop",
                "any": "Not stated",
            },
        },
        "min_rating": {
            "type": "choice",
            "instructions": "Minimum rating the user wants." + lang_note,
            "criteria": {"any": "Not stated", "4.0": "Good places, rating 4 or higher", "4.5": "Only the best places, rating 4.5 or higher"},
        },
        "novelty": {
            "type": "choice",
            "instructions": "Does the user want something new?" + lang_note,
            "criteria": {
                "new_places": "Recently opened places",
                "not_visited": "Places the user has not been to yet, something unusual",
                "any": "Not stated",
            },
        },
    }
    noul_text = {
        "indoor": "The user needs an indoor place (rain, cold, wind, or asks for it).",
        "wifi": "The user needs Wi-Fi.",
        "sockets": "The user needs a power socket to charge a phone or laptop.",
        "card_payment": "The user needs to pay by card.",
        "parking": "The user needs parking for a car.",
        "terrace": "The user wants a terrace or summer veranda.",
        "accessible": "The user needs wheelchair or step-free access.",
        "pets": "The user comes with a pet, for example a dog.",
        "halal": "The user needs halal food.",
        "vegetarian": "The user needs vegetarian or vegan food.",
        "toilet": "The user needs a toilet.",
        "exclude_chains": "The user wants a local independent place, not a chain.",
    }
    for key in NOUL_KEYS:
        q[key] = {"type": "noul", "instructions": noul_text[key] + lang_note}
    return q


class JevUnavailable(Exception):
    pass


class JevSchemaError(Exception):
    pass


class JevClient:
    def __init__(self, http: httpx.AsyncClient, base_url: str, api_key: str, model: str, timeout_s: float = 1.5):
        self._http = http
        self._url = f"{base_url.rstrip('/')}/v1/systemone"
        self._key = api_key
        self._model = model
        self._timeout = timeout_s

    async def ask(self, state: dict, questions: dict) -> dict:
        body = {"model": self._model, "state": state, "questions": questions}
        headers = {"Authorization": f"Bearer {self._key}"}
        for attempt in range(2):
            try:
                r = await self._http.post(self._url, json=body, headers=headers, timeout=self._timeout)
            except httpx.TimeoutException as exc:
                raise JevUnavailable("timeout") from exc
            except httpx.HTTPError as exc:
                raise JevUnavailable(type(exc).__name__) from exc
            if r.status_code == 422:
                # Только путь и сообщение: поле input может содержать текст запроса.
                try:
                    detail = [{"loc": d.get("loc"), "msg": d.get("msg")} for d in r.json().get("detail") or []]
                except ValueError:
                    detail = []
                log.error("jev 422: question schema rejected: %s", detail[:20])
                raise JevSchemaError("422")
            if r.status_code == 429 or r.status_code >= 500:
                retry_after = _retry_after(r)
                if attempt == 0 and retry_after <= 1.0:
                    await asyncio.sleep(retry_after)
                    continue
                raise JevUnavailable(f"http {r.status_code}")
            if r.status_code >= 400:
                raise JevUnavailable(f"http {r.status_code}")
            data = r.json()
            return data.get("answers") or {}
        raise JevUnavailable("retry exhausted")


def _retry_after(r: httpx.Response) -> float:
    try:
        return max(0.0, float(r.headers.get("Retry-After", "0.3")))
    except ValueError:
        return 0.3


def jev_state(query: str, local_time, weather: dict | None, walk_active: bool, transport_detected: str | None,
              profile_hint: str, is_holiday: bool) -> dict:
    """Состояние для Jev: без координат."""
    w = weather or {}
    return {
        "query": query,
        "local_time": local_time.strftime("%Y-%m-%dT%H:%M"),
        "weekday": local_time.strftime("%a").lower(),
        "is_holiday": is_holiday,
        "walk_active": walk_active,
        "transport_detected": transport_detected or "unknown",
        "weather": {
            "temp_c": w.get("temp_c"),
            "precip_starts_in_min": w.get("precip_starts_in_min"),
            "wind_ms": w.get("wind_ms"),
        },
        "profile_hint": profile_hint[:300],
    }


@dataclass
class _Choice:
    value: str | None
    p: float


def _choice(answers: dict, name: str) -> _Choice:
    ans = answers.get(name) or {}
    value = ans.get("choice")
    conf = float(ans.get("confidence") or 0.0)
    if value is None or conf < CHOICE_MIN or value == CHOICE_DEFAULTS.get(name):
        return _Choice(None, conf)
    return _Choice(str(value), conf)


def build_filters(answers: dict, nums: ParsedNumbers, source: str = "jev") -> JevFilters:
    """Собирает JevFilters из ответов Jev по порогам и подставляет точные числа из текста."""
    f = JevFilters(source=source)
    intent = _choice(answers, "intent")
    f.intent = intent.value or "find_place"
    if f.intent == "other":
        f.intent = "find_place"
    for name in ("category_primary", "category_secondary", "purchase_item"):
        c = _choice(answers, name)
        if c.value and (name != "purchase_item" or c.p >= PURCHASE_MIN):
            setattr(f, name, Scored(id=c.value, p=round(c.p, 3)))
    if f.category_secondary and f.category_primary and f.category_secondary.id == f.category_primary.id:
        f.category_secondary = None
    f.transport = _choice(answers, "transport").value
    mt = _choice(answers, "max_travel").value
    f.max_travel_min = int(mt) if mt and mt.isdigit() else None
    tb = _choice(answers, "time_budget").value
    f.time_budget_min = 180 if tb == "more" else (int(tb) if tb and tb.isdigit() else None)
    f.open = _choice(answers, "open_requirement").value
    f.company = _choice(answers, "company").value
    f.kids_age = _choice(answers, "kids_age").value
    f.price = _choice(answers, "price").value
    f.ambience = _choice(answers, "ambience").value
    mr = _choice(answers, "min_rating").value
    f.min_rating = float(mr) if mr else None
    f.novelty = _choice(answers, "novelty").value
    hard, soft = [], []
    for key in NOUL_KEYS:
        p = float((answers.get(key) or {}).get("noul") or 0.0)
        if p >= NOUL_HARD and key in HARD_CAPABLE:
            hard.append(key)
        elif p >= NOUL_SOFT:
            soft.append(key)
    f.hard, f.soft = hard, soft
    apply_numbers(f, nums)
    cat_conf = float((answers.get("category_primary") or {}).get("confidence") or 0.0)
    f.raw_text_search = f.intent in ("navigate_to", "place_info") or cat_conf < CHOICE_MIN
    return f


def apply_numbers(f: JevFilters, nums: ParsedNumbers) -> None:
    """Точное значение из текста заменяет корзину Jev того же смысла."""
    if nums.max_travel_min is not None:
        f.max_travel_min = nums.max_travel_min
    if nums.time_budget_min is not None:
        f.time_budget_min = nums.time_budget_min
    if nums.event_offset_min is not None:
        f.event_offset_min = nums.event_offset_min
    if nums.price_max_tenge is not None:
        f.price_max_tenge = nums.price_max_tenge
        if f.price is None:
            f.price = "budget" if nums.price_max_tenge <= 3000 else ("mid" if nums.price_max_tenge <= 8000 else "premium")
    if nums.group_size is not None:
        f.group_size = nums.group_size
        if f.company in (None, "solo", "pair"):
            f.company = "pair" if nums.group_size == 2 else ("group_small" if nums.group_size <= 5 else "group_large")
    if nums.kids_age_years is not None:
        f.kids_age = kids_age_bucket(nums.kids_age_years)
        if f.company is None:
            f.company = "kids"
    if nums.needs_return:
        f.needs_return = True


def unavailable_filters(nums: ParsedNumbers) -> JevFilters:
    """Jev недоступен: поиск по исходному тексту без фильтров (раздел 3)."""
    f = JevFilters(source="none", raw_text_search=True)
    apply_numbers(f, nums)
    return f


def build_chips(f: JevFilters, catalog: Catalog) -> list[Chip]:
    chips: list[Chip] = []
    if f.source == "none":
        chips.append(Chip(key="jev_unavailable", label="Фильтры не определены", kind="warning", removable=False))
    for name in ("category_primary", "category_secondary"):
        sc = getattr(f, name)
        if sc:
            cat = catalog.get(sc.id)
            chips.append(Chip(key=name, label=cat.name_ru if cat else sc.id, kind="info"))
    if f.purchase_item:
        item = catalog.purchase_items.get(f.purchase_item.id)
        chips.append(Chip(key="purchase_item", label=f"Купить: {item.name_ru if item else f.purchase_item.id}"))
    if f.transport:
        chips.append(Chip(key="transport", label=TRANSPORT_LABELS.get(f.transport, f.transport)))
    if f.max_travel_min:
        chips.append(Chip(key="max_travel_min", label=f"≤ {f.max_travel_min} мин в пути", kind="hard"))
    if f.time_budget_min:
        chips.append(Chip(key="time_budget_min", label=f"На {f.time_budget_min} мин"))
    if f.event_offset_min:
        chips.append(Chip(key="event_offset_min", label=f"Через {f.event_offset_min} мин", removable=False))
    if f.open:
        chips.append(Chip(key="open", label=OPEN_LABELS.get(f.open, f.open), kind="hard"))
    if f.company:
        label = COMPANY_LABELS.get(f.company, f.company)
        if f.company == "kids" and f.kids_age:
            label = f"С детьми {KIDS_LABELS.get(f.kids_age, '')}".strip()
        chips.append(Chip(key="company", label=label))
    if f.group_size and f.company not in ("kids",):
        chips.append(Chip(key="group_size", label=f"{f.group_size} чел."))
    if f.price:
        label = PRICE_LABELS.get(f.price, f.price)
        if f.price_max_tenge:
            label = f"До {f.price_max_tenge:,} ₸".replace(",", " ")
        chips.append(Chip(key="price", label=label, kind="soft"))
    if f.ambience:
        chips.append(Chip(key="ambience", label=AMBIENCE_LABELS.get(f.ambience, f.ambience), kind="soft"))
    if f.min_rating:
        chips.append(Chip(key="min_rating", label=f"Рейтинг ≥ {f.min_rating:g}", kind="hard"))
    if f.novelty:
        chips.append(Chip(key="novelty", label=NOVELTY_LABELS.get(f.novelty, f.novelty), kind="soft"))
    for key in f.hard:
        chips.append(Chip(key=f"hard:{key}", label=NOUL_LABELS.get(key, key), kind="hard"))
    for key in f.soft:
        chips.append(Chip(key=f"soft:{key}", label=f"{NOUL_LABELS.get(key, key)} — желательно", kind="soft"))
    return chips


def remove_chip(f: JevFilters, key: str) -> JevFilters:
    """Снять фильтр по ключу чипа без повторного вызова Jev (используется клиентом через filters)."""
    data = f.model_copy(deep=True)
    if key.startswith("hard:"):
        data.hard = [k for k in data.hard if k != key[5:]]
    elif key.startswith("soft:"):
        data.soft = [k for k in data.soft if k != key[5:]]
    elif hasattr(data, key):
        default = JevFilters.model_fields[key].default
        setattr(data, key, default)
    return data


def max_travel_hint(minutes: int | None) -> int | None:
    return max_travel_bucket(minutes)
