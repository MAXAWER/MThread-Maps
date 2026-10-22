"""Вызовы Claude: Haiku 5.5 — ответы, портрет, тексты; Sonnet 5.5 — режим плана.

Модель каждой роли задаётся конфигом. Ответы строго по JSON-схеме (output_config.format);
длины строк и число элементов дополнительно проверяет код.
"""
from __future__ import annotations

import asyncio
import json
import logging
from typing import Awaitable, Callable

import anthropic

from . import prompts
from .catalog import Catalog
from .config import Settings

log = logging.getLogger(__name__)

ToolExecutor = Callable[[str, dict], Awaitable[dict]]


def profile_keys(catalog: Catalog) -> list[str]:
    return [f"category.{cid}" for cid in catalog.categories] + prompts.PROFILE_BASE_KEYS


def profile_schema(catalog: Catalog) -> dict:
    return {
        "type": "object",
        "additionalProperties": False,
        "required": ["ops"],
        "properties": {
            "ops": {
                "type": "array",
                "items": {
                    "type": "object",
                    "additionalProperties": False,
                    "required": ["op", "key", "value", "explicit", "evidence"],
                    "properties": {
                        "op": {"type": "string", "enum": ["add", "reinforce", "weaken", "remove"]},
                        "key": {"type": "string", "enum": profile_keys(catalog)},
                        "value": {"type": "string"},
                        "explicit": {"type": "boolean"},
                        "evidence": {"type": "string"},
                    },
                },
            }
        },
    }


