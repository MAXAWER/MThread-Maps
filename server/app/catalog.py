"""Справочники: категории (categories.yaml), покупки по пути, праздники."""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import date
from functools import lru_cache
from pathlib import Path

import yaml

DATA_DIR = Path(__file__).parent / "data"

GROUP_NAMES_RU = {
    "food": "Еда",
    "culture": "Культура",
    "walks": "Прогулки",
    "kids": "Дети",
    "shopping": "Шопинг",
    "fun": "Развлечения",
    "sport": "Спорт",
    "night": "Ночь",
    "services": "Сервисы",
}

# Категории «прогулочных» мест для правил контекста колоды (парки и т. п.).
WALK_OUTDOOR = {"park", "square", "embankment", "botanical_garden", "pedestrian_street", "viewpoint"}


@dataclass(frozen=True)
class Category:
    id: str
    name_ru: str
    name_en: str
    q: str
    rubric_ids: tuple[str, ...]
    indoor: bool
    kids: bool
    night: bool
    visit_min: int
    group: str
    match: tuple[re.Pattern, ...]
    for_groups: bool
    ambience: tuple[str, ...]
    free: bool

    @property
    def is_food(self) -> bool:
        return self.group == "food"


@dataclass(frozen=True)
class PurchaseItem:
    id: str
    name_ru: str
    queries: tuple[str, ...]
    categories: tuple[str, ...]
    solves_rain: bool


@dataclass
class Catalog:
    categories: dict[str, Category]
    purchase_items: dict[str, PurchaseItem]
    holidays_fixed: set[str] = field(default_factory=set)
    holidays_dated: set[str] = field(default_factory=set)

    def get(self, category_id: str | None) -> Category | None:
        if not category_id:
            return None
        return self.categories.get(category_id)

    def match_rubrics(self, rubric_names: list[str], place_name: str = "") -> str | None:
        """Сопоставляет рубрики 2ГИС с id категории. Первой проверяется основная рубрика."""
        texts = [r.lower() for r in rubric_names if r]
        if place_name:
            texts.append(place_name.lower())
        for text in texts:
            for cat in self.categories.values():
                if any(p.search(text) for p in cat.match):
                    return cat.id
        return None

    def is_holiday(self, d: date) -> bool:
        return d.strftime("%m-%d") in self.holidays_fixed or d.isoformat() in self.holidays_dated


@lru_cache(maxsize=1)
def load_catalog() -> Catalog:
    raw_cats = yaml.safe_load((DATA_DIR / "categories.yaml").read_text(encoding="utf-8"))
    categories: dict[str, Category] = {}
    for row in raw_cats:
        categories[row["id"]] = Category(
            id=row["id"],
            name_ru=row["name_ru"],
            name_en=row["name_en"],
            q=row["q"],
            rubric_ids=tuple(str(x) for x in row.get("rubric_ids") or ()),
            indoor=bool(row["indoor"]),
            kids=bool(row["kids"]),
            night=bool(row["night"]),
            visit_min=int(row["visit_min"]),
            group=row["group"],
            match=tuple(re.compile(p, re.IGNORECASE) for p in row.get("match") or ()),
            for_groups=bool(row.get("for_groups", False)),
            ambience=tuple(row.get("ambience") or ()),
            free=bool(row.get("free", False)),
        )
    raw_items = yaml.safe_load((DATA_DIR / "purchase_items.yaml").read_text(encoding="utf-8"))
    items = {
        r["id"]: PurchaseItem(
            id=r["id"],
            name_ru=r["name_ru"],
            queries=tuple(r["queries"]),
            categories=tuple(r.get("categories") or ()),
            solves_rain=bool(r.get("solves_rain", False)),
        )
        for r in raw_items
    }
    hol = yaml.safe_load((DATA_DIR / "holidays.yaml").read_text(encoding="utf-8")) or {}
    return Catalog(
        categories=categories,
        purchase_items=items,
        holidays_fixed=set(hol.get("fixed") or ()),
        holidays_dated=set(hol.get("dated") or ()),
    )
