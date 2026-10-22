"""FastAPI-приложение: серверное API (раздел 12 ТЗ)."""
from __future__ import annotations

import logging
import time
import uuid
from contextlib import asynccontextmanager
from datetime import date, datetime
from pathlib import Path

import httpx
from dotenv import load_dotenv
from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, StreamingResponse

from .config import Settings
from .deck import build_deck
from .dgis import DgisError
from .geo import round_coord
from .models import ASTANA_TZ, DeckRequest, NotifyRequest, ProfileUpdateRequest, RouteOut, RouteRequest, SearchRequest
from .notify import notify_candidates
from .profile import update_profile
from .schedule import open_info
from .search import open_until_label, stream_search
from .services import Services, build_services

log = logging.getLogger("app")


class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str):
        self.status, self.code, self.message = status, code, message


class DailyLimiter:
    """Лимит поисков в сутки на установку (X-Install-Id)."""

    def __init__(self, limit: int):
        self.limit = limit
        self._day = date.today()
        self._counts: dict[str, int] = {}

    def check(self, install_id: str) -> bool:
        today = date.today()
        if today != self._day:
            self._day, self._counts = today, {}
        n = self._counts.get(install_id, 0)
        if n >= self.limit:
            return False
        self._counts[install_id] = n + 1
        return True


def _setup_logging(settings: Settings) -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    logging.getLogger("httpx").setLevel(logging.WARNING)
    logging.getLogger("httpcore").setLevel(logging.WARNING)


async def start_services(app: FastAPI) -> None:
    """Ключи — из окружения; локально ещё из server/.env.local и server/.env (в облако эти файлы не попадают)."""
    root = Path(__file__).resolve().parent.parent
    for name in (".env.local", ".env"):
        if (root / name).exists():
            load_dotenv(root / name)
    settings = Settings.from_env()
    _setup_logging(settings)
    app.state.http = httpx.AsyncClient(timeout=10.0, limits=httpx.Limits(max_connections=50, max_keepalive_connections=20))
    app.state.svc = build_services(settings, app.state.http)
    app.state.limiter = DailyLimiter(settings.daily_search_limit)
    if settings.demo_mode:
        log.warning("DEMO MODE: places are synthetic (no DGIS_API_KEY)")


async def stop_services(app: FastAPI) -> None:
    await app.state.http.aclose()


@asynccontextmanager
async def lifespan(app: FastAPI):
    await start_services(app)
    yield
    await stop_services(app)


app = FastAPI(title="Astana Walk Assistant API", version="0.1.0", lifespan=lifespan)


def get_svc(request: Request) -> Services:
    return request.app.state.svc


def install_id(x_install_id: str | None = Header(default=None)) -> str:
    """Случайный UUID на установку без персональных данных."""
    try:
        return str(uuid.UUID(x_install_id or ""))
    except ValueError:
        raise ApiError(400, "install_id_required", "Нужен заголовок X-Install-Id (UUID установки).")


@app.middleware("http")
async def request_log(request: Request, call_next):
    """Лог: id запроса, путь без параметров, статус, задержка. Без координат и текстов запросов."""
    rid = uuid.uuid4().hex[:12]
    t0 = time.perf_counter()
    response = await call_next(request)
    route = request.scope.get("route")
    path = getattr(route, "path", request.url.path)
    log.info("http %s %s %s %s %dms", rid, request.method, path, response.status_code,
             int((time.perf_counter() - t0) * 1000))
    response.headers["X-Request-Id"] = rid
    return response


@app.exception_handler(ApiError)
async def api_error_handler(_: Request, exc: ApiError):
    return JSONResponse(status_code=exc.status, content={"code": exc.code, "message": exc.message})


@app.exception_handler(HTTPException)
async def http_error_handler(_: Request, exc: HTTPException):
    return JSONResponse(status_code=exc.status_code, content={"code": "http_error", "message": str(exc.detail)})


@app.exception_handler(RequestValidationError)
async def validation_handler(_: Request, exc: RequestValidationError):
    fields = ", ".join(".".join(str(p) for p in e.get("loc", ())[1:]) for e in exc.errors()[:5])
    return JSONResponse(status_code=422, content={"code": "bad_request", "message": f"Неверные поля: {fields}"})


@app.exception_handler(DgisError)
async def dgis_error_handler(_: Request, exc: DgisError):
    log.warning("2gis error: %s", exc)
    return JSONResponse(status_code=502, content={"code": "places_unavailable",
                                                  "message": "Справочник 2ГИС сейчас недоступен. Попробуйте ещё раз."})


@app.get("/healthz")
async def healthz():
    return {"ok": True}


