package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 多账号 API key 的配置面（v1.6.0）。
 *
 * ## 分工（关键设计）
 * - **App 只写 `multi-account.json`**（本类）：账号列表、启用开关、切换策略、用户选的当前账号。
 * - **引擎插件只写 `multi-account.state.json`**：运行时真正生效的账号、切换原因、失败冷却。
 * - 真正把 key 写进凭证库的是引擎内的 `android-plugins/multi-account.mjs`（走
 *   `ctx.credentials.set()`，尊重文件锁与 `credentials/reference-updated` 事件）。
 *
 * 为什么 App 不直接改 `.credentials.yaml`：该文件的写入有跨进程锁、注释保留与
 * owner-only 校验，Kotlin 侧手写会绕过全部三条（并发写会丢条目）。两个文件两个
 * 写者，也就没有读改写竞态。
 *
 * ## 为什么换账号不需要重启引擎
 * 引擎按请求解析凭证（`@deepseek-ai/dsh-credentials`：换 key 对下一个操作立即
 * 生效），插件把选中的 key 写进同一个 ref，因此 WebUI 不用刷新、引擎不用重启。
 */
object MultiAccountStore {

    private const val TAG = "MultiAccountStore"

    const val MODE_OFF = "off"
    const val MODE_MANUAL = "manual"
    const val MODE_ON_ERROR = "on-error"
    const val MODE_ROUND_ROBIN = "round-robin"

    /** 默认冷却：失败账号在这么久内不被优先选中（插件侧判据，这里只是默认值）。 */
    const val DEFAULT_COOLDOWN_MS = 60_000L

    /** 单个账号。`key` 只在 App 与插件之间流动，永远不进日志/UI 全文。 */
    data class Account(
        val id: String,
        val label: String,
        val key: String,
        val enabled: Boolean,
    )

    /**
     * 一个「凭证引用」（= 一个 provider 的一把钥匙位）下的多个账号。
     *
     * `ref` 是 `.credentials.yaml` 的 `refs:` 键名（如 `OPENROUTER_API_KEY`），
     * `route` 是 pi-ai 的 provider 路由名（如 `openrouter`），插件按 route 匹配
     * `agent/request-error` 的 provider 字段。
     */
    data class Provider(
        val id: String,
        val label: String,
        val route: String,
        val ref: String,
        val mode: String,
        val cooldownMs: Long,
        val activeId: String,
        val accounts: List<Account>,
    ) {
        val enabledAccounts: List<Account> get() = accounts.filter { it.enabled }

        fun account(id: String?): Account? = accounts.firstOrNull { it.id == id }

        /** 当前应当生效的账号：用户选中的（若仍启用），否则第一个启用的。 */
        fun effective(): Account? = account(activeId)?.takeIf { it.enabled } ?: enabledAccounts.firstOrNull()
    }

    /** 插件写回的运行时状态（只读展示）。 */
    data class Runtime(
        val activeId: String,
        val reason: String,
        val failures: Map<String, Long>,
    )

    fun configFile(ctx: Context): File = File(EngineConfig.dshHome(ctx), "multi-account.json")

    fun stateFile(ctx: Context): File = File(EngineConfig.dshHome(ctx), "multi-account.state.json")

    fun newId(): String = UUID.randomUUID().toString().substring(0, 8)

    // ---------------------------------------------------------------- 读取

