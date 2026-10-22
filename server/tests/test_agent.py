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

    async def agent_loop(self, payload, execute, on_turn=None):
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
                "idea": None}

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


async def test_agent_failure_falls_back_to_pipeline():
    svc = make_services()
    svc.llm = ScriptedAgentLLM(fail=True)
    events = await _events(svc)
    names = [n for n, _ in events]
    assert "progress" in names and "filters" in names and "answer" in names and names[-1] == "done"
