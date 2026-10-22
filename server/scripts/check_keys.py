"""Этап 0 (раздел 16): каждый ключ проверяется одним запросом. Значения ключей не печатаются.

Запуск:  python scripts/check_keys.py
Образцы ответов 2ГИС сохраняются в server/var/day1/ (не в git) — по ним сверяются
имена полей режима работы, рейтинга, атрибутов и манёвров (разделы 4 и 7).
"""
from __future__ import annotations

import asyncio
import json
import os
import sys
from pathlib import Path

import httpx
from dotenv import load_dotenv

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
load_dotenv(ROOT / ".env.local")  # локальные ключи; .env — запасной вариант
load_dotenv(ROOT / ".env")

from app.config import DEFAULT_PLACE_FIELDS, Settings  # noqa: E402

DAY1 = ROOT / "var" / "day1"
POINT = (51.128, 71.430)  # Астана, левый берег


def save(name: str, data) -> None:
    DAY1.mkdir(parents=True, exist_ok=True)
    (DAY1 / f"{name}.json").write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")


async def check_2gis(http: httpx.AsyncClient) -> list[str]:
    key = os.environ.get("DGIS_API_KEY")
    if not key:
        return ["SKIP  2ГИС: DGIS_API_KEY не задан (сервер работает в демо-режиме)"]
    out = []
    r = await http.get("https://catalog.api.2gis.com/3.0/items", params={
        "q": "кофейня", "point": f"{POINT[1]},{POINT[0]}", "radius": 1500, "page_size": 5,
        "fields": os.environ.get("DGIS_PLACE_FIELDS", DEFAULT_PLACE_FIELDS), "locale": "ru_KZ", "key": key})
    data = r.json()
    save("places", data)
    meta = data.get("meta", {})
    items = (data.get("result") or {}).get("items") or []
    if meta.get("code") == 200 and items:
        first = items[0]
        out.append(f"OK    2ГИС Places: {len(items)} мест; поля первого: {sorted(first)}")
        out.append(f"      schedule: {'есть' if first.get('schedule') else 'нет'}; reviews: {sorted((first.get('reviews') or {}))}; "
                   f"attribute_groups: {len(first.get('attribute_groups') or [])}; org: {sorted(first.get('org') or {})}")
    else:
        out.append(f"FAIL  2ГИС Places: meta={meta}")
    r = await http.post("https://routing.api.2gis.com/get_dist_matrix", params={"key": key, "version": "2.0"}, json={
        "points": [{"lat": POINT[0], "lon": POINT[1]}, {"lat": 51.1324, "lon": 71.4036}],
        "sources": [0], "targets": [1], "transport": "walking"})
    save("matrix", r.json() if r.headers.get("content-type", "").startswith("application/json") else {"text": r.text})
    out.append(("OK    " if r.status_code == 200 else "FAIL  ") + f"2ГИС Distance Matrix: HTTP {r.status_code}")
    r = await http.post("https://routing.api.2gis.com/routing/7.0.0/global", params={"key": key}, json={
        "points": [{"type": "stop", "lat": POINT[0], "lon": POINT[1]}, {"type": "stop", "lat": 51.1324, "lon": 71.4036}],
        "transport": "walking", "output": "detailed", "locale": "ru"})
    body = r.json() if r.headers.get("content-type", "").startswith("application/json") else {"text": r.text}
    save("routing", body)
    man = ((body.get("result") or [{}])[0].get("maneuvers") or []) if isinstance(body, dict) else []
    out.append(("OK    " if r.status_code == 200 and man else "FAIL  ") + f"2ГИС Routing: HTTP {r.status_code}, манёвров {len(man)}")
    for svc, url, params in (
        ("Suggest", "https://catalog.api.2gis.com/3.0/suggests", {"q": "Хан Ш", "location": f"{POINT[1]},{POINT[0]}"}),
        ("Geocoder", "https://catalog.api.2gis.com/3.0/items/geocode", {"q": "Астана, Туран 37"}),
    ):
        r = await http.get(url, params={**params, "key": key, "fields": "items.point"})
        d = r.json()
        save(svc.lower(), d)
        out.append(("OK    " if (d.get("meta") or {}).get("code") == 200 else "FAIL  ") + f"2ГИС {svc}: meta={d.get('meta', {}).get('code')}")
    return out