    fun load(ctx: Context): List<Provider> {
        val f = configFile(ctx)
        if (!f.isFile) return emptyList()
        return runCatching {
            val root = JSONObject(f.readText())
            val arr = root.optJSONArray("providers") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i -> parseProvider(arr.optJSONObject(i)) }
        }.getOrElse {
            Log.w(TAG, "config parse failed: ${it.message}")
            emptyList()
        }
    }

    private fun parseProvider(o: JSONObject?): Provider? {
        if (o == null) return null
        val ref = o.optString("ref", "").trim()
        if (!isRefName(ref)) return null
        val accounts = ArrayList<Account>()
        val arr = o.optJSONArray("accounts") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            val key = a.optString("key", "").trim()
            val id = a.optString("id", "").trim()
            if (key.isEmpty() || id.isEmpty()) continue
            accounts.add(
                Account(
                    id = id,
                    label = a.optString("label", id).ifBlank { id },
                    key = key,
                    enabled = a.optBoolean("enabled", true),
                )
            )
        }
        val mode = o.optString("mode", MODE_MANUAL).let { if (it in MODES) it else MODE_MANUAL }
        return Provider(
            id = o.optString("id", ref).ifBlank { ref },
            label = o.optString("label", ref).ifBlank { ref },
            route = o.optString("route", "").trim(),
            ref = ref,
            mode = mode,
            cooldownMs = o.optLong("cooldownMs", DEFAULT_COOLDOWN_MS).coerceAtLeast(0L),
            activeId = o.optString("activeId", "").trim(),
            accounts = accounts,
        )
    }

    fun state(ctx: Context): Map<String, Runtime> {
        val f = stateFile(ctx)
        if (!f.isFile) return emptyMap()
        return runCatching {
            val providers = JSONObject(f.readText()).optJSONObject("providers") ?: JSONObject()
            val out = HashMap<String, Runtime>()
            for (id in providers.keys()) {
                val p = providers.optJSONObject(id) ?: continue
                val failures = HashMap<String, Long>()
                val fObj = p.optJSONObject("failures") ?: JSONObject()
                for (acc in fObj.keys()) {
                    val until = fObj.optJSONObject(acc)?.optLong("until", 0L) ?: 0L
                    if (until > 0L) failures[acc] = until
                }
                out[id] = Runtime(
                    activeId = p.optString("activeId", ""),
                    reason = p.optString("reason", ""),
                    failures = failures,
                )
            }
            out
        }.getOrElse { emptyMap() }
    }

    // ---------------------------------------------------------------- 写入

    /** 保存配置。插件每 2s 轮询该文件，用户点完最多两秒后生效（下一步请求前一定生效）。 */
    fun save(ctx: Context, providers: List<Provider>): Boolean = runCatching {
        val root = JSONObject()
        root.put("version", 1)
        root.put("revision", System.currentTimeMillis())
        root.put("updatedAt", System.currentTimeMillis())
        val arr = JSONArray()
        providers.forEach { p ->
            val o = JSONObject()
            o.put("id", p.id)
            o.put("label", p.label)
            o.put("route", p.route)
            o.put("ref", p.ref)
            o.put("mode", p.mode)
            o.put("cooldownMs", p.cooldownMs)
            o.put("activeId", p.activeId)
            val accs = JSONArray()
            p.accounts.forEach { a ->
                accs.put(
                    JSONObject()
                        .put("id", a.id)
                        .put("label", a.label)
                        .put("key", a.key)
                        .put("enabled", a.enabled)
                )
            }
            o.put("accounts", accs)
            arr.put(o)
        }
        root.put("providers", arr)

        val f = configFile(ctx)
        f.parentFile?.mkdirs()
        // 原子替换：插件每 2s 读一次，半截文件会被它当成"无 provider"。
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(root.toString(2))
        if (!tmp.renameTo(f)) {
            f.writeText(root.toString(2))
            tmp.delete()
        }
        Log.i(TAG, "saved ${providers.size} provider(s)")
        true
    }.getOrElse {
        Log.w(TAG, "save failed: ${it.message}")
        false
    }

    // ---------------------------------------------------------------- 工具

    /** 凭证引用必须是 POSIX shell 标识符（与 dsh-credentials 的语法一致）。 */
    fun isRefName(ref: String): Boolean =
        ref.isNotEmpty() && Regex("^[A-Za-z_][A-Za-z0-9_]*$").matches(ref)

    /** 只露头尾，供 UI 展示；key 永远不会完整出现在界面上。 */
    fun mask(key: String): String {
        if (key.length <= 8) return "••••"
        return key.take(4) + "…" + key.takeLast(4)
    }

    /**
     * 已知 provider 的路由名与默认凭证引用（取自引擎 `dsh-llm-pi-ai` 的供应商
     * 目录：`route → env[0]`）。用户也可以自己填一个别的引用名 —— 目录之外的
     * 自建网关同样适用。
     */
    data class Known(val label: String, val route: String, val ref: String)

    val KNOWN: List<Known> = listOf(
        Known("DeepSeek", "deepseek", "DEEPSEEK_API_KEY"),
        Known("OpenRouter", "openrouter", "OPENROUTER_API_KEY"),
        Known("NVIDIA NIM", "nvidia", "NVIDIA_API_KEY"),
        Known("OpenAI", "openai", "OPENAI_API_KEY"),
        Known("Anthropic", "anthropic", "ANTHROPIC_API_KEY"),
        Known("Google Gemini", "google", "GEMINI_API_KEY"),
        Known("Groq", "groq", "GROQ_API_KEY"),
        Known("Fireworks", "fireworks", "FIREWORKS_API_KEY"),
        Known("Together", "together", "TOGETHER_API_KEY"),
        Known("Moonshot Kimi", "moonshotai", "MOONSHOT_API_KEY"),
        Known("xAI Grok", "xai", "XAI_API_KEY"),
        Known("Mistral", "mistral", "MISTRAL_API_KEY"),
        Known("Cerebras", "cerebras", "CEREBRAS_API_KEY"),
    )

    private val MODES = setOf(MODE_OFF, MODE_MANUAL, MODE_ON_ERROR, MODE_ROUND_ROBIN)
}
