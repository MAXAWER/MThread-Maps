"""Монетизация: такси с переходом в приложение партнёра, партнёрские ссылки, реклама и учёт переходов."""
import json
import uuid
from urllib.parse import parse_qs, urlparse

import pytest
from fastapi.testclient import TestClient

from app import partners
from app.deck import build_deck
from app.main import app
from app.models import DeckRequest

from .conftest import MSW, StubWeather, at, make_services


def test_yandex_go_link_has_points_tariff_and_ref(monkeypatch):
    monkeypatch.setenv("YANDEX_TAXI_REF", "progulka_kz")
    url = partners.yandex_go_link((51.089, 71.4086), (51.1283, 71.4305), "business")
    u = urlparse(url)
    q = {k: v[0] for k, v in parse_qs(u.query).items()}
    assert u.netloc == "3.redirect.appmetrica.yandex.com" and u.path == "/route"
    assert q["start-lat"] == "51.089000" and q["end-lon"] == "71.430500" and q["tariffClass"] == "business"
    assert q["ref"] == "progulka_kz" and q["appmetrica_tracking_id"] == partners.YANDEX_GO_STORE_REDIRECT


def test_estimate_price_is_a_rounded_range():
    lo, hi = partners.estimate_price("econom", 6500, 900)
    assert lo % 50 == 0 and hi % 50 == 0 and lo < hi
    assert partners.estimate_price("business", 6500, 900)[0] > lo


def test_partner_links_only_for_known_pages_with_affiliate(monkeypatch):
    monkeypatch.setenv("AFF_GLOVOAPP_COM", "utm_source=progulka")
    links = partners.partner_links(["https://glovoapp.com/ru/kz/astana/stores/coffee-boom-nur1",
                                    "https://yandex.kz/maps/org/x/menu/", "https://ticketon.kz/astana/event/abc", None])
    assert [(x["kind"], x["partner"]) for x in links] == [("delivery", "Glovo"), ("tickets", "Ticketon")]
    assert links[0]["url"].endswith("?utm_source=progulka")


async def test_deck_marks_and_lifts_promoted_place(monkeypatch):
    svc = make_services()
    base = await build_deck(DeckRequest(lat=MSW[0], lon=MSW[1], local_time=at(15), categories=["coffee", "park"]), svc)
    last = base["cards"][-1]
    monkeypatch.setenv("PROMOTIONS_JSON", json.dumps([{"poi_id": last["poi_id"], "offer": "Кофе −10%", "promo_code": "PROGULKA",
                                                       "boost": 1.0}]))
    monkeypatch.setattr(partners, "_promos", None)
    deck = await build_deck(DeckRequest(lat=MSW[0], lon=MSW[1], local_time=at(15), categories=["coffee", "park"]), svc)
    promoted = [c for c in deck["cards"] if c["sponsored"]]
    assert promoted and promoted[0]["poi_id"] == last["poi_id"] and promoted[0]["sponsored"]["label"] == "Реклама"
    assert deck["cards"][0]["poi_id"] == last["poi_id"]  # поднялось наверх
    monkeypatch.setattr(partners, "_promos", None)


@pytest.fixture()
def client(monkeypatch):
    for key in ("DGIS_API_KEY", "JEV_API_KEY", "ANTHROPIC_API_KEY", "OPENWEATHER_API_KEY", "WEATHER_OVERRIDE"):
        monkeypatch.setenv(key, "")
    monkeypatch.setenv("DEMO_MODE", "1")
    monkeypatch.setenv("ADMIN_TOKEN", "secret-test")
    with TestClient(app) as c:
        app.state.svc.weather = StubWeather({})
        yield c


def test_taxi_estimate_and_clickout_stats(client):
    iid = {"X-Install-Id": str(uuid.uuid4())}
    r = client.post("/v1/taxi/estimate", headers=iid, json={"origin": {"lat": 51.089, "lon": 71.4086},
                                                          "to": {"lat": 51.1283, "lon": 71.4305}, "to_name": "Байтерек"})
    assert r.status_code == 200
    d = r.json()
    assert d["priced_by"] == "estimate" and d["distance_m"] > 0 and d["geometry"]
    yandex = next(p for p in d["partners"] if p["id"] == "yandex_go")
    assert yandex["prefill"] and len(yandex["prices"]) == 3 and yandex["prices"][0]["deeplink"].startswith("https://3.redirect")
    assert client.post("/v1/clickout", headers=iid, json={"kind": "taxi", "partner": "yandex_go", "value": 1500}).json()["ok"]
    assert client.get("/v1/admin/stats").status_code == 403
    stats = client.get("/v1/admin/stats", headers={"Authorization": "Bearer secret-test"}).json()
    assert stats["counts"]["taxi:yandex_go"] >= 1
