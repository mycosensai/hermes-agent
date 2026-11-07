"""In-process bridge for the Hermes Android app.

Configures HERMES_HOME, accepts a runtime Nous API key (never baked into the
APK), refreshes model metadata from the Nous inference API, and runs an Android-safe
OpenAI-compatible tool loop against Nous Research.
"""

from __future__ import annotations

import json
import os
import time
from pathlib import Path
from typing import Any, Dict, List, Optional

import httpx

# Android's Java networking uses the platform DNS/TLS stack, unlike embedded CPython.
try:
    from java import jclass
    _native_http = jclass("com.mycosensai.hermes.NativeHttp")
except ImportError:
    _native_http = None


class _PlatformTransport(httpx.BaseTransport):
    def handle_request(self, request):
        try:
            reply = json.loads(str(_native_http.request(
                str(request.url), request.method,
                json.dumps(dict(request.headers)), request.content.decode("utf-8")
            )))
            return httpx.Response(reply["status"], content=reply["body"].encode("utf-8"), request=request)
        except Exception as exc:
            raise httpx.ConnectError("Could not reach Nous using Android networking. Check your connection.", request=request) from exc


def _http_client(**kwargs):
    if _native_http is not None:
        kwargs["transport"] = _PlatformTransport()
    return httpx.Client(**kwargs)


def reload_oauth():
    """Read Kotlin's atomically persisted OAuth state; never cache rotated tokens as API keys."""
    set_api_key("")
    return auth_status()


INFERENCE_URL = "https://inference-api.nousresearch.com/v1"
DEFAULT_MODEL = "anthropic/claude-sonnet-4.6"
FALLBACK_MODELS = [
    "anthropic/claude-sonnet-4.6",
    "openai/gpt-4.1",
    "openai/gpt-4o",
    "google/gemini-2.5-pro",
    "deepseek/deepseek-v3.2",
]
# Mobile-safe toolsets: in-memory todo (+ optional clarify). Avoid terminal/browser.
DEFAULT_TOOLSETS = ["todo"]

_home: Optional[Path] = None
_api_key: Optional[str] = None
_model: str = DEFAULT_MODEL
_models_cache: List[str] = []
_models_fetched_at: float = 0.0


def configure_home(path: str) -> str:
    """Set HERMES_HOME and HOME before any hermes import. Called from Application.onCreate."""
    global _home
    home = Path(path)
    home.mkdir(parents=True, exist_ok=True)
    os.environ["HERMES_HOME"] = str(home)
    os.environ["HOME"] = str(home)
    shared = home / "shared"
    shared.mkdir(parents=True, exist_ok=True)
    os.environ["HERMES_SHARED_AUTH_DIR"] = str(shared)
    # Quiet noisy desktop defaults on mobile.
    os.environ.setdefault("HERMES_QUIET", "1")
    _home = home
    return str(home)


def set_api_key(key: str) -> str:
    """Store API key in process memory + env for hermes resolvers. Never log the value."""
    global _api_key
    cleaned = (key or "").strip()
    _api_key = cleaned or None
    if cleaned:
        os.environ["NOUS_API_KEY"] = cleaned
    else:
        os.environ.pop("NOUS_API_KEY", None)
    return json.dumps({"ok": True, "configured": bool(cleaned)})


def clear_api_key() -> str:
    return set_api_key("")


def set_model(model: str) -> str:
    global _model
    cleaned = (model or "").strip() or DEFAULT_MODEL
    _model = cleaned
    os.environ["HERMES_MODEL"] = cleaned
    return json.dumps({"ok": True, "model": _model})


def _auth_path() -> Path:
    if _home is None:
        raise RuntimeError("configure_home() was not called")
    return _home / "auth.json"


def _load_store() -> Dict[str, Any]:
    path = _auth_path()
    if not path.exists():
        return {"version": 1, "providers": {}}
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return {"version": 1, "providers": {}}
    if not isinstance(data, dict):
        return {"version": 1, "providers": {}}
    data.setdefault("version", 1)
    data.setdefault("providers", {})
    return data


def _save_store(store: Dict[str, Any]) -> None:
    path = _auth_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(store, indent=2, sort_keys=True), encoding="utf-8")
    tmp.replace(path)


def _nous_state() -> Optional[Dict[str, Any]]:
    store = _load_store()
    providers = store.get("providers") or {}
    state = providers.get("nous")
    return dict(state) if isinstance(state, dict) else None


