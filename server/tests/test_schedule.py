from app.schedule import is_open_late, open_at, open_info

from .conftest import at

DAYS = ("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")


def daily(f, t, days=DAYS):
    return {d: {"working_hours": [{"from": f, "to": t}]} for d in days}


def test_open_with_until():
    info = open_info(daily("08:00", "22:00"), at(21, 30))
    assert info.state == "open" and info.open_until == "22:00" and info.closes_in_min == 30


def test_closed_opens_tomorrow():
    info = open_info(daily("08:00", "22:00"), at(22, 30))
    assert info.state == "closed" and info.opens_at == "завтра 08:00"


def test_overnight_after_midnight():
    # среда 01:00 — работает по интервалу вторника 18:00–02:00
    info = open_info(daily("18:00", "02:00"), at(1, 0, day=21))
    assert info.state == "open" and info.open_until == "02:00"


def test_week_wrap_sunday_to_monday():
    sched = {"Sun": {"working_hours": [{"from": "22:00", "to": "05:00"}]}}
    info = open_info(sched, at(3, 0, day=26))  # понедельник 03:00
    assert info.state == "open"


def test_24x7_and_unknown():
    assert open_info({"is_24x7": True}, at(3)).state == "open"
    assert open_info(None, at(12)).state == "unknown"
    assert open_info({"comment": "по записи"}, at(12)).state == "unknown"


def test_day_off_is_closed():
    museum = daily("10:00", "18:00", DAYS[1:])
    info = open_info(museum, at(12, day=26))  # понедельник — выходной
    assert info.state == "closed" and info.opens_at == "завтра 10:00"


def test_closed_at_arrival():
    sched = daily("08:00", "22:00")
    assert open_at(sched, at(21, 50), 15).state == "closed"
    assert open_at(sched, at(21, 30), 15).state == "open"


def test_open_late():
    assert is_open_late(open_info(daily("10:00", "02:00"), at(20)), at(20))
    assert not is_open_late(open_info(daily("10:00", "21:00"), at(20)), at(20))
