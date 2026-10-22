from app.catalog import load_catalog
from app.dgis import Place
from app.planner import Group, optimize

from .conftest import at

CAT = load_catalog()


def P(pid, cat):
    return Place(id=pid, name=pid, lat=51.0, lon=71.0, category=cat, schedule={"is_24x7": True})


def test_umbrella_then_cafe_before_rain():
    w1, w2, d1 = P("w1", "supermarket"), P("w2", "supermarket"), P("d1", "coffee")
    g = Group(role="purchase", label="купить: зонт", queries=[], solves_rain=True, candidates=[w1, w2])
    times = {("user", "w1"): 6, ("user", "w2"): 28, ("user", "d1"): 31, ("w1", "d1"): 9, ("w2", "d1"): 4,
             ("w1", "w2"): 20, ("w2", "w1"): 20}
    opts = optimize([g], [d1], times, t_rain=30, purchase_min=5, margin=3, now=at(15), catalog=CAT, dest_purpose="кофейня")
    best = opts[0]
    assert [p.id for p in best.seq] == ["w1", "d1"]
    assert best.total_min == 20 and best.rain_exposure_min == 0 and best.feasible


def test_no_feasible_minimizes_rain_exposure():
    w1, d1 = P("w1", "supermarket"), P("d1", "coffee")
    g = Group(role="purchase", label="купить: зонт", queries=[], solves_rain=True, candidates=[w1])
    times = {("user", "w1"): 12, ("user", "d1"): 20, ("w1", "d1"): 5}
    best = optimize([g], [d1], times, t_rain=4, purchase_min=5, margin=3, now=at(15), catalog=CAT,
                    dest_purpose="кофейня")[0]
    assert not best.feasible and round(best.rain_exposure_min) == 8


def test_closed_stop_is_skipped():
    closed = Place(id="w1", name="w1", lat=51, lon=71, category="supermarket",
                   schedule={"Thu": {"working_hours": [{"from": "08:00", "to": "09:00"}]}})
    w2, d1 = P("w2", "supermarket"), P("d1", "coffee")
    g = Group(role="purchase", label="купить: зонт", queries=[], solves_rain=True, candidates=[closed, w2])
    times = {("user", "w1"): 3, ("user", "w2"): 10, ("w1", "d1"): 3, ("w2", "d1"): 5}
    best = optimize([g], [d1], times, t_rain=None, purchase_min=5, margin=3, now=at(15), catalog=CAT,
                    dest_purpose="кофейня")[0]
    assert best.seq[0].id == "w2"


def test_two_waypoint_groups_any_order():
    a, b, d = P("a", "pharmacy"), P("b", "flowers"), P("d", "cafe")
    ga = Group(role="purchase", label="купить: аптека", queries=[], candidates=[a])
    gb = Group(role="secondary", label="цветы", queries=[], candidates=[b])
    times = {("user", "a"): 10, ("user", "b"): 2, ("a", "b"): 8, ("b", "a"): 3, ("a", "d"): 2, ("b", "d"): 9}
    best = optimize([ga, gb], [d], times, t_rain=None, purchase_min=5, margin=3, now=at(15), catalog=CAT,
                    dest_purpose="кафе")[0]
    assert [p.id for p in best.seq] == ["b", "a", "d"]  # 2+5+3+5+2 = 17 против 10+5+8+5+9 = 37