def _runtime_api_key() -> Optional[str]:
    if _api_key:
        return _api_key
    env = os.environ.get("NOUS_API_KEY") or os.environ.get("HERMES_API_KEY")
    if env and env.strip():
        return env.strip()
    state = _nous_state()
    if not state:
        return None
    for field in ("agent_key", "access_token"):
        val = state.get(field)
        if isinstance(val, str) and val.strip():
            return val.strip()
    return None


def auth_status() -> str:
    key = _runtime_api_key()
    if key:
        return json.dumps(
            {
                "signed_in": True,
                "mode": "api_key" if _api_key or os.environ.get("NOUS_API_KEY") else "oauth",
                "summary": f"Ready · model {_model}",
                "model": _model,
            }
        )
    return json.dumps({"signed_in": False, "summary": "Not signed in", "model": _model})


_NON_CHAT_MARKERS = ("hermes", "embed", "rerank", "whisper", "tts", "moderation", "image", ":batch")


def _is_chat_model(model_id: str) -> bool:
    """Keep only chat-capable ids; the Nous /models list also has embedding/image models."""
    mid = model_id.strip().lower()
    return bool(mid) and not any(marker in mid for marker in _NON_CHAT_MARKERS)


def refresh_models(force: bool = False) -> str:
    """Refresh model list from Nous /v1/models. Filters out Hermes chat-only models."""
    global _models_cache, _models_fetched_at, _model
    now = time.time()
    if not force and _models_cache and (now - _models_fetched_at) < 300:
        return json.dumps(
            {
                "ok": True,
                "models": _models_cache,
                "selected": _model,
                "cached": True,
            }
        )

    key = _runtime_api_key()
    if not key:
        _models_cache = list(FALLBACK_MODELS)
        _models_fetched_at = now
        return json.dumps(
            {
                "ok": True,
                "models": _models_cache,
                "selected": _model,
                "cached": False,
                "note": "offline_fallback",
            }
        )

    try:
        with _http_client(timeout=20.0, headers={"Accept": "application/json"}) as client:
            resp = client.get(
                f"{INFERENCE_URL.rstrip('/')}/models",
                headers={"Authorization": f"Bearer {key}"},
            )
            resp.raise_for_status()
            data = resp.json().get("data") or []
        ids = [
            item["id"].strip()
            for item in data
            if isinstance(item, dict) and isinstance(item.get("id"), str) and _is_chat_model(item["id"])
        ]

        # Prefer known agentic models first.
        preferred = [m for m in FALLBACK_MODELS if m in ids]
        rest = [m for m in ids if m not in preferred]
        _models_cache = (preferred + rest)[:80] or list(FALLBACK_MODELS)
        _models_fetched_at = now
        if _model not in _models_cache:
            _model = _models_cache[0]
        return json.dumps(
            {
                "ok": True,
                "models": _models_cache,
                "selected": _model,
                "cached": False,
                "count": len(_models_cache),
            }
        )
    except Exception as exc:
        _models_cache = list(FALLBACK_MODELS)
        _models_fetched_at = now
        return json.dumps(
            {
                "ok": False,
                "error": str(exc),
                "models": _models_cache,
                "selected": _model,
            }
        )


def chat(message: str) -> str:
    """Run one agent turn against Nous with the Android-safe tool loop."""
    text = (message or "").strip()
    if not text:
        return json.dumps({"ok": False, "error": "Empty message"})
    if not _runtime_api_key():
        return json.dumps({"ok": False, "error": "Not signed in — set API key or complete Portal login"})
    # Hermes' AIAgent needs the openai SDK (pydantic-core), which has no Chaquopy
    # wheel, so the app runs an OpenAI-compatible tool loop over platform HTTP.
    try:
        reply = _http_agent_loop(text)
        return json.dumps({"ok": True, "reply": reply or "", "via": "android_tool_loop", "model": _model})
    except Exception as exc:
        return json.dumps({"ok": False, "error": str(exc)})


def _todo_tools_schema():
    return [
        {
            "type": "function",
            "function": {
                "name": "todo",
                "description": "Read or write an in-memory todo list.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "todos": {
                            "type": "array",
                            "items": {
                                "type": "object",
                                "properties": {
                                    "id": {"type": "string"},
                                    "content": {"type": "string"},
                                    "status": {"type": "string"},
                                },
                                "required": ["id", "content", "status"],
                            },
                        },
                        "merge": {"type": "boolean"},
                    },
                },
            },
        }
    ]


_todo_items: list = []


