/**
 * [dsh-android] Multi-account API keys (v1.6.0).
 *
 * ## What it does
 * The engine resolves every provider credential **per request** through
 * `ctx.credentials` (see `@deepseek-ai/dsh-credentials`: "a changed credential
 * reaches the next operation without any plugin restart"). This plugin keeps a
 * list of API keys per credential reference and makes exactly one of them the
 * stored value, so switching accounts needs neither an engine restart nor a
 * configuration edit.
 *
 * Three switch strategies per provider:
 *   · `manual`      – only the account the user picked in the app.
 *   · `on-error`    – manual, plus fail-over to the next enabled account when a
 *                     request dies on a rate limit, quota, or auth failure.
 *   · `round-robin` – advance to the next enabled account on every model step
 *                     (and fail over on error as well).
 *   · `off`         – leave the reference alone (the WebUI Models page owns it).
 *
 * ## Why a plugin and not the WebUI Models page
 * Upstream's Models page stores exactly ONE key per reference. Managing a list
 * of keys, their enabled flags and a rotation policy belongs to the app, and the
 * write into the credential store has to come from inside the engine process:
 * `ctx.credentials.set()` is the only writer that respects the store's
 * cross-process lock, comment preservation and `credentials/reference-updated`
 * event. So the app writes a plain JSON file, this plugin performs the writes.
 *
 * ## Files (both under $DSH_HOME)
 *   · `multi-account.json`       – config, written by the app only (read here).
 *   · `multi-account.state.json` – runtime state, written here only (read by
 *                                  the app to show the active account/reason).
 * Two files, two writers: no read-modify-write race between app and engine.
 *
 * ## Error fail-over
 * `agent/request-error` is a waterfall; a listener that owns recovery returns
 * `{ kind: 'retry' }` without calling `next()`. This listener is registered with
 * `{ prepend: true }` so it runs BEFORE `@deepseek-ai/dsh-llm-retry`: the retry
 * executor would otherwise burn its whole budget on the exhausted key and only
 * then hand the failure over.
 *
 * @module dsh-android-multi-account
 */

import fs from "node:fs";
import path from "node:path";

const name = "android-multi-account";
const inject = ["credentials"];

/** App-owned config, read every tick. */
const CONFIG_NAME = "multi-account.json";
/** Plugin-owned runtime state, written on every change. */
const STATE_NAME = "multi-account.state.json";
/** Config poll interval — also the latency of a manual switch from the app. */
const TICK_MS = 2000;
/** Default cool-down for an account that just failed. */
const DEFAULT_COOLDOWN_MS = 60000;
/** Failure classes that mean "this account is used up", not "the network hiccuped". */
const FAILOVER_CODES = new Set([
  "RATE_LIMIT",
  "QUOTA",
  "ACCOUNT_QUOTA",
  "INVALID_CREDENTIAL",
  "MISSING_CREDENTIAL",
  "UNAUTHORIZED",
  "AUTH",
  "FORBIDDEN",
]);
/** HTTP statuses that always fail over, whatever the adapter's code. */
const FAILOVER_STATUS = new Set([401, 402, 403, 429]);
/** A credential reference is a POSIX shell identifier (same grammar as the store). */
const REF_RE = /^[A-Za-z_][A-Za-z0-9_]*$/;
const MODES = new Set(["off", "manual", "on-error", "round-robin"]);

function home() {
  return process.env.DSH_HOME || process.env.HOME || "";
}

function readJson(file) {
  try {
    return JSON.parse(fs.readFileSync(file, "utf8"));
  } catch {
    return null;
  }
}

/** Atomic replace: a half-written state file must never be read back. */
function writeJson(file, value) {
  try {
    const tmp = file + ".tmp-" + process.pid;
    fs.writeFileSync(tmp, JSON.stringify(value, null, 2) + "\n");
    fs.renameSync(tmp, file);
  } catch {
    /* state is diagnostic only — never fail the engine over it */
  }
}

function asString(v, fallback) {
  return typeof v === "string" && v.trim() ? v.trim() : fallback;
}

/**
 * Read + validate the app-owned config. Anything unusable is dropped instead of
 * throwing: a broken file must never take the engine down with it.
 */
