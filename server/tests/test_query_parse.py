from app.query_parse import detect_lang, kids_age_bucket, parse_numbers


def test_budget_range():
    n = parse_numbers("Я возле Mega Silk Way, хочу прогуляться 30–60 минут")
    assert n.time_budget_min == 60 and n.max_travel_min is None


def test_event_offset():
    n = parse_numbers("нет зонта, через 30 минут дождь, хочу в кофейню")
    assert n.event_offset_min == 30 and n.time_budget_min is None


def test_travel_time():
    assert parse_numbers("кафе в 10 минутах ходьбы").max_travel_min == 10
    assert parse_numbers("до 15 минут на машине").max_travel_min == 15


def test_hours_and_return():
    n = parse_numbers("Я турист, у меня 2 часа до вылета")
    assert n.time_budget_min == 120 and n.needs_return


def test_kids_age():
    n = parse_numbers("Еду с ребёнком 5 лет — площадки или музей с интерактивом")
    assert n.kids_age_years == 5 and kids_age_bucket(5) == "3_7"


def test_tenge_and_group():
    n = parse_numbers("ужин до 5 000 тенге, нас 6 человек")
    assert n.price_max_tenge == 5000 and n.group_size == 6
    assert parse_numbers("кофе до 2к тг").price_max_tenge == 2000


def test_word_durations():
    assert parse_numbers("хочу погулять час").time_budget_min == 60
    assert parse_numbers("есть полчаса").time_budget_min == 30


def test_night_time_is_not_duration():
    n = parse_numbers("Каток с прокатом в 3 ночи")
    assert n.time_budget_min is None and n.max_travel_min is None


def test_lang():
    assert detect_lang("Жақын жерде дәмді кофе қайда?") == "kk"
    assert detect_lang("Где кофе?") == "ru"
    assert detect_lang("coffee nearby") == "en"