def _exec_todo(args: Dict[str, Any]) -> str:
    global _todo_items
    todos = args.get("todos")
    if todos is None:
        return json.dumps({"todos": _todo_items})
    merge = bool(args.get("merge") or False)
    cleaned = []
    for t in todos:
        if not isinstance(t, dict):
            continue
        cleaned.append(
            {
                "id": str(t.get("id") or ""),
                "content": str(t.get("content") or ""),
                "status": str(t.get("status") or "pending"),
            }
        )
    if merge:
        by_id = {t["id"]: t for t in _todo_items}
        for t in cleaned:
            by_id[t["id"]] = t
        _todo_items = list(by_id.values())
    else:
        _todo_items = cleaned
    return json.dumps({"todos": _todo_items})


def _http_agent_loop(message: str, max_iterations: int = 8) -> str:
    """OpenAI-compatible tool loop over httpx (works without pydantic/openai wheels)."""
    key = _runtime_api_key()
    if not key:
        raise RuntimeError("No API key")
    messages: List[Dict[str, Any]] = [
        {
            "role": "system",
            "content": (
                "You are Hermes on Android. Be concise. "
                "You may use the todo tool when asked to track tasks."
            ),
        },
        {"role": "user", "content": message},
    ]
    tools = _todo_tools_schema()
    with _http_client(timeout=120.0) as client:
        for _ in range(max_iterations):
            resp = client.post(
                f"{INFERENCE_URL.rstrip('/')}/chat/completions",
                headers={
                    "Authorization": f"Bearer {key}",
                    "Content-Type": "application/json",
                },
                json={
                    "model": _model,
                    "messages": messages,
                    "tools": tools,
                    "tool_choice": "auto",
                    "max_tokens": 1024,
                },
            )
            if resp.status_code != 200:
                raise RuntimeError(f"HTTP {resp.status_code}: {resp.text[:300]}")
            payload = resp.json()
            choice = (payload.get("choices") or [{}])[0]
            msg = choice.get("message") or {}
            tool_calls = msg.get("tool_calls") or []
            content = msg.get("content")
            if tool_calls:
                messages.append(
                    {
                        "role": "assistant",
                        "content": content,
                        "tool_calls": tool_calls,
                    }
                )
                for tc in tool_calls:
                    fn = (tc.get("function") or {})
                    name = fn.get("name") or ""
                    raw_args = fn.get("arguments") or "{}"
                    try:
                        args = json.loads(raw_args) if isinstance(raw_args, str) else (raw_args or {})
                    except Exception:
                        args = {}
                    if name == "todo":
                        result = _exec_todo(args if isinstance(args, dict) else {})
                    else:
                        result = json.dumps({"error": f"unknown tool {name}"})
                    messages.append(
                        {
                            "role": "tool",
                            "tool_call_id": tc.get("id") or name,
                            "content": result,
                        }
                    )
                continue
            return content or ""
    return ""


HERMES_SELF_TEST_MODULES = [
    "hermes_constants",
    "hermes_cli",
    "hermes_cli.config",
    "hermes_cli.auth",
    "toolsets",
    "tools.todo_tool",
    "model_tools",
    "run_agent",
]


def self_test() -> str:
    """Report which bundled Hermes modules import on this device (no network, no secrets)."""
    import importlib
    import platform
    import sys

    results = {}
    for name in HERMES_SELF_TEST_MODULES:
        try:
            importlib.import_module(name)
            results[name] = "ok"
        except BaseException as exc:  # SystemExit from CLI modules must not kill the app
            results[name] = f"{type(exc).__name__}: {str(exc)[:160]}"
    try:
        importlib.import_module("openai")
        openai_status = "ok"
    except BaseException as exc:
        openai_status = f"{type(exc).__name__}"
    return json.dumps(
        {
            "ok": all(v == "ok" for v in results.values()),
            "python": sys.version.split()[0],
            "machine": platform.machine(),
            "hermes_home": os.environ.get("HERMES_HOME"),
            "modules": results,
            "openai_sdk": openai_status,
            "native_http": _native_http is not None,
        }
    )


def process_pending_prompt() -> str:
    """Debug helper: if HERMES_HOME/pending_prompt.txt exists, run chat and write last_reply.txt."""
    if _home is None:
        return json.dumps({"ok": False, "error": "home not configured"})
    pending = _home / "pending_prompt.txt"
    if not pending.exists():
        return json.dumps({"ok": False, "error": "no pending prompt"})
    try:
        message = pending.read_text(encoding="utf-8").strip()
        pending.unlink(missing_ok=True)
    except Exception as exc:
        return json.dumps({"ok": False, "error": str(exc)})
    if not message:
        return json.dumps({"ok": False, "error": "empty prompt"})
    result = chat(message)
    try:
        (_home / "last_reply.json").write_text(result, encoding="utf-8")
    except Exception:
        pass
    return result