function readConfig(file) {
  const raw = readJson(file);
  const out = { revision: 0, providers: [] };
  if (!raw || typeof raw !== "object") return out;
  out.revision = Number(raw.revision) || 0;
  const list = Array.isArray(raw.providers) ? raw.providers : [];
  for (const p of list) {
    if (!p || typeof p !== "object") continue;
    const ref = asString(p.ref, "");
    if (!REF_RE.test(ref)) continue;
    const accounts = [];
    for (const a of Array.isArray(p.accounts) ? p.accounts : []) {
      if (!a || typeof a !== "object") continue;
      const key = asString(a.key, "");
      if (!key) continue;
      const id = asString(a.id, "");
      if (!id) continue;
      accounts.push({
        id,
        label: asString(a.label, id),
        key,
        enabled: a.enabled !== false,
      });
    }
    if (accounts.length === 0) continue;
    const mode = asString(p.mode, "manual");
    out.providers.push({
      id: asString(p.id, ref),
      label: asString(p.label, ref),
      route: asString(p.route, ""),
      ref,
      mode: MODES.has(mode) ? mode : "manual",
      cooldownMs: Math.max(0, Number(p.cooldownMs) || DEFAULT_COOLDOWN_MS),
      activeId: asString(p.activeId, ""),
      accounts,
    });
  }
  return out;
}

function enabledAccounts(p) {
  return p.accounts.filter((a) => a.enabled);
}

function accountById(p, id) {
  return id ? p.accounts.find((a) => a.id === id && a.enabled) : undefined;
}

function isCooling(st, providerId, accountId, now) {
  const entry = st.providers[providerId]?.failures?.[accountId];
  return Boolean(entry && Number(entry.until) > now);
}

/**
 * Pick the next usable account, preferring enabled + not cooling down.
 * `skipId` is the account that just failed. Returns undefined when there is no
 * other account to move to.
 */
function nextAccount(p, st, now, skipId) {
  const list = enabledAccounts(p);
  if (list.length === 0) return undefined;
  const current = st.providers[p.id]?.activeId || p.activeId;
  const startIdx = Math.max(0, list.findIndex((a) => a.id === current));
  for (let step = 1; step <= list.length; step += 1) {
    const cand = list[(startIdx + step) % list.length];
    if (cand.id === skipId) continue;
    if (isCooling(st, p.id, cand.id, now)) continue;
    return cand;
  }
  // Everything is cooling down: fall back to the account whose penalty ends first.
  const others = list.filter((a) => a.id !== skipId);
  if (others.length === 0) return undefined;
  return others.reduce((best, a) => {
    const until = Number(st.providers[p.id]?.failures?.[a.id]?.until) || 0;
    const bestUntil = Number(st.providers[p.id]?.failures?.[best.id]?.until) || 0;
    return until < bestUntil ? a : best;
  }, others[0]);
}

