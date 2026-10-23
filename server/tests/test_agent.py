"""Агент подбора: инструменты исполняются кодом, ответ собирается из фактов 2ГИС, маршрут прикладывается."""
import json

from app.search import stream_search
from app.models import SearchRequest

from .conftest import MSW, at, make_services


class ScriptedAgentLLM:
    """Подменная модель: ищет, проверяет маршрут и отвечает, как это делал бы Sonnet."""

    def __init__(self, fail: bool = False):
        self.fail = fail
        self.payload = None

    async def agent_loop(self, payload, execute, on_turn=None, on_web=None, validate=None):
        self.payload = payload
        if self.fail:
            return None
        if on_turn:
            await on_turn(0)
        found = await execute("search_places", {"query": "кофейня", "radius_m": 3000})
        first = found["places"][0]["id"]
        route = await execute("route_options", {"place_id": first, "transport": "walking"})
        return {"items": [{"place_id": first, "why": "Ближе всех и открыто", "visit_min": 40, "action": "Идите пешком",
                           "transport": "walking", "route_option_id": route["options"][0]["id"]}],
                "understood": ["Кофейня", "Пешком"], "summary": "Нашёл кофейню рядом.", "show_route": True,
                "idea": None, "trip": False, "requested_steps": ["кофейня"], "skipped": [], "remember": None}

    # остальные методы для запасного конвейера
    async def answer(self, payload):
        return None


async def _events(svc, query="кофе рядом"):
    req = SearchRequest(query=query, lat=MSW[0], lon=MSW[1], local_time=at(14))
    out = []
    async for chunk in stream_search(req, svc):
        lines = chunk.strip().split("\n")
        out.append((lines[0].removeprefix("event: "), json.loads(lines[1].removeprefix("data: "))))
    return out


async def test_agent_answer_with_route():
    svc = make_services()
    svc.llm = ScriptedAgentLLM()
    events = await _events(svc)
    names = [n for n, _ in events]
    assert names[0] == "progress" and "answer" in names and names[-1] == "done"
    answer = next(d for n, d in events if n == "answer")
    assert answer["items"][0]["why"] == "Ближе всех и открыто" and answer["summary"] == "Нашёл кофейню рядом."
    assert answer["route"] and answer["route_option_id"] == answer["route"]["options"][0]["id"]
    chips = next(d for n, d in events if n == "filters")["chips"]
    assert [c["label"] for c in chips] == ["Кофейня", "Пешком"] and not chips[0]["removable"]
    assert "profile" in svc.llm.payload and svc.llm.payload["query"] == "кофе рядом"


class TripAgentLLM(ScriptedAgentLLM):
    """Многошаговый запрос: два места, проверка порядка, факты из интернета и предложение запомнить адрес."""

    async def agent_loop(self, payload, execute, on_turn=None, on_web=None, validate=None):
        self.payload = payload
        if on_web:
            await on_web("web_search", {"query": "кофейня меню цены Астана"})
        coffee = (await execute("search_places", {"query": "кофейня", "radius_m": 3000}))["places"][0]["id"]
        park = (await execute("search_places", {"query": "парк", "radius_m": 3000}))["places"][0]["id"]
        trip = await execute("check_trip", {"place_ids": [coffee, park], "visit_min": [30, 60],
                                            "transports": ["walking", "driving"]})
        assert len(trip["stops"]) == 2 and trip["stops"][0]["arrive_at"] and trip["total_min"] >= 90
        assert trip["stops"][1]["transport"] == "driving" and trip["stops"][1]["route_options"]
        self.chosen = trip["stops"][1]["route_options"][-1]["id"]  # не самый быстрый — выбор агента
        addr = (await execute("find_address", {"query": "Хан Шатыр"}))["results"][0]["place_id"]
        item = {"why": "—", "action": "—", "route_option_id": None}
        result = {"items": [{**item, "transport": "walking", "place_id": coffee, "visit_min": 30, "purpose": "Кофе",
                             "evidence": [{"fact": "Капучино — 1200 ₸", "source": "https://example.kz/menu"},
                                          {"fact": "Без ссылки", "source": "javascript:alert(1)"}]},
                            {**item, "transport": "driving", "route_option_id": self.chosen, "place_id": park,
                             "visit_min": 60, "purpose": "Прогулка", "evidence": []}],
                  "understood": ["Кофе", "Парк"], "summary": "Сначала кофе, потом парк.", "show_route": False,
                  "idea": None, "trip": True, "remember": {"name": "Брат", "place_id": addr, "icon": "family"},
                  "requested_steps": ["кофе", "парк", "аптека"], "skipped": []}
        # Шаг «аптека» потерян — проверка возвращает ответ на доработку; агент объясняет пропуск.
        problem = validate(result)
        assert problem and "3 шагов" in problem
        result["skipped"] = [{"step": "аптека", "reason": "рядом нет открытой"}]
        assert validate(result) is None
        assert validate({**result, "items": [{**result["items"][0], "place_id": "выдуманный"}]})
        return result


async def test_agent_trip_plan_evidence_and_remember():
    svc = make_services()
    svc.llm = TripAgentLLM()
    events = await _events(svc, "кофе, потом погулять в парке")
    assert any(n == "progress" and d["text"].startswith("Ищу в интернете") for n, d in events)
    answer = next(d for n, d in events if n == "answer")
    assert answer["mode"] == "plan" and [leg["purpose"] for leg in answer["plan"]["legs"]] == ["Кофе", "Прогулка"]
    legs = answer["plan"]["legs"]
    assert legs[0]["arrive_at"] and legs[0]["leave_at"] and legs[1]["visit_min"] == 60 and answer["plan"]["geometry"]
    ev = answer["items"][0]["evidence"]
    assert ev[0] == {"fact": "Капучино — 1200 ₸", "source": "https://example.kz/menu"} and ev[1]["source"] is None
    assert answer["remember"]["name"] == "Брат" and answer["remember"]["icon"] == "family"
    # На втором этапе — выбранный агентом вариант дороги (авто), целиком: линия и подпись.
    assert legs[1]["transport"] == "driving" and legs[1]["route"]["id"] == str(svc.llm.chosen)
    assert legs[1]["route"]["geometry"] and legs[1]["route_label"]
    assert answer["skipped"] == ["аптека — рядом нет открытой"]


