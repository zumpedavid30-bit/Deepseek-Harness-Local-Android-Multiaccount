# Multiple API keys per provider (multi-account)

One provider, several keys, plus the policy that decides which key is in use.
Everything stays on the device — no proxy, no cloud, no fork of the engine.

## What "an account" is

The engine stores provider keys in `dsh-home/.credentials.yaml`:

```yaml
version: 1
refs:
  OPENROUTER_API_KEY: sk-or-…
  NVIDIA_API_KEY: nvapi-…
records:
  llm-pi-ai/openai-codex: { kind: grant, … }
```

`refs` is a flat map of **credential reference → secret**. A pi-ai provider
route names the reference it wants (`apiKeyEnv`), and the adapter resolves that
reference **on every request** (`@deepseek-ai/dsh-credentials`: *"a changed
credential reaches the next operation without any plugin restart"*). Routes
without an explicit `apiKeyEnv` fall back to the `env` list of the pi-ai
catalogue (`openrouter → OPENROUTER_API_KEY`, `nvidia → NVIDIA_API_KEY`,
`deepseek → DEEPSEEK_API_KEY`, …).

So an "account" here is **one value of one reference**. Replacing that value
switches the account for the very next model request — no engine restart, no
WebUI reload, no configuration edit.

## Who writes what

| File | Writer | Purpose |
| --- | --- | --- |
| `dsh-home/multi-account.json` | the app (`MultiAccountStore.kt`) | providers, accounts, enabled flags, switch mode, the pick of the user |
| `dsh-home/multi-account.state.json` | the engine plugin (`multi-account.mjs`) | which account is *actually* active, why, and the cool-downs |
| `dsh-home/.credentials.yaml` | the engine plugin, through `ctx.credentials.set()` | the key the engine reads |

Two files, two writers — there is no read-modify-write race between the app and
the engine, and the credential store keeps its cross-process writer lock,
comment preservation and `credentials/reference-updated` event. The app never
touches `.credentials.yaml` itself.

## Switch modes

| Mode | Behaviour |
| --- | --- |
| `off` | The plugin never writes the reference. A key typed into the WebUI Models page stays in charge. |
| `manual` | Only the account picked in the app (or the first enabled one) is applied. |
| `on-error` | `manual`, plus fail-over: on a rate limit, quota, or auth failure the next enabled account becomes active and the step is retried. |
| `round-robin` | `on-error`, plus the active account advances on every model step. |

An account can be disabled without being deleted; rotation and fail-over skip
it, and the UI keeps it in the list.

## Fail-over mechanics

`agent/request-error` is a Cordis **waterfall**: a listener that owns recovery
returns `{ kind: 'retry' }` instead of calling `next()`, and the agent loop
re-runs the failed step in the same turn.

The plugin registers that listener with `{ prepend: true }`. That is not a
detail — `@deepseek-ai/dsh-llm-retry` is registered earlier (it is part of the
base composition, the app's overlay loads last) and, in normal mode, spends its
whole retry budget on the *same* exhausted key before delegating. Prepending
puts the account switch in front of it.

Which failures count as "this account is used up":

* codes `RATE_LIMIT`, `QUOTA`, `ACCOUNT_QUOTA`, `INVALID_CREDENTIAL`,
  `MISSING_CREDENTIAL`, `UNAUTHORIZED`, `AUTH`, `FORBIDDEN`
* any HTTP `401 / 402 / 403 / 429`

`TRANSPORT`, `SERVER`, `TIMEOUT` and `EMPTY_RESPONSE` are deliberately **not**
handled: a socket hiccup is what `dsh-llm-retry` exists for, and switching keys
would not help.

After a failure the account cools down for `cooldownMs` (default 60 s) and is
skipped while another account is available. The fail-over budget is capped at
the number of enabled accounts per step, so two exhausted keys cannot ping-pong
forever; once the budget is gone the failure is delegated to the retry executor
and ends up as a normal error.

## Setting it up

1. WebUI → **Models**: add the provider (route + models) and type any one key.
   The route has to exist — the account list only manages the *value* of its
   reference.
2. App → **Settings → Model accounts**: add the provider (a known route can be
   picked from the list), then add one account per key, enable the ones you
   want and pick the switch mode.
3. The engine applies the choice within two seconds, and at the latest before
   the next model request.

## Limits

* Keys live in the app-private `dsh-home` (owner-only), the same trust boundary
  as `.credentials.yaml` itself: the agent runs as the same UID and could read
  either file.
* A request that is already in flight finishes with the key it started with;
  the switch applies to the next request.
* Accounts are managed per **reference**, not per model: every model of a route
  that resolves that reference uses the same active account.
* `round-robin` advances per model **step**, not per token or per turn.