async function apply(ctx) {
  const h = home();
  const log = ctx.logger ?? console;
  if (!h) {
    log.warn?.("[multi-account] neither DSH_HOME nor HOME is set — plugin idle");
    return;
  }
  const configFile = path.join(h, CONFIG_NAME);
  const stateFile = path.join(h, STATE_NAME);

  /** `ctx.credentials` (injected); `get()` keeps it working if the service re-arms. */
  const store = () => ctx.get?.("credentials") ?? ctx.credentials;

  const state = readJson(stateFile) || {};
  state.version = 1;
  state.providers = state.providers && typeof state.providers === "object" ? state.providers : {};

  let config = readConfig(configFile);
  let lastConfigStamp = stamp(configFile);
  /** Fail-over budget for the step currently running: `${agent}:${turn}:${step}`. */
  let attempt = { key: "", count: 0 };

  function stamp(file) {
    try {
      const st = fs.statSync(file);
      return st.mtimeMs + ":" + st.size;
    } catch {
      return "missing";
    }
  }

  function stateOf(providerId) {
    if (!state.providers[providerId]) {
      state.providers[providerId] = { activeId: "", reason: "", at: 0, failures: {} };
    }
    const s = state.providers[providerId];
    s.failures = s.failures && typeof s.failures === "object" ? s.failures : {};
    return s;
  }

  function saveState() {
    state.updatedAt = Date.now();
    state.plugin = name + "/1.0.0";
    writeJson(stateFile, state);
  }

  /**
   * Make `account` the stored value of the provider's credential reference.
   * A no-op when the reference already holds that key — avoids a write and a
   * `credentials/reference-updated` storm on every tick.
   */
  async function push(p, account, reason) {
    const s = stateOf(p.id);
    const nextId = account ? account.id : "";
    if (account) {
      try {
        const hit = await store()?.resolve(p.ref);
        if (!hit || hit.value !== account.key) {
          await store().set(p.ref, account.key);
          log.info?.(`[multi-account] ${p.id}: key -> "${account.label}" (${reason})`);
        }
      } catch (err) {
        log.warn?.(`[multi-account] ${p.id}: cannot write ${p.ref}: ${err && err.message}`);
        return;
      }
    }
    if (s.activeId !== nextId || (reason && s.reason !== reason)) {
      s.activeId = nextId;
      s.reason = reason || s.reason || "";
      s.at = Date.now();
      saveState();
    }
  }

  /**
   * Adopt a pick the user made in the app.
   *
   * Runtime rotation (`round-robin`, fail-over) writes `activeId` too, so the
   * two must be told apart: `appPick` remembers the last value the app sent, and
   * only a change on that side resets the runtime choice (and the cool-downs).
   */
  function adoptAppPick(p) {
    const s = stateOf(p.id);
    if (s.appPick === p.activeId) return;
    s.appPick = p.activeId;
    s.failures = {};
    if (accountById(p, p.activeId)) s.activeId = p.activeId;
    saveState();
  }

  /** Effective account: runtime choice first, then the app's pick, then the first enabled one. */
  function desired(p, now) {
    adoptAppPick(p);
    const s = stateOf(p.id);
    const candidates = [s.activeId, p.activeId];
    for (const id of candidates) {
      const hit = accountById(p, id);
      if (hit && !isCooling(state, p.id, id, now)) return hit;
    }
    for (const id of candidates) {
      const hit = accountById(p, id);
      if (hit) return hit;
    }
    return enabledAccounts(p)[0];
  }

  /** Re-apply every provider that this plugin owns. */
  async function syncAll(reason) {
    const now = Date.now();
    for (const p of config.providers) {
      if (p.mode === "off") continue;
      adoptAppPick(p);
      const account = desired(p, now);
      if (!account) continue;
      await push(p, account, reason);
    }
  }

  /** Reload the config when the app wrote it. Returns true when it changed. */
  function refreshConfig() {
    const s = stamp(configFile);
    if (s === lastConfigStamp) return false;
    lastConfigStamp = s;
    config = readConfig(configFile);
    log.info?.(`[multi-account] config reloaded (${config.providers.length} provider(s))`);
    return true;
  }

  function providerForRoute(route) {
    if (!route) return undefined;
    const hit = config.providers.find((p) => p.route && p.route === route);
    if (hit) return hit;
    // A route the app could not name: fall back to a single provider that owns
    // the only reference, so an unnamed route still fails over.
    return config.providers.length === 1 ? config.providers[0] : undefined;
  }

  // ---- boot: apply the app's choice before the first request ----------------
  await syncAll("boot");

  // ---- config poll: the app writes the file, we are the only writer of refs --
  const timer = setInterval(() => {
    if (refreshConfig()) syncAll("config").catch(() => {});
  }, TICK_MS);
  timer.unref?.();
  try {
    ctx.effect?.(() => () => clearInterval(timer));
  } catch {
    /* effect() is optional sugar */
  }

  // ---- round robin: advance once per model step ----------------------------
  try {
    ctx.on(
      "agent/pre-step",
      async (payload, next) => {
        if (refreshConfig()) await syncAll("config");
        const now = Date.now();
        for (const p of config.providers) {
          if (p.mode !== "round-robin") continue;
          if (enabledAccounts(p).length < 2) continue;
          const account = nextAccount(p, state, now, undefined);
          if (!account) continue;
          await push(p, account, "round-robin");
        }
        // A new step gets a fresh fail-over budget.
        attempt = { key: "", count: 0 };
        return next();
      },
      { prepend: true },
    );
  } catch (err) {
    log.warn?.(`[multi-account] cannot hook agent/pre-step: ${err && err.message}`);
  }

  // ---- fail-over: own the failure before the retry executor sees it --------
  try {
    ctx.on(
      "agent/request-error",
      async (payload, next) => {
        const failure = payload?.failure;
        if (!failure) return next();
        const code = String(failure.code || "");
        const status = Number(failure.status) || 0;
        const eligible = FAILOVER_CODES.has(code) || FAILOVER_STATUS.has(status);
        if (!eligible) return next();

        refreshConfig();
        const p = providerForRoute(payload.provider);
        if (!p || (p.mode !== "on-error" && p.mode !== "round-robin")) return next();
        if (enabledAccounts(p).length < 2) return next();

        const key = `${payload.turn}:${payload.step}`;
        if (attempt.key !== key) attempt = { key, count: 0 };
        if (attempt.count >= enabledAccounts(p).length) return next();

        const now = Date.now();
        const s = stateOf(p.id);
        const failedId = s.activeId || p.activeId || "";
        if (failedId) {
          const prev = s.failures[failedId];
          s.failures[failedId] = {
            count: (Number(prev?.count) || 0) + 1,
            until: now + p.cooldownMs,
            code,
          };
        }
        const account = nextAccount(p, state, now, failedId);
        if (!account) {
          saveState();
          return next();
        }
        attempt.count += 1;
        log.warn?.(`[multi-account] ${p.id}: ${code || status} -> fail-over to "${account.label}"`);
        await push(p, account, "error");
        return { kind: "retry" };
      },
      { prepend: true },
    );
  } catch (err) {
    log.warn?.(`[multi-account] cannot hook agent/request-error: ${err && err.message}`);
  }

  log.info?.(
    `[multi-account] ready: ${config.providers.length} provider(s), state ${path.basename(stateFile)}`,
  );
}

export { apply, inject, name };
