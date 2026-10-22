"""HTTP API в демо-режиме (без сети: погода подменена)."""
import json
import uuid

import pytest
from fastapi.testclient import TestClient

from app.main import app

from .conftest import KSH, MSW, StubWeather

IID = {"X-Install-Id": str(uuid.uuid4())}


@pytest.fixture(scope="module")
def client(monkeypatch_module):
    with TestClient(app) as c:
        app.state.svc.weather = StubWeather({"precip_starts_in_min": 30})
        yield c


@pytest.fixture(scope="module")
def monkeypatch_module():
    mp = pytest.MonkeyPatch()
    for key in ("DGIS_API_KEY", "JEV_API_KEY", "ANTHROPIC_API_KEY", "OPENWEATHER_API_KEY", "WEATHER_OVERRIDE"):
        mp.setenv(key, "")
    mp.setenv("DEMO_MODE", "1")
    mp.setenv("DAILY_SEARCH_LIMIT", "3")
    yield mp
    mp.undo()


def sse_events(text: str) -> list[tuple[str, dict]]:
    out = []
    for block in text.strip().split("\n\n"):
        lines = block.split("\n")
        out.append((lines[0][7:], json.loads(lines[1][6:])))
    return out


def test_health_and_status(client):
    assert client.get("/healthz").json() == {"ok": True}
    st = client.get("/v1/status").json()
    assert st["data_source"] == "demo" and st["settings"]["demo_mode"] is True
    assert "key" not in json.dumps(st["settings"]).lower() or "jev_model" in st["settings"]


def test_install_id_required(client):
    r = client.post("/v1/search", json={"query": "кофе", "lat": 51.1, "lon": 71.4, "local_time": "2026-10-22T15:10:00+05:00"})
    assert r.status_code == 400 and r.json()["code"] == "install_id_required"


def test_search_sse_and_daily_limit(client):
    body = {"query": "кофейня рядом", "lat": MSW[0], "lon": MSW[1], "local_time": "2026-10-22T15:10:00+05:00",
            "transport_hint": "walking"}
    headers = {"X-Install-Id": str(uuid.uuid4())}
    r = client.post("/v1/search", json=body, headers=headers)
    assert r.status_code == 200 and r.headers["content-type"].startswith("text/event-stream")
    names = [e for e, _ in sse_events(r.text)]
    assert names == ["filters", "candidates", "answer", "done"]
    for _ in range(2):
        client.post("/v1/search", json=body, headers=headers)
    r = client.post("/v1/search", json=body, headers=headers)
    assert r.status_code == 429 and r.json()["code"] == "daily_limit"


def test_validation_error_format(client):
    r = client.post("/v1/search", json={"query": ""}, headers=IID)
    assert r.status_code == 422 and r.json()["code"] == "bad_request"


def test_suggest_place_route(client):
    s = client.get("/v1/suggest", params={"q": "Хан", "lat": KSH[0], "lon": KSH[1]}, headers=IID).json()
    item = next(i for i in s["items"] if i["title"] == "Хан Шатыр")
    card = client.get(f"/v1/places/{item['id']}", headers=IID).json()
    assert card["name"] == "Хан Шатыр" and card["open_state"] == "unknown"
    route = client.post("/v1/route", json={"from": {"lat": MSW[0], "lon": MSW[1]}, "to": {"lat": KSH[0], "lon": KSH[1]},
                                           "transport": "walking"}, headers=IID).json()
    assert route["geometry"] and route["maneuvers"][-1]["text"] == "Вы на месте"
    # Цель не округляется (это координаты места), округляется только позиция пользователя.
    precise = client.post("/v1/route", json={"from": {"lat": 51.08904, "lon": 71.40861}, "to": {"lat": 51.13245, "lon": 71.40367},
                                             "transport": "walking"}, headers=IID).json()
    assert precise["geometry"][-1] == [71.40367, 51.13245]
    assert precise["geometry"][0] == [71.409, 51.089]
    assert client.get("/v1/places/nope", headers=IID).status_code == 404


def test_deck(client):
    r = client.post("/v1/deck", json={"lat": MSW[0], "lon": MSW[1], "local_time": "2026-10-24T10:00:00+05:00",
                                      "transport": "walking", "categories": ["coffee", "park", "museum", "mall"]},
                    headers=IID).json()
    assert r["cards"] and r["is_weekend"]
    assert all(len(c["why"]) <= 100 and 0 <= c["k"] <= 1 for c in r["cards"])
    rain_chip = [c for c in r["cards"] if c["context_chip"] and "крытое" in c["context_chip"]]
    assert rain_chip and all(c["indoor"] for c in rain_chip)


def test_profile_update(client):
    r = client.post("/v1/profile/update", json={"event": {"query": "люблю виды на город", "action": "selected",
                                                          "selected_category": "viewpoint"}}, headers=IID).json()
    keys = {o["key"] for o in r["ops"]}
    assert "category.viewpoint" in keys and "explicit" in keys


def test_notify_rain(client):
    r = client.post("/v1/notify/candidates", json={"lat": MSW[0], "lon": MSW[1], "local_time": "2026-10-22T15:10:00+05:00",
                                                   "transport": "walking", "trigger": "rain"}, headers=IID).json()
    assert r["items"] and len(r["text"]) <= 90 and "по прогнозу" in r["text"].lower()
    cat = app.state.svc.catalog
    assert all(cat.get(i["category"]).indoor and i["eta_min"] <= 27 for i in r["items"])


def test_weather_endpoint(client):
    w = client.get("/v1/weather", params={"lat": MSW[0], "lon": MSW[1]}, headers=IID).json()
    assert w["precip_starts_in_min"] == 30 and w["source"] == "override"
