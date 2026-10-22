"""Инварианты ответа (раздел 15): прогоняются тестами и скриптом сценариев."""
from __future__ import annotations

import re

OPEN_CLAIM = re.compile(r"\b(открыт\w*|работает сейчас|open now|ашық)\b", re.IGNORECASE)


def check_invariants(events: dict, allow_estimate: bool = False) -> list[str]:
    """events — словарь событий SSE поиска: {'candidates': {...}, 'answer': {...}}."""
    errors: list[str] = []
    candidates = {c["poi_id"] for c in (events.get("candidates") or {}).get("items", [])}
    answer = events.get("answer")
    if not answer:
        if "error" not in events:
            errors.append("no answer event")
        return errors
    eta_ok = {"matrix", "demo"} | ({"estimate"} if allow_estimate else set())
    for it in answer.get("items", []):
        if it["poi_id"] not in candidates:
            errors.append(f"poi_id {it['poi_id']} is not among candidates")
        if it["open_state"] == "unknown" and it.get("why") and OPEN_CLAIM.search(it["why"]):
            errors.append(f"'open' claimed for unknown hours: {it['poi_id']}")
        if it.get("eta_min") is not None and it.get("eta_source") not in eta_ok:
            errors.append(f"eta not from 2GIS matrix: {it['poi_id']}")
        if it.get("source") not in ("poi_data", "model_idea"):
            errors.append(f"no source label: {it['poi_id']}")
    plan = answer.get("plan")
    if plan:
        for leg in plan.get("legs", []):
            if leg["to"] not in candidates:
                errors.append(f"plan stop {leg['to']} is not among candidates")
    return errors
