from app.catalog import load_catalog
from app.models import ProfileEvent, ProfileUpdateRequest
from app.profile import sanitize_ops


def req(query="", action="selected"):
    return ProfileUpdateRequest(event=ProfileEvent(query=query, action=action))


def op(key, value, explicit=False, kind="add", evidence="запрос"):
    return {"op": kind, "key": key, "value": value, "explicit": explicit, "evidence": evidence}


def test_whitelist_and_forbidden_topics():
    ops = [
        op("category.coffee", "coffee", kind="reinforce"),
        op("unknown_key", "x"),
        op("dislikes", "шумные места"),
        op("explicit", "у меня диабет", explicit=True),       # здоровье — запрещено
        op("company", "ходит в мечеть по пятницам"),          # религия — запрещено
    ]
    out = sanitize_ops(ops, req("тихая кофейня"), load_catalog())
    assert [(o.key, o.value) for o in out] == [("category.coffee", "coffee"), ("dislikes", "шумные места")]


def test_diet_only_as_explicit_statement():
    cat = load_catalog()
    inferred = sanitize_ops([op("cuisine", "халяль")], req("поесть недорого"), cat)
    stated = sanitize_ops([op("cuisine", "халяль", explicit=True)], req("я ем только халяль"), cat)
    assert inferred == [] and stated[0].value == "халяль"


def test_lengths_trimmed():
    out = sanitize_ops([op("explicit", "люблю " + "виды " * 60, explicit=True, evidence="e" * 200)], req("люблю виды"),
                       load_catalog())
    assert len(out[0].value) <= 120 and len(out[0].evidence) <= 80