async def check_jev(http: httpx.AsyncClient) -> list[str]:
    s = Settings.from_env()  # та же логика выбора, что у сервера: TypeSafe напрямую или OpenRouter
    if not s.has_jev:
        return ["SKIP  Jev: нет JEV_API_KEY и OPENROUTER_API_KEY (фильтры — классификатор по ключевым словам)"]
    base, model = s.jev_base_url, s.jev_model
    headers = {"Authorization": f"Bearer {s.jev_api_key}"}
    out = [f"INFO  Jev через {s.jev_via}: {base}, модель {model}"]
    if s.jev_via == "typesafe":  # у OpenRouter свой формат списка моделей — его не сверяем
        r = await http.get(f"{base}/v1/models", headers=headers)
        if r.status_code == 200:
            names = [m.get("id") or m.get("name") for m in (r.json().get("data") or r.json().get("models") or [])]
            out.append(f"OK    Jev models: {names}")
            if names and model not in names:
                out.append(f"WARN  JEV_MODEL={model} нет в списке — проверьте версию")
        else:
            out.append(f"FAIL  Jev models: HTTP {r.status_code}")
    r = await http.post(f"{base}/v1/systemone", headers=headers, json={
        "model": model, "state": {"query": "хочу кофе рядом"},
        "questions": {"coffee": {"type": "noul", "instructions": "The user wants coffee."}}})
    if r.status_code == 200:
        out.append(f"OK    Jev systemone: {r.json().get('answers')}, usage={r.json().get('usage')}")
    else:
        out.append(f"FAIL  Jev systemone: HTTP {r.status_code} {r.text[:200]}")
    return out


async def check_claude() -> list[str]:
    s = Settings.from_env()
    if not s.has_claude:
        return ["SKIP  Claude: ANTHROPIC_API_KEY не задан (пояснения — по шаблонам)"]
    import anthropic

    client = anthropic.AsyncAnthropic(api_key=s.anthropic_api_key, base_url=s.anthropic_base_url)
    out = []
    for model in {os.environ.get("ANSWER_MODEL", "claude-haiku-5-5"), os.environ.get("PLANNER_MODEL", "claude-sonnet-5-5")}:
        try:
            m = await client.models.retrieve(model)
            out.append(f"OK    Claude model {m.id}")
        except anthropic.APIStatusError as exc:
            out.append(f"FAIL  Claude model {model}: HTTP {exc.status_code}")
    try:
        resp = await client.messages.create(model="claude-haiku-5-5", max_tokens=200,
                                            output_config={"effort": "low"},
                                            messages=[{"role": "user", "content": "Ответь одним словом: столица Казахстана?"}])
        text = next((b.text for b in resp.content if b.type == "text"), "")
        out.append(f"OK    Claude Haiku 5.5 отвечает: {text.strip()[:40]!r}")
    except anthropic.APIStatusError as exc:
        out.append(f"FAIL  Claude messages: HTTP {exc.status_code}")
    return out


async def check_weather(http: httpx.AsyncClient) -> list[str]:
    out = []
    key = os.environ.get("OPENWEATHER_API_KEY")
    if key:
        r = await http.get("https://api.openweathermap.org/data/3.0/onecall",
                           params={"lat": POINT[0], "lon": POINT[1], "appid": key, "units": "metric", "exclude": "alerts,daily"})
        if r.status_code == 200:
            out.append(f"OK    OpenWeather One Call 3.0: minutely={len(r.json().get('minutely') or [])}")
        else:
            out.append(f"FAIL  OpenWeather: HTTP {r.status_code} (активация ключа может занять до часа)")
    else:
        out.append("SKIP  OpenWeather: OPENWEATHER_API_KEY не задан (только почасовой прогноз Open-Meteo)")
    r = await http.get("https://api.open-meteo.com/v1/forecast",
                       params={"latitude": POINT[0], "longitude": POINT[1], "hourly": "precipitation_probability"})
    out.append(("OK    " if r.status_code == 200 else "FAIL  ") + f"Open-Meteo: HTTP {r.status_code}")
    return out


async def main() -> int:
    async with httpx.AsyncClient(timeout=15.0) as http:
        results = await asyncio.gather(check_2gis(http), check_jev(http), check_claude(), check_weather(http),
                                       return_exceptions=True)
    failed = False
    for res in results:
        if isinstance(res, Exception):
            print(f"FAIL  {type(res).__name__}: {res}")
            failed = True
            continue
        for line in res:
            print(line)
            failed |= line.startswith("FAIL")
    if DAY1.exists():
        print(f"\nОбразцы ответов 2ГИС для сверки форматов: {DAY1}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
