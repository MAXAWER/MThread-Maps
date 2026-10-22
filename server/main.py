"""Точка входа Cloud Functions for Firebase (2-е поколение): то же FastAPI-приложение за WSGI-переходником.

Функции 2-го поколения работают на Cloud Run, поэтому потоковые ответы поиска (SSE) доходят до клиента по мере
готовности. Ключи внешних сервисов — секреты Secret Manager, функция получает их как переменные окружения.
Локально сервер по-прежнему запускается через uvicorn (README).
"""
import asyncio
import threading

from a2wsgi import ASGIMiddleware
from firebase_functions import https_fn, options

SECRETS = ["DGIS_API_KEY", "OPENROUTER_API_KEY", "ANTHROPIC_API_KEY"]

_lock = threading.Lock()
_wsgi: ASGIMiddleware | None = None


def _wsgi_app() -> ASGIMiddleware:
    """Приложение импортируется и поднимается при первом запросе: Firebase CLI при деплое ждёт загрузки этого
    файла не дольше 10 с. Сервисы живут в своём цикле событий — к нему привязан httpx-клиент."""
    global _wsgi
    with _lock:
        if _wsgi is None:
            from app.main import app, start_services


            loop = asyncio.new_event_loop()
            threading.Thread(target=loop.run_forever, daemon=True, name="asgi-loop").start()
            asyncio.run_coroutine_threadsafe(start_services(app), loop).result()
            _wsgi = ASGIMiddleware(app, loop=loop)
    return _wsgi


@https_fn.on_request(
    region="europe-west1",
    memory=options.MemoryOption.MB_512,
    cpu=1,
    concurrency=40,
    max_instances=5,
    timeout_sec=120,
    secrets=SECRETS,
    invoker="public",
)
def api(req: https_fn.Request) -> https_fn.Response:
    return https_fn.Response.from_app(_wsgi_app(), req.environ)
