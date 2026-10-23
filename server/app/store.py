"""Кэш ответов 2ГИС: «загрузилось один раз — используем дальше».

Два уровня: память процесса и (если включено DGIS_CACHE=firestore) Firestore — общий для всех экземпляров
функции и переживает холодные старты. Хранится сырой ответ 2ГИС, разбор — каждый раз заново.
Свежий ответ берётся из кэша в пределах срока; если 2ГИС отвечает ошибкой или лимитом — отдаём последнюю
сохранённую копию любой давности (stale-if-error). Одинаковые одновременные запросы склеиваются в один.
"""
from __future__ import annotations

import asyncio
import base64
import hashlib
import json
import logging
import os
import time
import zlib
from collections import Counter, OrderedDict
from typing import Any, Awaitable, Callable

log = logging.getLogger(__name__)


class FirestoreBackend:
    """Документы {at, data} в коллекции dgis_cache; данные сжаты (лимит документа — 1 МБ)."""

    def __init__(self, collection: str = "dgis_cache"):
        from google.cloud import firestore  # импорт только если кэш в Firestore включён

        self._col = firestore.AsyncClient().collection(collection)

    async def get(self, key: str) -> tuple[float, Any] | None:
        snap = await self._col.document(key).get()
        if not snap.exists:
            return None
        d = snap.to_dict() or {}
        try:
            return float(d["at"]), json.loads(zlib.decompress(base64.b64decode(d["data"])))
        except (KeyError, ValueError, zlib.error):
            return None

    async def set(self, key: str, at: float, value: Any) -> None:
        packed = base64.b64encode(zlib.compress(json.dumps(value, ensure_ascii=False).encode("utf-8"), 6)).decode("ascii")
        if len(packed) < 900_000:
            await self._col.document(key).set({"at": at, "data": packed})


class ResponseStore:
    def __init__(self, max_items: int = 3000, backend: Any | None = None):
        self._mem: OrderedDict[str, tuple[float, Any]] = OrderedDict()
        self._max = max_items
        self._backend = backend
        self._inflight: dict[str, asyncio.Future] = {}
        self.stats: Counter[str] = Counter()

    @staticmethod
    def make_key(kind: str, parts: Any) -> str:
        raw = json.dumps(parts, ensure_ascii=False, sort_keys=True, default=str)
        return f"{kind}-{hashlib.sha1(raw.encode('utf-8')).hexdigest()}"

    def _remember(self, key: str, at: float, value: Any) -> None:
        self._mem[key] = (at, value)
        self._mem.move_to_end(key)
        while len(self._mem) > self._max:
            self._mem.popitem(last=False)

    async def fetch(self, kind: str, parts: Any, ttl_s: float, loader: Callable[[], Awaitable[Any]],
                    retry_on: tuple[type[BaseException], ...] = (Exception,)) -> Any:
        key = self.make_key(kind, parts)
        now = time.time()
        stale = self._mem.get(key)
        if stale and now - stale[0] < ttl_s:
            self.stats[f"{kind}:memory"] += 1
            self._mem.move_to_end(key)
            return stale[1]
        if self._backend is not None:
            try:
                doc = await self._backend.get(key)
            except Exception as exc:  # кэш не должен ломать запрос
                log.warning("cache backend get failed: %s", type(exc).__name__)
                doc = None
            if doc is not None:
                if now - doc[0] < ttl_s:
                    self.stats[f"{kind}:shared"] += 1
                    self._remember(key, *doc)
                    return doc[1]
                stale = stale if stale and stale[0] >= doc[0] else doc
        if key in self._inflight:
            self.stats[f"{kind}:joined"] += 1
            return await asyncio.shield(self._inflight[key])
        fut: asyncio.Future = asyncio.get_running_loop().create_future()
        self._inflight[key] = fut
        try:
            value = await loader()
        except retry_on as exc:
            if stale is not None:
                self.stats[f"{kind}:stale"] += 1
                log.warning("2gis %s failed (%s) — served cached copy from %.0f min ago", kind, str(exc)[:80], (now - stale[0]) / 60)
                fut.set_result(stale[1])
                return stale[1]
            fut.set_exception(exc)
            fut.exception()  # исключение уже передано вызывающему; помечаем как полученное
            raise
        finally:
            self._inflight.pop(key, None)
        self.stats[f"{kind}:network"] += 1
        self._remember(key, now, value)
        if not fut.done():
            fut.set_result(value)
        if self._backend is not None:
            asyncio.create_task(self._persist(key, now, value))
        return value

    async def _persist(self, key: str, at: float, value: Any) -> None:
        try:
            await self._backend.set(key, at, value)
        except Exception as exc:
            log.warning("cache backend set failed: %s", type(exc).__name__)


def build_store() -> ResponseStore:
    backend = None
    if os.getenv("DGIS_CACHE", "").lower() == "firestore":
        try:
            backend = FirestoreBackend()
            log.info("2gis cache: memory + firestore")
        except Exception as exc:
            log.warning("firestore cache unavailable (%s) — memory only", type(exc).__name__)
    return ResponseStore(backend=backend)


STORE = build_store()
