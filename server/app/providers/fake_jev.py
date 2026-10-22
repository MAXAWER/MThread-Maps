"""Демо-замена Jev: классификатор по ключевым словам, отвечает в формате System One.

Нужен, чтобы конвейер работал без ключа TypeSafe. Качество — на уровне простых правил;
фильтры в интерфейсе помечаются источником «demo».
"""
from __future__ import annotations

import re

from ..catalog import Catalog

SYNONYMS = [
    (r"поесть|покушать|пообедать|поужинать|перекус|обед\b|ужин\b|еда\b|тамақ", "cafe"),
    (r"кофе|кофейн|капучино|латте|coffee", "coffee"),
    (r"прогул|погулять|пройтись|прогуляться|серуен", "park"),
    (r"площадк", "playground"),
    (r"музей с интерактив|интерактив", "interactive_museum"),
    (r"ночн\w* активност|необычн\w* ночн|ночная жизнь|потусить", "nightclub"),
    (r"каток|коньк", "skating_rink"),
    (r"смотров|панорам|вид на город", "viewpoint"),
    (r"халал|халяль", "cafe"),
    (r"поработать|ноутбук", "coffee"),
    (r"турист|достопримечат|что посмотреть|sightseeing", "architecture"),
]
PURCHASE = [
    (r"зонт", "umbrella"), (r"дождевик", "raincoat"), (r"повербанк|зарядк\w* для телефона|power ?bank", "powerbank"),
    (r"аптек|лекарств", "pharmacy"), (r"вод[ыау]\b|перекус|попить", "water_snack"), (r"цвет(ы|ов|очк)", "flowers"),
    (r"подар", "gift"), (r"сим-?карт|пополнит", "sim_topup"), (r"налич|банкомат", "cash_atm"),
]
TRANSPORT = [
    (r"пешком|жаяу|on foot|walk", "walking"), (r"на машине|на авто|за рул[её]м|\bеду\b|by car", "driving"),
    (r"такси|taxi", "taxi"), (r"велосипед|bike", "bicycle"), (r"самокат|scooter", "scooter"),
    (r"автобус|общественн|bus\b", "public_transport"),
]
NOUL = {
    "indoor": r"крыт|в помещении|дожд|холодн|мороз|ветер|жаңбыр",
    "wifi": r"wi-?fi|вай-?фай|интернет",
    "sockets": r"розетк|зарядить|ноутбук",
    "card_payment": r"оплат\w* карт|по карте|картой",
    "parking": r"парков",
    "terrace": r"веранд|террас",
    "accessible": r"коляс|пандус|инвалид",
    "pets": r"с собак|с питомц|с кошк|с собачк",
    "halal": r"халал|халяль|halal",
    "vegetarian": r"вегет|веган|vegan",
    "toilet": r"туалет",
    "exclude_chains": r"не сетев|несетев|локальн|авторск",
}


def _choice(choice: str, conf: float) -> dict:
    return {"type": "choice", "choice": choice, "confidence": conf, "probabilities": {choice: conf}}


class FakeJev:
    is_demo = True

    def __init__(self, catalog: Catalog):
        self._catalog = catalog

    async def ask(self, state: dict, questions: dict) -> dict:
        q = str(state.get("query") or "").lower()
        a: dict = {}
        cats: list[str] = []
        for pattern, cid in SYNONYMS:
            if re.search(pattern, q) and cid not in cats:
                cats.append(cid)
        rubric = self._catalog.match_rubrics([q])
        if rubric and rubric not in cats:
            cats.append(rubric)
        purchase = next((pid for pattern, pid in PURCHASE if re.search(pattern, q)), None)
        if purchase and cats and cats[0] in ("supermarket", "mall", "pharmacy", "flowers", "atm", "mobile_store"):
            cats = cats[1:]
        named = bool(re.search(r"[A-ZА-ЯЁ][a-zа-яё]+\s+[A-ZА-ЯЁ][a-zа-яё]+", state.get("query") or "")) and len(q.split()) <= 3
        if re.search(r"как добраться|маршрут до|доехать до|дойти до|navigate", q) or (named and not cats):
            intent = "navigate_to"
        elif re.search(r"во сколько|часы работы|телефон|работает ли", q):
            intent = "place_info"
        elif purchase and cats:
            intent = "plan_route"
        else:
            intent = "find_place"
        if len(q.split()) <= 2 and not cats and not purchase:
            intent = "navigate_to"
        a["intent"] = _choice(intent, 0.82)
        a["category_primary"] = _choice(cats[0], 0.86) if cats else _choice("none", 0.4)
        a["category_secondary"] = _choice(cats[1], 0.7) if len(cats) > 1 and intent == "plan_route" else _choice("none", 0.8)
        a["purchase_item"] = _choice(purchase, 0.9) if purchase else _choice("none", 0.9)
        transport = next((t for p, t in TRANSPORT if re.search(p, q)), "unspecified")
        a["transport"] = _choice(transport, 0.85 if transport != "unspecified" else 0.6)
        near = re.search(r"рядом|недалеко|поблизости|жақын|nearby|close", q)
        a["max_travel"] = _choice("10", 0.8) if near else _choice("unspecified", 0.6)
        a["time_budget"] = _choice("unspecified", 0.6)
        if re.search(r"\bв \d{1,2} ноч|ночью|среди ночи|круглосуточн", q):
            opened = "24h"
        elif re.search(r"ночн|допоздна|поздно", q):
            opened = "late"
        elif re.search(r"сейчас|открыт", q):
            opened = "now"
        else:
            opened = "at_arrival"
        a["open_requirement"] = _choice(opened, 0.75)
        if re.search(r"реб[её]н|детьми|с сыном|с дочк|малыш", q):
            company = "kids"
        elif re.search(r"вдво[её]м|с девушк|с парн|свидан", q):
            company = "pair"
        elif re.search(r"компани|друзь", q):
            company = "group_small"
        else:
            company = "unspecified"
        a["company"] = _choice(company, 0.8 if company != "unspecified" else 0.6)
        a["kids_age"] = _choice("none", 0.6)
        if re.search(r"бесплатн", q):
            price = "free"
        elif re.search(r"недорог|дешев|дёшев|бюджетн", q):
            price = "budget"
        elif re.search(r"дорог|премиум|роскош", q):
            price = "premium"
        else:
            price = "unspecified"
        a["price"] = _choice(price, 0.8 if price != "unspecified" else 0.6)
        if re.search(r"тих|спокойн", q):
            amb = "quiet"
        elif re.search(r"весел|шумн|движ", q):
            amb = "lively"
        elif re.search(r"романт|свидан", q):
            amb = "romantic"
        elif re.search(r"поработать|ноутбук", q):
            amb = "work"
        else:
            amb = "any"
        a["ambience"] = _choice(amb, 0.78 if amb != "any" else 0.6)
        a["min_rating"] = _choice("4.5", 0.8) if re.search(r"лучш|топ|высок\w* рейтинг", q) else _choice("any", 0.7)
        if re.search(r"нов(ое|ые|ых)|недавно открыл", q):
            novelty = "new_places"
        elif re.search(r"необычн|где не был", q):
            novelty = "not_visited"
        else:
            novelty = "any"
        a["novelty"] = _choice(novelty, 0.75 if novelty != "any" else 0.6)
        for key, pattern in NOUL.items():
            a[key] = {"type": "noul", "noul": 0.88 if re.search(pattern, q) else 0.08}
        return a
