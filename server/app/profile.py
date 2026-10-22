"""Операции над портретом пользователя (раздел 8). Сервер ничего не хранит: только проверяет ответ модели."""
from __future__ import annotations

import logging
import re

from .catalog import Catalog
from .llm import profile_keys
from .models import ProfileOp, ProfileUpdateRequest
from .services import Services

log = logging.getLogger(__name__)

# Запрещённые выводы: здоровье, религия, национальность, политика, финансовое положение.
_FORBIDDEN = re.compile(
    r"здоров|болезн|болеет|диабет|аллерг|беремен|инвалидн|лекарств|диагноз|"
    r"религ|верующ|мусульм|христиан|православ|иудаи|буддис|молит|намаз|мечет|церк|"
    r"национальн|этнич|"
    r"политик|партия|партии|выбор|оппозиц|"
    r"зарплат|доход|бедн|богат|кредит|долг|ипотек|финансов",
    re.IGNORECASE,
)
_DIET = re.compile(r"халал|халяль|вегетариан|веган|halal|vegan|vegetarian|кошер", re.IGNORECASE)


def _trim(text, limit: int) -> str:
    text = " ".join(str(text or "").split())
    return text if len(text) <= limit else text[: limit - 1].rstrip() + "…"


def sanitize_ops(ops: list[dict], req: ProfileUpdateRequest, catalog: Catalog) -> list[ProfileOp]:
    """Только разрешённые ключи; запрещённые темы отбрасываются; диета — только как прямое заявление."""
    allowed = set(profile_keys(catalog))
    query = req.event.query or ""
    out: list[ProfileOp] = []
    for raw in ops[:20]:
        key = raw.get("key")
        op = raw.get("op")
        if key not in allowed or op not in ("add", "reinforce", "weaken", "remove"):
            continue
        explicit = bool(raw.get("explicit"))
        value = _trim(raw.get("value"), 120 if key == "explicit" else 60)
        evidence = _trim(raw.get("evidence"), 80)
        if not value:
            continue
        if _FORBIDDEN.search(f"{value} {evidence}"):
            continue
        if _DIET.search(value) and not (explicit and _DIET.search(query)):
            continue
        if key == "explicit" and not explicit:
            continue
        if key.startswith("category.") and value not in catalog.categories:
            value = key.split(".", 1)[1]
        out.append(ProfileOp(op=op, key=key, value=value, explicit=explicit, evidence=evidence))
    return out


async def update_profile(req: ProfileUpdateRequest, svc: Services) -> dict:
    if svc.llm is None:
        return {"ops": [], "note": "model unavailable"}
    payload = {
        "summary": req.summary[:1500],
        "event": req.event.model_dump(mode="json"),
    }
    ops = await svc.llm.profile_ops(payload)
    if ops is None:
        return {"ops": [], "note": "model unavailable"}
    return {"ops": [o.model_dump() for o in sanitize_ops(ops, req, svc.catalog)]}
