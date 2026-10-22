"""Демо-замена Claude: шаблонные тексты. Нужна, чтобы приложение работало без ключа Claude Console."""
from __future__ import annotations

import re

from ..catalog import Catalog

ACTIONS = {
    "coffee": "Возьмите напиток и сядьте у окна",
    "cafe": "Закажите блюдо дня",
    "restaurant": "Спросите про фирменное блюдо",
    "bakery": "Возьмите свежую выпечку с собой",
    "chaikhana": "Закажите чайник чая на компанию",
    "park": "Пройдите круг по главной аллее",
    "embankment": "Пройдитесь вдоль воды до ближайшего моста",
    "viewpoint": "Поднимитесь наверх и посмотрите на город",
    "museum": "Начните с основной экспозиции",
    "interactive_museum": "Начните с интерактивного зала",
    "mall": "Загляните на фуд-корт или в магазины",
    "playground": "Дайте ребёнку побегать",
    "kids_center": "Купите билет на час игры",
    "cinema": "Посмотрите расписание ближайших сеансов",
}
ACTIONS_KK = "Маршрут құрып, жолға шығыңыз"


class TemplateLLM:
    """Шаблонный ответ: берёт кандидатов в порядке ранжирования кода."""

    name = "template"

    def __init__(self, catalog: Catalog):
        self._catalog = catalog

    async def answer(self, payload: dict) -> dict | None:
        lang = payload.get("language", "ru")
        items = []
        for c in payload.get("candidates", [])[:3]:
            reasons = []
            if lang == "kk":
                why = f"{c.get('name')} — сұранысыңызға сай жақын орын"
                action = ACTIONS_KK
            elif lang == "en":
                why = f"{c.get('category') or 'Place'} nearby that fits your request"
                action = "Build a route and go"
            else:
                if c.get("rating") and c.get("rating") >= 4.5 and (c.get("reviews") or 0) >= 30:
                    reasons.append("высокий рейтинг по отзывам")
                if c.get("confirmed"):
                    reasons.append("есть " + ", ".join(c["confirmed"]).lower())
                if c.get("indoor") and "осадки" in str(payload.get("context", {}).get("weather", "")):
                    reasons.append("крытое место — не промокнете")
                if not reasons:
                    reasons.append("подходит под запрос и недалеко")
                why = f"{c.get('category') or 'Место'}: " + "; ".join(reasons)
                if c.get("unconfirmed"):
                    why += f". {', '.join(c['unconfirmed'])} — не подтверждено"
                action = ACTIONS.get(self._category_id(c.get("category")), "Постройте маршрут и отправляйтесь")
            items.append({"candidate": c["candidate"], "why": why, "visit_min": c.get("typical_visit_min") or 30,
                          "action": action})
        return {"items": items, "idea": None}

    def _category_id(self, name: str | None) -> str | None:
        for cat in self._catalog.categories.values():
            if cat.name_ru == name:
                return cat.id
        return None

    async def profile_ops(self, payload: dict) -> list[dict] | None:
        ev = payload.get("event") or {}
        ops: list[dict] = []
        cat = ev.get("selected_category")
        if cat and ev.get("action") in ("selected", "route_started", "route_completed"):
            ops.append({"op": "reinforce", "key": f"category.{cat}", "value": cat, "explicit": False,
                        "evidence": "выбрал место этой категории"})
        transport = (ev.get("filters") or {}).get("transport")
        if transport:
            label = {"walking": "пешком", "bicycle": "велосипед", "scooter": "самокат", "driving": "авто",
                     "taxi": "такси", "public_transport": "общественный транспорт"}.get(transport, transport)
            ops.append({"op": "reinforce", "key": "transport_default", "value": label, "explicit": False,
                        "evidence": "указал транспорт в запросе"})
        m = re.search(r"\b(люблю|обожаю|мне нравится)\s+([^,.!?]{3,60})", ev.get("query") or "", re.IGNORECASE)
        if m:
            ops.append({"op": "add", "key": "explicit", "value": m.group(0)[:120], "explicit": True,
                        "evidence": "прямое заявление в запросе"})
        return ops

    async def deck_texts(self, payload: dict) -> dict[str, str] | None:
        return None  # колода возьмёт шаблоны

    async def notify_text(self, payload: dict) -> str | None:
        return None  # уведомление возьмёт шаблон

    async def plan_explain(self, payload: dict) -> str | None:
        return None  # объяснение плана возьмёт шаблон
