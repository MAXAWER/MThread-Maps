"""Сборка сервисов: каждый подключается своим ключом."""
import httpx

from app.config import Settings
from app.dgis import DgisClient
from app.providers.fake_jev import FakeJev
from app.providers.fake_llm import TemplateLLM
from app.services import build_services


def test_jev_via_openrouter_ignores_typesafe_settings(monkeypatch):
    """Только ключ OpenRouter: Jev идёт на openrouter.ai с моделью typesafe/, даже если в .env остались адреса TypeSafe."""
    monkeypatch.setenv("JEV_API_KEY", "")
    monkeypatch.setenv("OPENROUTER_API_KEY", "or-test")
    monkeypatch.setenv("JEV_BASE_URL", "https://api.typesafe.ai")
    monkeypatch.setenv("JEV_MODEL", "jev-1.13.0")
    s = Settings.from_env()
    assert s.has_jev and s.jev_via == "openrouter"
    assert s.jev_base_url == "https://openrouter.ai/api" and s.jev_model == "typesafe/jev-1.13"


def test_direct_typesafe_key_wins_over_openrouter(monkeypatch):
    monkeypatch.setenv("JEV_API_KEY", "ts-test")
    monkeypatch.setenv("OPENROUTER_API_KEY", "or-test")
    monkeypatch.delenv("JEV_BASE_URL", raising=False)
    monkeypatch.delenv("JEV_MODEL", raising=False)
    s = Settings.from_env()
    assert s.jev_via == "typesafe" and s.jev_api_key == "ts-test" and s.jev_base_url == "https://api.typesafe.ai"


def test_claude_url_does_not_follow_process_env(monkeypatch):
    """ANTHROPIC_BASE_URL в окружении (например, от инструментов разработчика) не уводит запросы Claude."""
    monkeypatch.setenv("ANTHROPIC_BASE_URL", "http://127.0.0.1:9")
    monkeypatch.delenv("CLAUDE_API_URL", raising=False)
    assert Settings.from_env().anthropic_base_url == "https://api.anthropic.com"


async def test_matrix_splits_into_demo_key_blocks():
    """Демо-ключ 2ГИС принимает матрицу не больше 10 × 10: 12 источников × 23 цели — несколько запросов, ответ целиком."""
    from app.catalog import load_catalog
    from app.dgis import UsageCounters

    bodies = []

    async def fake_post(service, url, body, params=None):
        bodies.append(body)
        return {"routes": [{"source_id": s, "target_id": t, "duration": 60, "distance": 100, "status": "OK"}
                           for s in body["sources"] for t in body["targets"]]}

    async with httpx.AsyncClient() as http:
        client = DgisClient(http, Settings(dgis_key="k"), load_catalog(), UsageCounters(1000))
        client._post = fake_post
        points = [(51.1 + i / 1000, 71.4) for i in range(35)]
        res = await client.matrix(points, list(range(12)), list(range(12, 35)), "walking")
    assert all(len(b["sources"]) <= 10 and len(b["targets"]) <= 10 for b in bodies)
    assert len(res) == 12 * 23


async def test_dgis_key_without_ai_keys_uses_fallbacks():
    """Ключ 2ГИС без ключей Jev и Claude: места живые, фильтры по ключевым словам, пояснения по шаблонам."""
    async with httpx.AsyncClient() as http:
        svc = build_services(Settings(demo_mode=False, dgis_key="test"), http)
    assert isinstance(svc.places, DgisClient) and svc.data_source == "2gis"
    assert isinstance(svc.jev, FakeJev) and isinstance(svc.llm, TemplateLLM)
