"""Ранжирование и проверка признаков мест (разделы 4–5 ТЗ)."""
from __future__ import annotations

import math

from .catalog import Catalog
from .dgis import Place

M_PRIOR = 4.2
C_PRIOR = 10

# Признаки, подтверждаемые атрибутами 2ГИС: ищем по тегу и названию атрибута.
ATTR_KEYWORDS: dict[str, tuple[str, ...]] = {
    "wifi": ("wi-fi", "wifi", "вай-фай", "wi fi"),
    "card_payment": ("оплата карт", "банковск", "visa", "mastercard", "card"),
    "terrace": ("веранд", "террас", "terrace"),
    "parking": ("парковк", "parking"),
    "accessible": ("пандус", "доступн", "инвалид", "колясоч", "wheelchair"),
    "pets": ("животн", "питомц", "pets", "dog"),
    "halal": ("халал", "халяль", "halal"),
    "vegetarian": ("вегетариан", "веган", "vegan", "vegetarian"),
    "toilet": ("туалет", "toilet", "wc"),
    "sockets": ("розетк", "socket"),
    "kids_room": ("детская комната", "игровая комната", "детское меню", "kids room"),
}

AMBIENCE_FROM_ATTR = {"quiet": ("тихо", "спокойн"), "lively": ("живая музыка", "танцпол"), "work": ("wi-fi", "wifi", "коворкинг")}


def bayes_rating(rating: float | None, reviews: int | None, m: float = M_PRIOR, c: int = C_PRIOR) -> float:
    """r̄ = (n·r + C·m) / (n + C): «5,0 при двух отзывах» не обходит 4,7 при тысяче."""
    if rating is None:
        return m
    n = max(0, reviews or 0)
    return (n * rating + c * m) / (n + c)


def quality(rating: float | None, reviews: int | None) -> float:
    return max(0.0, min(1.0, (bayes_rating(rating, reviews) - 1.0) / 4.0))


def attr_status(place: Place, key: str, catalog: Catalog) -> str:
    """yes | no | unknown. Отсутствие атрибута в 2ГИС — это «не подтверждено», а не «нет»."""
    if key == "indoor":
        cat = catalog.get(place.category)
        if cat is None:
            return "unknown"
        return "yes" if cat.indoor else "no"
    if key == "exclude_chains":
        if place.branch_count is None:
            return "unknown"
        return "yes" if place.branch_count <= 3 else "no"
    words = ATTR_KEYWORDS.get(key)
    if not words:
        return "unknown"
    if any(w in attr for attr in place.attributes for w in words):
        return "yes"
    return "unknown"


def soft_match_fraction(place: Place, soft: list[str], catalog: Catalog) -> float:
    if not soft:
        return 0.0
    hits = sum(1 for k in soft if attr_status(place, k, catalog) == "yes")
    return hits / len(soft)


def context_bonus(place: Place, catalog: Catalog, *, ambience: str | None, price: str | None,
                  price_max: int | None, company: str | None, kids_age: str | None) -> float:
    """Мягкие бонусы по атмосфере, цене и компании: 0…1, добавляются к R."""
    cat = catalog.get(place.category)
    bonus, parts = 0.0, 0
    if ambience:
        parts += 1
        if cat and ambience in cat.ambience:
            bonus += 1.0
        elif any(w in a for a in place.attributes for w in AMBIENCE_FROM_ATTR.get(ambience, ())):
            bonus += 0.6
    if price:
        parts += 1
        if price == "free":
            bonus += 1.0 if (cat and cat.free) else 0.0
        elif price_max and place.avg_check:
            bonus += 1.0 if place.avg_check <= price_max else 0.0
        elif price in ("budget", "mid") and cat and cat.id in ("canteen", "fastfood", "bakery", "chaikhana", "cafe"):
            bonus += 0.7 if price == "budget" else 0.5
        elif price == "premium" and cat and cat.id in ("restaurant", "lounge", "theatre"):
            bonus += 0.7
    if company:
        parts += 1
        if company == "kids":
            if cat and cat.kids:
                bonus += 1.0
            if attr_status(place, "kids_room", catalog) == "yes":
                bonus += 0.3
        elif company in ("group_small", "group_large"):
            bonus += 1.0 if (cat and cat.for_groups) else 0.2
        elif company == "pair":
            bonus += 0.8 if (cat and "romantic" in cat.ambience) else 0.4
        else:
            bonus += 0.5
    return min(1.0, bonus / parts) if parts else 0.0


def relevance(place: Place, category_p: float, soft: list[str], ctx_bonus: float, catalog: Catalog,
              has_ctx: bool) -> float:
    """R: вероятность категории от Jev и доля совпавших мягких фильтров (плюс контекстные бонусы)."""
    parts = [(category_p, 0.6)]
    if soft:
        parts.append((soft_match_fraction(place, soft, catalog), 0.25))
    if has_ctx:
        parts.append((ctx_bonus, 0.15))
    total_w = sum(w for _, w in parts)
    return sum(v * w for v, w in parts) / total_w


def search_score(r: float, t_min: float, tau: float, q: float, p: float, o: float) -> float:
    """s = 0.35·R + 0.25·e^(−t/τ) + 0.20·Q + 0.10·P + 0.10·O (раздел 5)."""
    tau = max(1.0, tau)
    return 0.35 * r + 0.25 * math.exp(-t_min / tau) + 0.20 * q + 0.10 * p + 0.10 * o


def portrait_fit(category: str | None, affinity: dict[str, float]) -> float:
    if not category:
        return 0.5
    v = affinity.get(category)
    return max(0.0, min(1.0, float(v))) if v is not None else 0.5