class ClaudeService:
    """Реальные вызовы Claude API. При любой ошибке методы возвращают None — конвейер уходит в запасной путь."""

    name = "claude"

    def __init__(self, settings: Settings, catalog: Catalog):
        self._s = settings
        self._catalog = catalog
        self.client = anthropic.AsyncAnthropic(api_key=settings.anthropic_api_key, base_url=settings.anthropic_base_url,
                                               max_retries=1)
        self._fallback_ok = settings.planner_fallback
        self._profile_schema = profile_schema(catalog)

    async def _json_call(self, *, model: str, system: str, user: str, schema: dict, effort: str,
                         max_tokens: int, timeout: float, tag: str) -> dict | None:
        client = self.client.with_options(timeout=timeout, max_retries=0)
        try:
            resp = await asyncio.wait_for(
                client.messages.create(
                    model=model,
                    max_tokens=max_tokens,
                    system=[{"type": "text", "text": system, "cache_control": {"type": "ephemeral"}}],
                    messages=[{"role": "user", "content": user}],
                    output_config={"effort": effort, "format": {"type": "json_schema", "schema": schema}},
                ),
                timeout=timeout + 0.5,
            )
        except (asyncio.TimeoutError, anthropic.APITimeoutError):
            log.warning("claude %s: timeout", tag)
            return None
        except anthropic.RateLimitError:
            log.warning("claude %s: rate limited", tag)
            return None
        except anthropic.BadRequestError as exc:
            log.error("claude %s: bad request: %s", tag, exc.message)
            return None
        except anthropic.APIStatusError as exc:
            log.warning("claude %s: http %s", tag, exc.status_code)
            return None
        except anthropic.APIConnectionError:
            log.warning("claude %s: connection error", tag)
            return None
        log.info("claude %s: model=%s in=%s out=%s stop=%s", tag, resp.model, resp.usage.input_tokens,
                 resp.usage.output_tokens, resp.stop_reason)
        if resp.stop_reason in ("refusal", "max_tokens"):
            return None
        text = next((b.text for b in resp.content if b.type == "text"), None)
        if not text:
            return None
        try:
            return json.loads(text)
        except json.JSONDecodeError:
            log.warning("claude %s: invalid json", tag)
            return None

    async def answer(self, payload: dict) -> dict | None:
        return await self._json_call(
            model=self._s.answer_model, system=prompts.ANSWER_SYSTEM,
            user=json.dumps(payload, ensure_ascii=False), schema=prompts.ANSWER_SCHEMA,
            # effort medium: модель взвешивает кандидатов по параметрам; мышление входит в max_tokens.
            effort=self._s.answer_effort, max_tokens=12000, timeout=self._s.answer_timeout_s, tag="answer",
        )

    async def profile_ops(self, payload: dict) -> list[dict] | None:
        data = await self._json_call(
            model=self._s.profile_model, system=prompts.PROFILE_SYSTEM,
            user=json.dumps(payload, ensure_ascii=False), schema=self._profile_schema,
            effort="low", max_tokens=3000, timeout=15.0, tag="profile",
        )
        return None if data is None else list(data.get("ops") or [])

    async def deck_texts(self, payload: dict) -> dict[str, str] | None:
        data = await self._json_call(
            model=self._s.text_model, system=prompts.DECK_SYSTEM,
            user=json.dumps(payload, ensure_ascii=False), schema=prompts.DECK_SCHEMA,
            effort="low", max_tokens=6000, timeout=25.0, tag="deck",
        )
        if data is None:
            return None
        return {t["slot"]: t["why"] for t in data.get("texts") or [] if t.get("slot") and t.get("why")}

    async def notify_text(self, payload: dict) -> str | None:
        data = await self._json_call(
            model=self._s.text_model, system=prompts.NOTIFY_SYSTEM,
            user=json.dumps(payload, ensure_ascii=False), schema=prompts.NOTIFY_SCHEMA,
            effort="low", max_tokens=1500, timeout=6.0, tag="notify",
        )
        return None if data is None else (data.get("text") or None)

    async def _sonnet(self, **kwargs):
        """Sonnet 5.5 с серверным fallback (Claude API). Если организация не принимает fallbacks — без него."""
        if self._fallback_ok:
            try:
                return await self.client.beta.messages.create(
                    betas=["server-side-fallback-2026-07-01"], fallbacks="default", **kwargs)
            except anthropic.BadRequestError as exc:
                if "fallback" not in (exc.message or "").lower():
                    raise
                log.warning("server-side fallback rejected; continuing without it")
                self._fallback_ok = False
        return await self.client.messages.create(**kwargs)

    async def plan_explain(self, payload: dict) -> str | None:
        try:
            resp = await asyncio.wait_for(self._sonnet(
                model=self._s.planner_model,
                max_tokens=4000,
                system=[{"type": "text", "text": prompts.PLAN_EXPLAIN_SYSTEM, "cache_control": {"type": "ephemeral"}}],
                messages=[{"role": "user", "content": json.dumps(payload, ensure_ascii=False)}],
                output_config={"effort": "low", "format": {"type": "json_schema", "schema": prompts.PLAN_EXPLAIN_SCHEMA}},
            ), timeout=self._s.planner_timeout_s)
        except (asyncio.TimeoutError, anthropic.APIError) as exc:
            log.warning("claude plan_explain failed: %s", type(exc).__name__)
            return None
        if resp.stop_reason in ("refusal", "max_tokens"):
            return None
        text = next((b.text for b in resp.content if b.type == "text"), None)
        try:
            return json.loads(text).get("explanation") if text else None
        except json.JSONDecodeError:
            return None

    async def agent_loop(self, user_payload: dict, execute: ToolExecutor, on_turn=None) -> dict | None:
        """Агент Sonnet 5.5 с инструментами 2ГИС. Возвращает вход submit_answer или None.

        Адаптивное мышление включено у модели по умолчанию, глубину задаёт effort. tool_choice — только auto
        (принудительный выбор инструмента Sonnet 5.5 отклоняет). В историю добавляется весь content ответа.
        """
        messages: list[dict] = [{"role": "user", "content": json.dumps(user_payload, ensure_ascii=False)}]
        system = [{"type": "text", "text": prompts.AGENT_SYSTEM, "cache_control": {"type": "ephemeral"}}]
        max_calls = self._s.agent_max_tool_calls
        calls = 0
        nudged = False

        async def run() -> dict | None:
            nonlocal calls, nudged
            for _ in range(max_calls + 4):
                if on_turn:
                    await on_turn(calls)
                resp = await self._sonnet(
                    model=self._s.agent_model,
                    max_tokens=16000,
                    system=system,
                    tools=prompts.AGENT_TOOLS,
                    tool_choice={"type": "auto"},
                    messages=messages,
                    output_config={"effort": self._s.agent_effort},
                )
                log.info("agent turn: stop=%s in=%s out=%s", resp.stop_reason, resp.usage.input_tokens,
                         resp.usage.output_tokens)
                if resp.stop_reason == "refusal":
                    return None
                uses = [b for b in resp.content if b.type == "tool_use"]
                submit = next((u for u in uses if u.name == "submit_answer"), None)
                if submit is not None:
                    return dict(submit.input)
                messages.append({"role": "assistant", "content": resp.content})
                if not uses:
                    if nudged:
                        return None
                    nudged = True
                    messages.append({"role": "user", "content": "Заверши ответ вызовом submit_answer."})
                    continue
                results = []
                for use in uses:
                    calls += 1
                    if calls > max_calls:
                        out, is_error = {"error": "Лимит вызовов исчерпан. Вызови submit_answer по найденному."}, True
                    else:
                        try:
                            out, is_error = await execute(use.name, dict(use.input)), False
                        except Exception as exc:  # инструмент не должен ронять цикл
                            log.warning("agent tool %s failed: %s", use.name, exc)
                            out, is_error = {"error": f"Инструмент недоступен: {type(exc).__name__}"}, True
                    results.append({"type": "tool_result", "tool_use_id": use.id,
                                    "content": json.dumps(out, ensure_ascii=False), "is_error": is_error})
                messages.append({"role": "user", "content": results})
            return None

        try:
            return await asyncio.wait_for(run(), timeout=self._s.agent_timeout_s)
        except (asyncio.TimeoutError, anthropic.APIError) as exc:
            log.warning("agent loop failed: %s", exc)
            return None

    async def plan_tool_loop(self, user_payload: dict, tools: list[dict], execute: ToolExecutor,
                             max_calls: int = 6) -> str | None:
        """Цикл инструментов Sonnet: tool_choice auto, strict-схемы, не больше max_calls вызовов."""
        messages: list[dict] = [{"role": "user", "content": json.dumps(user_payload, ensure_ascii=False)}]
        calls = 0
        system = [{"type": "text", "text": prompts.PLANNER_SYSTEM, "cache_control": {"type": "ephemeral"}}]

        async def run() -> str | None:
            nonlocal calls
            for _ in range(max_calls + 2):
                resp = await self._sonnet(
                    model=self._s.planner_model,
                    max_tokens=16000,
                    system=system,
                    tools=tools,
                    tool_choice={"type": "auto"} if calls < max_calls else {"type": "none"},
                    messages=messages,
                    output_config={"effort": self._s.planner_effort},
                )
                if resp.stop_reason == "refusal":
                    return None
                uses = [b for b in resp.content if b.type == "tool_use"]
                if not uses:
                    return next((b.text for b in resp.content if b.type == "text"), None)
                messages.append({"role": "assistant", "content": resp.content})
                results = []
                for use in uses:
                    calls += 1
                    if calls > max_calls:
                        out, is_error = {"error": "Лимит вызовов инструментов исчерпан. Ответь по найденному."}, True
                    else:
                        try:
                            out, is_error = await execute(use.name, dict(use.input)), False
                        except Exception as exc:  # инструмент не должен ронять цикл
                            log.warning("planner tool %s failed: %s", use.name, type(exc).__name__)
                            out, is_error = {"error": f"Инструмент недоступен: {type(exc).__name__}"}, True
                    results.append({"type": "tool_result", "tool_use_id": use.id,
                                    "content": json.dumps(out, ensure_ascii=False), "is_error": is_error})
                messages.append({"role": "user", "content": results})
            return None

        try:
            return await asyncio.wait_for(run(), timeout=self._s.planner_timeout_s)
        except (asyncio.TimeoutError, anthropic.APIError) as exc:
            log.warning("planner loop failed: %s", type(exc).__name__)
            return None
