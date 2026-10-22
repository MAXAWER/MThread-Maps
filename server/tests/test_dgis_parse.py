import httpx

from app.catalog import load_catalog
from app.config import Settings
from app.dgis import DgisClient, UsageCounters, parse_place, parse_public_transport, parse_route, parse_wkt

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


# Сокращённый ответ Public Transport API 2.0 той же структуры, что живой (Mega Silk Way → Байтерек).
PT_VARIANT = {
    "id": 1, "total_distance": 6508, "total_duration": 2159, "transfer_count": 0,
    "total_walkway_distance": "пешком 12 мин",
    "schedules": [{"type": "precise", "precise_time": "13:52", "start_time_utc": 1_000_000 + 600}],
    "movements": [
        {"type": "walkway", "distance": 534, "moving_duration": 384, "waiting_duration": 0,
         "waypoint": {"name": "Старт", "subtype": "start"},
         "alternatives": [{"geometry": [{"selection": "LINESTRING(71.4086 51.089, 71.4029 51.0899)"}]}]},
        {"type": "passage", "distance": 5458, "moving_duration": 1320, "waiting_duration": 79,
         "waypoint": {"name": "Назарбаев университет", "subtype": "bus"},
         "routes": [{"subtype": "bus", "names": ["47"], "color": "#1a73f0"}],
         "platforms": {"names": ["ЭКСПО-2017"]},
         "alternatives": [{"geometry": [{"selection": "LINESTRING(71.4029 51.0899, 71.41 51.11, 71.42 51.125)"}],
                           "platforms": [{"geometry": "POINT(71.4029 51.0899)"}, {"geometry": "POINT(71.41 51.11)"},
                                         {"geometry": "POINT(71.42 51.125)"}]}]},
        {"type": "walkway", "distance": 516, "moving_duration": 376, "waiting_duration": 0,
         "waypoint": {"name": "Talan towers", "subtype": "pedestrian"},
         "alternatives": [{"geometry": [{"selection": "LINESTRING(71.42 51.125, 71.4305 51.1283)"}]}]},
        {"type": "walkway", "distance": 0, "moving_duration": 0, "waypoint": {"name": "Финиш", "subtype": "finish"}},
    ],
}


def test_parse_public_transport_options():
    slower = {**PT_VARIANT, "id": 2, "total_duration": 2452}
    r = parse_public_transport([PT_VARIANT, slower], (51.089, 71.4086), now_utc=1_000_000)
    assert len(r["options"]) == 2 and r["total_duration_s"] == 2159
    walk1, bus, walk2 = r["options"][0]["legs"]
    assert walk1["kind"] == "walk" and walk1["to_name"] == "Назарбаев университет"
    assert bus["kind"] == "bus" and bus["names"] == ["47"] and bus["color"] == "#1a73f0"
    assert [s["name"] for s in bus["stops"]] == ["Назарбаев университет", "ЭКСПО-2017", "Talan towers"]
    assert bus["stops"][1]["lat"] == 51.11 and bus["wait_s"] == 79
    assert walk2["to_name"] == "цели"
    o = r["options"][0]
    assert o["departure"] == "13:52" and o["departure_in_min"] == 10
    assert r["geometry"][0] == [71.4086, 51.089] and r["geometry"][-1] == [71.4305, 51.1283]
    assert r["maneuvers"][1]["text"].startswith("Автобус 47 до «Talan towers»")
    assert o["interval_min"] is None


def test_parse_public_transport_interval_only():
    """Без точного расписания — интервал движения, а не выдуманное время отправления."""
    v = {**PT_VARIANT, "schedules": [{"type": "periodic", "period": 20, "precise_time": "", "start_time_utc": 1_000_900}]}
    o = parse_public_transport([v], (51.089, 71.4086), now_utc=1_000_000)["options"][0]
    assert o["departure"] is None and o["departure_in_min"] is None and o["interval_min"] == 20


def test_route_options_labels():
    from app.dgis import label_route_options as route_options

    data = parse_route({"result": [
        {"total_distance": 900, "total_duration": 600, "maneuvers": [
            {"comment": "Старт", "outcoming_path": {"geometry": [{"selection": "LINESTRING(71.4 51.1, 71.41 51.1)"}]}}]},
        {"total_distance": 950, "total_duration": 780, "maneuvers": [
            {"comment": "Старт", "outcoming_path": {"geometry": [{"selection": "LINESTRING(71.4 51.1, 71.4 51.11)"}]}}]},
    ]})
    labels = [o["label"] for o in route_options(data)]
    assert labels == ["Быстрее всего", "+3 мин"]


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
