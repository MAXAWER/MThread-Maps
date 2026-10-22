"""Калибровка Jev на размеченном наборе (раздел 15): точность полей и подбор порога choice.

Запуск:  python scripts/jev_calibration.py [--set eval/jev_labeled.jsonl]
Цель — не меньше 85% верных значений по intent, category_primary, transport, open_requirement.
Порог choice (стартовый 0,55) подбирается так, чтобы ниже него ответы чаще были неверны, чем верны.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import os
import sys
from datetime import datetime
from pathlib import Path

import httpx
from dotenv import load_dotenv

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
load_dotenv(ROOT / ".env")

from app.catalog import load_catalog  # noqa: E402
from app.jev import CHOICE_DEFAULTS, JevClient, build_questions, jev_state  # noqa: E402
from app.models import ASTANA_TZ  # noqa: E402

FIELDS = ["intent", "category_primary", "transport", "open_requirement"]
THRESHOLDS = [0.35, 0.45, 0.55, 0.65, 0.75]


async def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--set", default=str(ROOT / "eval" / "jev_labeled.jsonl"))
    ap.add_argument("--concurrency", type=int, default=4)
    args = ap.parse_args()
    key = os.environ.get("JEV_API_KEY")
    if not key:
        print("Нужен JEV_API_KEY")
        return 2
    rows = [json.loads(line) for line in Path(args.set).read_text(encoding="utf-8").splitlines() if line.strip()]
    catalog = load_catalog()
    questions = {k: v for k, v in build_questions(catalog).items() if k in FIELDS}
    sem = asyncio.Semaphore(args.concurrency)
    now = datetime(2026, 10, 22, 15, 0, tzinfo=ASTANA_TZ)
    async with httpx.AsyncClient() as http:
        client = JevClient(http, os.environ.get("JEV_BASE_URL", "https://api.typesafe.ai"), key,
                           os.environ.get("JEV_MODEL", "jev-1.13.0"), timeout_s=10.0)

        async def ask(row):
            async with sem:
                state = jev_state(row["query"], now, None, False, None, "", False)
                return await client.ask(state, questions)

        answers = await asyncio.gather(*[ask(r) for r in rows], return_exceptions=True)
    per_field = {f: {"ok": 0, "n": 0} for f in FIELDS}
    by_lang: dict[str, list[int]] = {}
    conf_points = []  # (confidence, correct)
    for row, ans in zip(rows, answers):
        if isinstance(ans, Exception):
            print(f"ERR  {row['query'][:40]}: {ans}")
            continue
        for f in FIELDS:
            gold = row["labels"].get(f)
            if gold is None:
                continue
            a = ans.get(f) or {}
            pred, conf = a.get("choice"), float(a.get("confidence") or 0)
            correct = pred == gold
            per_field[f]["n"] += 1
            per_field[f]["ok"] += correct
            by_lang.setdefault(row.get("lang", "ru"), []).append(int(correct))
            if gold != CHOICE_DEFAULTS.get(f):
                conf_points.append((conf, correct))
            if not correct:
                print(f"MISS {f:17} {row['query'][:50]!r}: {pred} ({conf:.2f}) ≠ {gold}")
    print("\nТочность по полям (цель ≥ 85%):")
    for f, s in per_field.items():
        pct = 100 * s["ok"] / s["n"] if s["n"] else 0
        print(f"  {f:18} {pct:5.1f}%  ({s['ok']}/{s['n']})")
    print("\nПо языкам:")
    for lang, xs in by_lang.items():
        print(f"  {lang}: {100 * sum(xs) / len(xs):5.1f}% ({len(xs)} ответов)")
    print("\nПорог choice: доля верных среди принятых и доля отброшенных")
    for t in THRESHOLDS:
        kept = [c for conf, c in conf_points if conf >= t]
        dropped = len(conf_points) - len(kept)
        acc = 100 * sum(kept) / len(kept) if kept else 0
        print(f"  {t:.2f}: верных {acc:5.1f}%, отброшено {dropped}/{len(conf_points)}")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
