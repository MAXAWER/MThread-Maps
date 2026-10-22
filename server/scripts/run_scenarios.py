"""Прогон сценариев раздела 15 против работающего сервера: инварианты и задержки (раздел 14).

Запуск:  python scripts/run_scenarios.py --base http://localhost:8080
Сценарии 11–14 (прогулка, уведомление, портрет, сход с маршрута) проверяются на телефоне.
"""
from __future__ import annotations

import argparse
import json
import statistics
import sys
import time
import uuid
from pathlib import Path

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from app.invariants import check_invariants  # noqa: E402

MSW = (51.0890, 71.4086)
KSH = (51.1324, 71.4036)
NQZ = (51.0222, 71.4669)
EXPO = (51.0889, 71.4158)

SCENARIOS = [
    (1, "Я возле Mega Silk Way, хочу прогуляться 30–60 минут", MSW, "2026-10-22T14:00:00+05:00", "walking"),
    (2, "Тихое место с кофе и розеткой недалеко", EXPO, "2026-10-22T15:10:00+05:00", "walking"),
    (3, "Еду с ребёнком 5 лет — площадки или музей с интерактивом", MSW, "2026-10-24T11:00:00+05:00", "driving"),
    (4, "Я турист, у меня 2 часа до вылета", NQZ, "2026-10-22T13:00:00+05:00", "walking"),
    (5, "Необычная ночная активность", KSH, "2026-10-23T23:30:00+05:00", "walking"),
    (6, "Нет зонта, через 30 минут дождь, хочу в кофейню", NQZ, "2026-10-22T15:00:00+05:00", "walking"),
    (7, "Хан Шатыр", MSW, "2026-10-22T12:00:00+05:00", "walking"),
    (8, "Поесть халяль недорого", KSH, "2026-10-22T13:00:00+05:00", "walking"),
    (9, "Жақын жерде дәмді кофе қайда?", KSH, "2026-10-22T10:00:00+05:00", "walking"),
    (10, "Каток с прокатом в 3 ночи", MSW, "2026-10-22T03:00:00+05:00", "walking"),
]


def run_one(client: httpx.Client, base: str, query: str, point, when: str, transport: str) -> dict:
    body = {"query": query, "lat": point[0], "lon": point[1], "local_time": when, "transport_hint": transport,
            "walk_active": True, "profile_summary": "", "lang": None}
    events: dict = {"order": [], "t": {}}
    t0 = time.perf_counter()
    with client.stream("POST", f"{base}/v1/search", json=body, headers={"X-Install-Id": str(uuid.uuid4())}) as r:
        if r.status_code != 200:
            return {"order": [], "error": {"code": r.status_code, "message": r.read().decode()[:200]}, "t": {}}
        name = None
        for line in r.iter_lines():
            if line.startswith("event: "):
                name = line[7:]
            elif line.startswith("data: ") and name:
                events[name] = json.loads(line[6:])
                events["order"].append(name)
                events["t"][name] = time.perf_counter() - t0
    return events


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8080")
    ap.add_argument("--repeat", type=int, default=1, help="повторы для замера медианы задержек")
    args = ap.parse_args()
    t_filters, t_answer_simple, t_answer_plan = [], [], []
    failed = 0
    with httpx.Client(timeout=60.0) as client:
        for n, query, point, when, transport in SCENARIOS:
            for _ in range(args.repeat):
                ev = run_one(client, args.base, query, point, when, transport)
                errs = check_invariants(ev, allow_estimate=False)
                ans = ev.get("answer") or {}
                if "filters" in ev["t"]:
                    t_filters.append(ev["t"]["filters"])
                if "answer" in ev["t"]:
                    (t_answer_plan if ans.get("mode") == "plan" else t_answer_simple).append(ev["t"]["answer"])
            status = "OK  " if not errs else "FAIL"
            failed += bool(errs)
            names = ", ".join(i["name"] for i in ans.get("items", [])[:3]) or ans.get("note") or ev.get("error")
            print(f"{status} #{n:<2} {ans.get('mode', '-'):6} {names}")
            for e in errs:
                print(f"       ! {e}")

    def stat(xs, p=0.5):
        if not xs:
            return "—"
        xs = sorted(xs)
        return f"{xs[min(len(xs) - 1, int(p * len(xs)))]:.2f} с"

    print("\nЗадержки (раздел 14):")
    print(f"  чипы фильтров, медиана:           {stat(t_filters)}  (цель ≤ 1,0 с)")
    print(f"  ответ простого режима, медиана:   {stat(t_answer_simple)}  (цель ≤ 4 с)")
    print(f"  ответ простого режима, 90%:       {stat(t_answer_simple, 0.9)}  (цель ≤ 7 с)")
    print(f"  ответ режима плана, 90%:          {stat(t_answer_plan, 0.9)}  (цель ≤ 12 с)")
    if t_answer_simple:
        print(f"  (выборка: {len(t_answer_simple)} ответов, среднее {statistics.mean(t_answer_simple):.2f} с)")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