@app.get("/v1/status")
async def status(svc: Services = Depends(get_svc)):
    return {
        "settings": svc.settings.describe(),
        "data_source": svc.data_source,
        "dgis_usage": svc.counters.snapshot(),
        "weather_calls": getattr(svc.weather, "calls", {}),
    }


@app.post("/v1/search")
async def search(req: SearchRequest, request: Request, iid: str = Depends(install_id), svc: Services = Depends(get_svc)):
    if not request.app.state.limiter.check(iid):
        raise ApiError(429, "daily_limit", "Лимит поисков на сегодня исчерпан. Попробуйте завтра.")
    return StreamingResponse(stream_search(req, svc), media_type="text/event-stream",
                             headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


@app.get("/v1/suggest")
async def suggest(q: str = Query(min_length=1, max_length=120), lat: float = Query(ge=-90, le=90),
                  lon: float = Query(ge=-180, le=180), _: str = Depends(install_id), svc: Services = Depends(get_svc)):
    items = await svc.places.suggest(q, round_coord(lat), round_coord(lon))
    return {"items": items, "data_source": svc.data_source}


@app.get("/v1/places/{place_id}")
async def place_card(place_id: str, _: str = Depends(install_id), svc: Services = Depends(get_svc)):
    place = await svc.places.place_by_id(place_id)
    if place is None:
        raise ApiError(404, "not_found", "Место не найдено.")
    now = datetime.now(ASTANA_TZ)
    info = open_info(place.schedule, now)
    card = place.card()
    cat = svc.catalog.get(place.category)
    card.update({
        "category_name": cat.name_ru if cat else (place.rubrics[0] if place.rubrics else None),
        "open_state": info.state,
        "open_until": open_until_label(info),
        "opens_at": info.opens_at,
        "data_source": svc.data_source,
    })
    return card


def _fallback_route(points: list[tuple[float, float]], transport: str, svc: Services, note: str) -> dict:
    """Маршрут не построен: прямые отрезки и честная пометка."""
    from .geo import haversine_m

    speed = svc.settings.speeds_kmh.get(transport, 4.5) * 1000 / 3600
    dist = sum(haversine_m(a[0], a[1], b[0], b[1]) for a, b in zip(points, points[1:])) * 1.3
    return {
        "total_distance_m": int(dist), "total_duration_s": int(dist / speed),
        "geometry": [[lon, lat] for lat, lon in points],
        "maneuvers": [{"text": "Двигайтесь к цели", "lat": points[0][0], "lon": points[0][1],
                       "distance_m": int(dist), "duration_s": int(dist / speed)},
                      {"text": "Вы на месте", "lat": points[-1][0], "lon": points[-1][1]}],
        "source": "estimate", "note": note,
    }


@app.post("/v1/route", response_model=RouteOut)
async def route(req: RouteRequest, _: str = Depends(install_id), svc: Services = Depends(get_svc)):
    points = [(req.origin.lat, req.origin.lon)] + [(v.lat, v.lon) for v in req.via] + [(req.to.lat, req.to.lon)]
    transport = req.transport
    note = None
    try:
        data = await svc.places.route(points, transport)
    except DgisError as exc:
        log.warning("route failed (%s): %s", transport, exc)
        if transport == "public_transport":
            try:
                data = await svc.places.route(points, "walking")
                transport, note = "walking", "Маршрут на транспорте недоступен — показан пеший."
            except DgisError:
                data = _fallback_route(points, transport, svc, "Маршрут не построен: показана прямая линия.")
        else:
            data = _fallback_route(points, transport, svc, "Маршрут не построен: показана прямая линия.")
    return RouteOut(transport=transport, total_distance_m=data["total_distance_m"],
                    total_duration_s=data["total_duration_s"], geometry=data["geometry"],
                    maneuvers=data["maneuvers"], source=data.get("source", "routing"),
                    note=note or data.get("note"))


@app.post("/v1/deck")
async def deck(req: DeckRequest, _: str = Depends(install_id), svc: Services = Depends(get_svc)):
    return await build_deck(req, svc)


@app.post("/v1/profile/update")
async def profile_update(req: ProfileUpdateRequest, _: str = Depends(install_id), svc: Services = Depends(get_svc)):
    return await update_profile(req, svc)


@app.get("/v1/weather")
async def weather(lat: float = Query(ge=-90, le=90), lon: float = Query(ge=-180, le=180),
                  _: str = Depends(install_id), svc: Services = Depends(get_svc)):
    return await svc.weather.get(round_coord(lat), round_coord(lon), datetime.now(ASTANA_TZ))


@app.post("/v1/notify/candidates")
async def notify(req: NotifyRequest, _: str = Depends(install_id), svc: Services = Depends(get_svc)):
    return await notify_candidates(req, svc)
