"""Сценарии раздела 15 на демо-провайдерах: порядок событий SSE, содержание ответа и инварианты."""
import re

from app.invariants import check_invariants

from .conftest import KSH, MSW, NQZ, at, make_services, run_search

KK = re.compile(r"[әғқңөұүһі]", re.I)


def ok(ev):
    assert ev["order"][0] == "filters" and ev["order"][-1] == "done", ev["order"]
    assert "error" not in ev, ev.get("error")
    assert check_invariants(ev) == []
    return ev["answer"]


async def test_s1_walk_30_60_min(svc):
    ans = ok(await run_search(svc, "Я возле Mega Silk Way, хочу прогуляться 30–60 минут", MSW))
    assert ans["mode"] == "simple" and 1 <= len(ans["items"]) <= 3
    for it in ans["items"]:
        assert it["eta_min"] + it["visit_min"] <= 60


async def test_s2_quiet_coffee_with_socket(svc):
    ev = await run_search(svc, "Тихое место с кофе и розеткой недалеко", MSW)
    ans = ok(ev)
    assert ev["filters"]["filters"]["category_primary"]["id"] == "coffee"
    assert ans["items"] and all(it["category"] == "coffee" for it in ans["items"])
    assert all("sockets" in it["unconfirmed"] for it in ans["items"])  # розетки — «не подтверждено»


async def test_s3_kids_by_car(svc):
    ev = await run_search(svc, "Еду с ребёнком 5 лет — площадки или музей с интерактивом", MSW)
    ans = ok(ev)
    f = ev["filters"]["filters"]
    assert f["company"] == "kids" and f["kids_age"] == "3_7" and ev["filters"]["transport"] == "driving"
    cat = svc.catalog
    assert ans["items"] and all(cat.get(it["category"]).kids for it in ans["items"])
    assert all(it["transport"] == "driving" for it in ans["items"])


async def test_s4_tourist_before_flight(svc):
    ev = await run_search(svc, "Я турист, у меня 2 часа до вылета", NQZ)
    ans = ok(ev)
    f = ev["filters"]["filters"]
    assert f["time_budget_min"] == 120 and f["needs_return"]
    for it in ans["items"]:
        assert 2 * it["eta_min"] + it["visit_min"] <= 120  # запас на обратную дорогу


async def test_s5_night_only_open(svc):
    ans = ok(await run_search(svc, "Необычная ночная активность", MSW, when=at(23, 30, day=23)))
    assert ans["mode"] in ("simple", "empty")
    assert all(it["open_state"] != "closed" for it in ans["items"])
    assert all("Режим работы: нет данных" in it["notes"] for it in ans["items"] if it["open_state"] == "unknown")


async def test_s6_umbrella_then_coffee(svc):
    ev = await run_search(svc, "Нет зонта, через 30 минут дождь, хочу в кофейню", NQZ, when=at(15))
    ans = ok(ev)
    assert ans["mode"] == "plan", ans
    plan = ans["plan"]
    first, last = plan["legs"][0], plan["legs"][-1]
    assert first["purpose"].startswith("купить") and first["eta_min"] <= 27   # магазин до дождя (t_rain − 3)
    assert "кофейня" in last["purpose"]
    assert plan["rain_exposure_min"] == 0
    assert any("не подтверждено" in n for n in plan["notes"])
    assert plan["geometry"] and plan["explanation"]


async def test_s7_navigate_to_named_place(svc):
    ev = await run_search(svc, "Хан Шатыр", MSW)
    ans = ok(ev)
    assert ev["filters"]["filters"]["intent"] == "navigate_to"
    assert ans["mode"] == "place" and ans["items"][0]["name"] == "Хан Шатыр"
    assert ans["items"][0]["why"] is None  # без рекомендаций


async def test_s8_halal_budget(svc):
    ev = await run_search(svc, "Поесть халяль недорого", KSH)
    ans = ok(ev)
    f = ev["filters"]["filters"]
    assert "halal" in f["hard"] and f["price"] == "budget"
    assert ans["items"] and all("halal" in it["confirmed"] for it in ans["items"])


async def test_s9_kazakh_answer(svc):
    ev = await run_search(svc, "Жақын жерде дәмді кофе қайда?", KSH)
    ans = ok(ev)
    assert ev["filters"]["filters"]["category_primary"]["id"] == "coffee"
    assert ans["items"] and KK.search(ans["items"][0]["why"])


async def test_s10_rink_at_3am_is_honest(svc):
    ans = ok(await run_search(svc, "Каток с прокатом в 3 ночи", MSW, when=at(3)))
    assert ans["mode"] == "empty" and ans["items"] == []
    assert ans["relaxed"] and "закрыты" in ans["note"]


async def test_jev_unavailable_falls_back_to_text():
    svc = make_services()
    svc.jev = None
    ev = await run_search(svc, "кофейня", MSW)
    assert ev["filters"]["chips"][0]["label"] == "Фильтры не определены"
    assert ok(ev)["items"]


async def test_claude_unavailable_gives_top3_with_note():
    svc = make_services(llm=False)
    ans = ok(await run_search(svc, "кофейня рядом", MSW))
    assert 1 <= len(ans["items"]) <= 3 and all(it["why"] is None for it in ans["items"])
    assert ans["note"]


async def test_client_filters_skip_jev(svc):
    first = await run_search(svc, "Тихое место с кофе и розеткой недалеко", MSW)
    f = first["filters"]["filters"]
    f["soft"] = [k for k in f["soft"] if k != "sockets"]

    class Boom:
        async def ask(self, *a):
            raise AssertionError("Jev must not be called")

    svc.jev = Boom()
    from app.models import JevFilters
    ev = await run_search(svc, "Тихое место с кофе и розеткой недалеко", MSW, filters=JevFilters(**f))
    assert ev["filters"]["filters"]["source"] == "client"
    assert all("sockets" not in it["unconfirmed"] for it in ok(ev)["items"])
