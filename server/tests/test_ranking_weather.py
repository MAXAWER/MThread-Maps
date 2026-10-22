from datetime import date

from app.ranking import bayes_rating, quality, search_score
from app.weather import WeatherService, rain_onset_from_minutely, sun_times
from app.config import Settings

from .conftest import at


def test_bayes_rating_prefers_many_reviews():
    assert bayes_rating(5.0, 2) < bayes_rating(4.7, 1000)
    assert bayes_rating(None, None) == 4.2
    assert 0 <= quality(1.0, 10_000) <= quality(5.0, 10_000) <= 1


def test_score_prefers_closer_and_better():
    near = search_score(0.8, 5, 10, 0.8, 0.5, 1.0)
    far = search_score(0.8, 25, 10, 0.8, 0.5, 1.0)
    unknown_hours = search_score(0.8, 5, 10, 0.8, 0.5, 0.5)
    assert near > far and near > unknown_hours


def test_rain_onset():
    values = [0.0] * 30 + [2.0] * 30  # 2 мм/ч с 30-й минуты: за 15 мин 0,5 мм
    onset, intensity = rain_onset_from_minutely(values)
    assert onset == 30 and intensity == 2.0
    assert rain_onset_from_minutely([0.5] * 60)[0] is None  # 0,125 мм за 15 мин — ниже порога


def test_sun_times_astana():
    # С марта 2024 года Казахстан живёт по UTC+5: 22 октября восход около 06:50, закат около 17:05.
    rise, sset = sun_times(51.128, 71.430, date(2026, 10, 22))
    assert "06:30" <= rise.strftime("%H:%M") <= "07:15"
    assert "16:45" <= sset.strftime("%H:%M") <= "17:30"


def test_rain_soon_needs_confirmation():
    svc = WeatherService(http=None, settings=Settings())
    now = at(15)
    ts = int(now.timestamp())
    ow = {"current": {"temp": 6, "feels_like": 3, "wind_speed": 5},
          "minutely": [{"dt": ts + 60 * i, "precipitation": 0.0 if i < 30 else 3.0} for i in range(60)],
          "hourly": [{"dt": ts - 600 + 3600 * h, "temp": 6, "feels_like": 3, "pop": 0.3, "wind_speed": 5} for h in range(4)]}
    om_yes = {"hourly": {"time": [ts + 3600 * h for h in range(4)], "precipitation_probability": [70] * 4,
                         "precipitation": [1.0] * 4, "temperature_2m": [6] * 4, "apparent_temperature": [3] * 4,
                         "wind_speed_10m": [5] * 4}}
    w = svc._derive({"ow": ow, "om": om_yes}, 51.128, 71.43, now)
    assert w["precip_starts_in_min"] == 30 and w["confirmed"] and w["rain_soon"]
    om_no = {"hourly": dict(om_yes["hourly"], precipitation_probability=[10] * 4)}
    w2 = svc._derive({"ow": ow, "om": om_no}, 51.128, 71.43, now)
    assert w2["precip_starts_in_min"] == 30 and not w2["rain_soon"]
