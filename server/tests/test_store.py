"""Кэш ответов 2ГИС: один раз загрузили — дальше без запросов; при лимите — сохранённая копия."""
import asyncio
import time

import pytest

from app.dgis import DgisError
from app.store import ResponseStore


async def test_second_request_comes_from_cache():
    store, calls = ResponseStore(), []

    async def load():
        calls.append(1)
        return {"n": len(calls)}

    a = await store.fetch("places", ["кофе", 1], 60, load, retry_on=(DgisError,))
    b = await store.fetch("places", ["кофе", 1], 60, load, retry_on=(DgisError,))
    assert a == b == {"n": 1} and len(calls) == 1 and store.stats["places:memory"] == 1


async def test_stale_copy_when_2gis_is_over_limit(monkeypatch):
    store = ResponseStore()
    await store.fetch("routing", ["A", "B"], 10, lambda: asyncio.sleep(0, {"route": 1}), retry_on=(DgisError,))
    monkeypatch.setattr(time, "time", lambda real=time.time: real() + 3600)  # срок вышел

    async def over_limit():
        raise DgisError("routing: http 429 too many requests")

    assert await store.fetch("routing", ["A", "B"], 10, over_limit, retry_on=(DgisError,)) == {"route": 1}
    assert store.stats["routing:stale"] == 1


async def test_error_without_copy_is_raised_and_not_cached():
    store = ResponseStore()

    async def fail():
        raise DgisError("places: http 403")

    with pytest.raises(DgisError):
        await store.fetch("places", ["x"], 60, fail, retry_on=(DgisError,))
    assert await store.fetch("places", ["x"], 60, lambda: asyncio.sleep(0, {"ok": True}), retry_on=(DgisError,)) == {"ok": True}


async def test_concurrent_identical_requests_share_one_call():
    store, calls = ResponseStore(), []

    async def slow():
        calls.append(1)
        await asyncio.sleep(0.05)
        return {"v": 1}

    res = await asyncio.gather(*[store.fetch("matrix", ["same"], 60, slow, retry_on=(DgisError,)) for _ in range(5)])
    assert all(r == {"v": 1} for r in res) and len(calls) == 1 and store.stats["matrix:joined"] == 4