class FollowUpAgentLLM(ScriptedAgentLLM):
    """Уточнение «добавь ещё парк»: места прошлого ответа доступны по своим id."""

    async def agent_loop(self, payload, execute, on_turn=None, on_web=None, validate=None):
        self.payload = payload
        prev = payload["previous"]["items"][0]["place_id"]
        park = (await execute("search_places", {"query": "парк", "radius_m": 3000}))["places"][0]["id"]
        trip = await execute("check_trip", {"place_ids": [prev, park], "visit_min": [20, 40], "transports": ["walking"]})
        assert "error" not in trip
        item = {"why": "—", "action": "—", "transport": "walking", "route_option_id": None, "evidence": []}
        return {"items": [{**item, "place_id": prev, "visit_min": 20, "purpose": "Кофе"},
                          {**item, "place_id": park, "visit_min": 40, "purpose": "Парк"}],
                "understood": ["Добавить парк"], "summary": "Добавил парк после кофе.", "show_route": False, "idea": None,
                "trip": True, "remember": None, "requested_steps": ["кофе", "парк"], "skipped": []}


async def test_agent_follow_up_keeps_previous_stops():
    svc = make_services()
    svc.llm = FollowUpAgentLLM()
    from app.models import PreviousAnswer, PreviousItem
    prev = PreviousAnswer(query="кофе рядом", trip=False,
                          items=[PreviousItem(poi_id="prev-cafe-1", name="Кофейня у дома", lat=MSW[0] + 0.003, lon=MSW[1])])
    req = SearchRequest(query="добавь ещё парк", lat=MSW[0], lon=MSW[1], local_time=at(14), previous=prev)
    events = []
    async for chunk in stream_search(req, svc):
        lines = chunk.strip().split("\n")
        events.append((lines[0].removeprefix("event: "), json.loads(lines[1].removeprefix("data: "))))
    answer = next(d for n, d in events if n == "answer")
    assert answer["mode"] == "plan" and [leg["name"] for leg in answer["plan"]["legs"]][0] == "Кофейня у дома"
    assert svc.llm.payload["previous"]["query"] == "кофе рядом"


async def test_agent_failure_falls_back_to_pipeline():
    svc = make_services()
    svc.llm = ScriptedAgentLLM(fail=True)
    events = await _events(svc)
    names = [n for n, _ in events]
    assert "progress" in names and "filters" in names and "answer" in names and names[-1] == "done"


class ContextAgentLLM(ScriptedAgentLLM):
    """«По дороге к брату заедем за кофе, выйду через час, веди»: контекст приложения, поиск у точки, время, навигация."""

    async def agent_loop(self, payload, execute, on_turn=None, on_web=None, validate=None):
        self.payload = payload
        app = payload["app"]
        brother = app["open_route"]["place_id"]
        coffee = await execute("search_places", {"query": "кофейня", "radius_m": 1500, "near_place_id": brother})
        assert coffee["near"] == "Брат" and "distance_from_center_m" in coffee["places"][0]
        cid = coffee["places"][0]["id"]
        trip = await execute("check_trip", {"place_ids": [cid, brother], "visit_min": [15, 60], "transports": ["walking"],
                                            "start_in_min": 60})
        self.trip = trip
        item = {"why": "—", "action": "—", "transport": "walking", "route_option_id": None, "evidence": []}
        return {"items": [{**item, "place_id": cid, "visit_min": 15, "purpose": "Кофе"},
                          {**item, "place_id": brother, "visit_min": 60, "purpose": "К брату"}],
                "understood": ["Кофе по пути", "Через час"], "summary": "Кофе по пути к брату.", "show_route": False,
                "idea": None, "trip": True, "remember": None, "requested_steps": ["кофе", "к брату"], "skipped": [],
                "trip_start_in_min": 60, "start_navigation": True}


async def test_agent_uses_app_context_near_search_start_time_and_navigation():
    from app.models import AppState, PreviousItem
    svc = make_services()
    svc.llm = ContextAgentLLM()
    brother = PreviousItem(poi_id="saved-brother", name="Брат", lat=MSW[0] + 0.01, lon=MSW[1] + 0.01)
    state = AppState(screen="route", route_to=brother, route_transport="walking", route_option="Пешком · 25 мин",
                     recent_queries=["кофе"], battery=15, charging=False)
    req = SearchRequest(query="по дороге к брату заедем за кофе, выйду через час, веди", lat=MSW[0], lon=MSW[1],
                        local_time=at(14), app_state=state)
    events = []
    async for chunk in stream_search(req, svc):
        lines = chunk.strip().split("\n")
        events.append((lines[0].removeprefix("event: "), json.loads(lines[1].removeprefix("data: "))))
    app = svc.llm.payload["app"]
    assert app["screen"] == "route" and app["battery_percent"] == 15 and app["open_route"]["chosen_option"] == "Пешком · 25 мин"
    assert svc.llm.trip["start_at"] == "15:00"
    answer = next(d for n, d in events if n == "answer")
    assert answer["start_navigation"] is True and answer["mode"] == "plan"
    assert answer["plan"]["legs"][0]["arrive_at"] >= "15:00"
