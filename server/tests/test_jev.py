import httpx
import pytest

from app.catalog import load_catalog
from app.jev import (JevClient, JevSchemaError, JevUnavailable, build_chips, build_filters, build_questions,
                     remove_chip, unavailable_filters)
from app.query_parse import parse_numbers


def ch(choice, conf):
    return {"type": "choice", "choice": choice, "confidence": conf, "probabilities": {choice: conf}}


def test_questions_shape():
    q = build_questions(load_catalog())
    assert q["category_primary"]["type"] == "choice"
    assert "none" in q["category_primary"]["criteria"]
    assert 1 <= len(q["category_primary"]["criteria"]) <= 255
    assert q["wifi"]["type"] == "noul"


def test_thresholds_and_numbers():
    answers = {
        "intent": ch("plan_route", 0.8),
        "category_primary": ch("coffee", 0.86),
        "purchase_item": ch("umbrella", 0.91),
        "transport": ch("walking", 0.5),            # ниже 0,55 → не применяется
        "max_travel": ch("10", 0.7),
        "open_requirement": ch("at_arrival", 0.6),
        "indoor": {"type": "noul", "noul": 0.9},     # ≥ 0,80 → жёсткий
        "wifi": {"type": "noul", "noul": 0.6},       # 0,55–0,80 → мягкий
        "sockets": {"type": "noul", "noul": 0.95},   # без атрибута в 2ГИС → только мягкий
        "parking": {"type": "noul", "noul": 0.3},
    }
    f = build_filters(answers, parse_numbers("нет зонта, через 30 минут дождь, хочу в кофейню"))
    assert f.intent == "plan_route"
    assert f.category_primary.id == "coffee" and f.purchase_item.id == "umbrella"
    assert f.transport is None and f.max_travel_min == 10 and f.open == "at_arrival"
    assert f.hard == ["indoor"] and set(f.soft) == {"wifi", "sockets"}
    assert f.event_offset_min == 30 and not f.raw_text_search


def test_low_category_confidence_uses_raw_text():
    f = build_filters({"intent": ch("find_place", 0.9), "category_primary": ch("cafe", 0.4)}, parse_numbers("x"))
    assert f.category_primary is None and f.raw_text_search


def test_exact_number_replaces_bucket():
    f = build_filters({"max_travel": ch("20", 0.9)}, parse_numbers("кафе в 10 минутах ходьбы"))
    assert f.max_travel_min == 10


def test_unavailable_and_chips():
    f = unavailable_filters(parse_numbers("кофе"))
    chips = build_chips(f, load_catalog())
    assert f.source == "none" and f.raw_text_search
    assert chips[0].label == "Фильтры не определены"


def test_remove_chip():
    f = build_filters({"wifi": {"type": "noul", "noul": 0.9}, "category_primary": ch("coffee", 0.9)}, parse_numbers("x"))
    assert remove_chip(f, "hard:wifi").hard == []
    assert remove_chip(f, "category_primary").category_primary is None


def _client(handler):
    http = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    return JevClient(http, "https://jev.test", "k", "jev-1.13.0", timeout_s=1.5)


async def test_client_ok_and_body():
    seen = {}

    def handler(request: httpx.Request):
        seen["auth"] = request.headers["Authorization"]
        seen["path"] = request.url.path
        return httpx.Response(200, json={"answers": {"intent": ch("find_place", 0.9)}, "usage": {"input_tokens": 10}})

    answers = await _client(handler).ask({"query": "кофе"}, {"intent": {"type": "noul", "instructions": "x"}})
    assert answers["intent"]["choice"] == "find_place"
    assert seen == {"auth": "Bearer k", "path": "/v1/systemone"}


async def test_client_422_not_retried():
    calls = []

    def handler(request):
        calls.append(1)
        return httpx.Response(422, json={"detail": [{"loc": ["body", "questions"], "msg": "bad", "input": "secret"}]})

    with pytest.raises(JevSchemaError):
        await _client(handler).ask({}, {})
    assert len(calls) == 1


async def test_client_retries_429_once():
    calls = []

    def handler(request):
        calls.append(1)
        if len(calls) == 1:
            return httpx.Response(429, headers={"Retry-After": "0.05"})
        return httpx.Response(200, json={"answers": {}})

    assert await _client(handler).ask({}, {}) == {}
    assert len(calls) == 2


async def test_client_timeout_is_unavailable():
    def handler(request):
        raise httpx.ReadTimeout("slow", request=request)

    with pytest.raises(JevUnavailable):
        await _client(handler).ask({}, {})
