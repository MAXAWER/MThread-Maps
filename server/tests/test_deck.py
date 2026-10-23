"""«Для вас»: фильтры панели и места по недавним поискам."""
from app.deck import build_deck, search_seeds
from app.models import DeckFilters, DeckRequest

from .conftest import MSW, at, make_services


def _req(**kw) -> DeckRequest:
    return DeckRequest(lat=MSW[0], lon=MSW[1], local_time=at(15), categories=["coffee", "park", "museum"], **kw)


async def test_filters_indoor_and_rating():
    svc = make_services()
    deck = await build_deck(_req(filters=DeckFilters(place="indoor", min_rating=4.0)), svc)
    assert deck["cards"], "колода не пустая"
    assert all(c["indoor"] for c in deck["cards"])
    assert all(c["rating"] is not None and c["rating"] >= 4.0 for c in deck["cards"])
    outdoor = await build_deck(_req(filters=DeckFilters(place="outdoor")), svc)
    assert all(not c["indoor"] for c in outdoor["cards"])


async def test_recent_search_adds_marked_places():
    svc = make_services()
    deck = await build_deck(_req(recent_queries=["ресторан", "как добраться до Байтерека"]), svc)
    marked = [c for c in deck["cards"] if c["from_search"]]
    assert marked and all(c["from_search"] == "ресторан" for c in marked)
    assert any((c["context_chip"] or "").startswith("Как в вашем поиске") for c in marked)


def test_search_seeds_skip_routes_and_addresses():
    assert search_seeds(["Как добраться на автобусе", "Кенесары 40", "горячий шоколад", "кофе", "суши"]) == \
        ["горячий шоколад", "кофе"]
