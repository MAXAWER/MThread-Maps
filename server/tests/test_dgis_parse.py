import httpx

from app.catalog import load_catalog
from app.config import Settings
from app.dgis import DgisClient, UsageCounters, parse_place, parse_route, parse_wkt

ITEM = {
    "id": "70000001023",
    "name": "Кофейня Тест",
    "point": {"lat": 51.1, "lon": 71.4},
    "address_name": "проспект Туран, 1",
    "rubrics": [{"name": "Пекарни", "kind": "additional"}, {"name": "Кофейни", "kind": "primary"}],
    "schedule": {"Mon": {"working_hours": [{"from": "08:00", "to": "22:00"}]}},
    "reviews": {"general_rating": 4.7, "general_review_count": 312},
    "org": {"branch_count": 12},
    "attribute_groups": [{"name": "Удобства", "attributes": [{"tag": "wifi", "name": "Wi-Fi"}]},
                         {"name": "Средний чек", "attributes": [{"tag": "avg_price", "name": "Средний чек 2 500 тнг"}]}],
    "contact_groups": [{"contacts": [{"type": "phone", "value": "+77001112233"}]}],
}


def test_parse_place():
    p = parse_place(ITEM, load_catalog())
    assert p.category == "coffee"  # основная рубрика проверяется первой
    assert p.rating == 4.7 and p.reviews == 312 and p.branch_count == 12
    assert any("wifi" in a for a in p.attributes) and p.avg_check == 2500
    assert p.phone == "+77001112233"


def test_parse_route():
    data = {"result": [{
        "total_distance": 900, "total_duration": 660,
        "maneuvers": [
            {"comment": "Начало", "type": "begin", "outcoming_path": {"distance": 400, "duration": 300,
             "geometry": [{"selection": "LINESTRING(71.40 51.10, 71.41 51.10)"}]}},
            {"comment": "Поверните налево", "type": "crossroad", "turn_direction": "left",
             "outcoming_path": {"distance": 500, "duration": 360,
                                "geometry": [{"selection": "LINESTRING(71.41 51.10, 71.41 51.11)"}]}},
            {"comment": "Финиш", "type": "end"},
        ]}]}
    r = parse_route(data)
    assert r["total_duration_s"] == 660 and len(r["maneuvers"]) == 3
    assert r["geometry"] == [[71.40, 51.10], [71.41, 51.10], [71.41, 51.11]]
    assert r["maneuvers"][-1]["lat"] == 51.11


def test_wkt():
    assert parse_wkt("LINESTRING(71.4 51.1, 71.5 51.2)") == [[71.4, 51.1], [71.5, 51.2]]


async def test_matrix_chunks_and_maps_ids():
    bodies = []

    def handler(request: httpx.Request):
        import json
        body = json.loads(request.content)
        bodies.append(body)
        routes = [{"source_id": s, "target_id": t, "duration": 60 * (t + 1), "distance": 100, "status": "OK"}
                  for s in body["sources"] for t in body["targets"]]
        return httpx.Response(200, json={"routes": routes})

    s = Settings(dgis_key="k", matrix_max_targets=4)
    client = DgisClient(httpx.AsyncClient(transport=httpx.MockTransport(handler)), s, load_catalog(), UsageCounters(1000))
    pts = [(51.0 + i / 100, 71.0) for i in range(7)]
    res = await client.matrix(pts, [0], list(range(1, 7)), "walking")
    assert len(bodies) == 2 and all(len(b["targets"]) <= 4 and len(b["sources"]) == 1 for b in bodies)
    assert set(res) == {(0, t) for t in range(1, 7)}
    assert client.counters.counts["matrix"] == 2
