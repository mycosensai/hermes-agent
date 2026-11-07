# Nous Portal auth for Hermes Android (Craig)

Derived from `hermes_cli/auth.py` in https://github.com/mycosensai/hermes-agent (main).

## Endpoints

| Step | Method | URL |
|------|--------|-----|
| Device code | POST | `https://portal.nousresearch.com/api/oauth/device/code` |
| Token (device) | POST | `https://portal.nousresearch.com/api/oauth/token` |
| Token (refresh) | POST | `https://portal.nousresearch.com/api/oauth/token` |
| Agent key | POST | `https://portal.nousresearch.com/api/oauth/agent-key` |
| Inference | POST | `https://inference-api.nousresearch.com/v1/chat/completions` |

`client_id`: `hermes-cli`

Default scope: `inference:invoke inference:mint_agent_key` (falls back to `inference:mint_agent_key` if invoke is refused).

## Device-code flow (UI-driven)

1. `POST /api/oauth/device/code` with form `client_id`, optional `scope`.
2. Response: `device_code`, `user_code`, `verification_uri`, `verification_uri_complete`, `expires_in`, `interval`.
3. Show `user_code` + open `verification_uri_complete` in a browser Intent. **Do not** call `webbrowser.open` or block the Python thread waiting for approval.
4. Poll `POST /api/oauth/token` with:
   - `grant_type=urn:ietf:params:oauth:grant-type:device_code`
   - `client_id`, `device_code`
5. Handle `authorization_pending`, `slow_down`, success (`access_token`), or errors / expiry.

## Refresh

`POST /api/oauth/token` with form `grant_type=refresh_token`, `client_id`, and header:

```
x-nous-refresh-token: <refresh_token>
```

Refresh tokens are single-use — persist the rotated token immediately to `$HERMES_HOME/auth.json`.

## Storage

- `$HERMES_HOME/auth.json` shape: `{ "version": 1, "active_provider": "nous", "providers": { "nous": { ... } } }`
- Shared store (optional): `$HERMES_SHARED_AUTH_DIR/nous_auth.json` (default under hermes root `shared/`).
- On Android set `HERMES_HOME` and `HOME` to `context.filesDir` **before** importing hermes.

## Kotlin → Python

Use `android_bridge.py` (non-blocking):

- `configure_home(path)`
- `start_device_login()` → user_code / URLs
- `poll_device_login()` → pending | approved | error
- `chat(message)` → Nous HTTP (or hermes import probe)

Do **not** call `_nous_device_code_login()` from the UI thread — it prints, may open a browser, and blocks until approval / can `SystemExit`.
