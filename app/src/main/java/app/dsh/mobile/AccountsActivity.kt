package app.dsh.mobile

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.engine.MultiAccountStore

/**
 * 模型账号（v1.6.0）—— 一个 provider 下挂多把 API key，并决定怎么切。
 *
 * ## 它管理什么
 * 每个「凭证引用」（`refs:` 里的一行，如 `OPENROUTER_API_KEY`）可以有多把 key：
 * 增删、启停、手动指定当前账号，以及三种切换策略（手动 / 出错时 / 轮询）。
 *
 * ## 它不做什么
 * **不直接写 `.credentials.yaml`**。真正把选中的 key 写进凭证库的是引擎内的
 * `multi-account.mjs`（`ctx.credentials.set()`）—— 那里才有跨进程写锁、注释保留
 * 与 `credentials/reference-updated` 事件。本页只写 `multi-account.json`，插件
 * 每 2 秒轮询并在下一个模型请求前应用，所以换账号**不需要重启引擎**。
 *
 * ## 为什么 UI 全用代码构建
 * 账号数量与 provider 数量都是动态的；固定的 XML 只出「页头 + 滚动容器 + 加
 * provider 一行」，卡片按数据现拼，改动一处不用同步改两个文件。
 */
class AccountsActivity : ThemedActivity() {

    private lateinit var content: LinearLayout
    private var providers: MutableList<MultiAccountStore.Provider> = mutableListOf()
    private var runtime: Map<String, MultiAccountStore.Runtime> = emptyMap()

    private val matchParent = LinearLayout.LayoutParams.MATCH_PARENT
    private val wrapContent = LinearLayout.LayoutParams.WRAP_CONTENT

    private val modes
        get() = listOf(
            MultiAccountStore.MODE_MANUAL,
            MultiAccountStore.MODE_ON_ERROR,
            MultiAccountStore.MODE_ROUND_ROBIN,
            MultiAccountStore.MODE_OFF,
        )

    private fun modeLabels(): List<String> = listOf(
        getString(R.string.accounts_mode_manual),
        getString(R.string.accounts_mode_error),
        getString(R.string.accounts_mode_roundrobin),
        getString(R.string.accounts_mode_off),
    )

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OrientationPrefs.applyToSettingsPage(this)
        setContentView(R.layout.activity_accounts)

        content = findViewById<LinearLayout>(R.id.accountsContent)
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
    }

    /**
     * onResume：从设置页返回或切回前台时刷新插件写回的状态。
     * 同时重新对齐一次朝向偏好 —— 否则从外观页返回本页时，本页仍持有上一次的
     * requestedOrientation，会把屏幕扳回去（见 scripts/probes/_test_responsive_native_ui.py）。
     */
    override fun onResume() {
        super.onResume()
        OrientationPrefs.applyToSettingsPage(this)
        reload()
    }

    private fun reload() {
        providers = MultiAccountStore.load(this).toMutableList()
        runtime = MultiAccountStore.state(this)
        render()
    }

    // ------------------------------------------------------------------ 渲染

    private fun render() {
        content.removeAllViews()
        content.addView(tv(getString(R.string.accounts_sub), 13f, R.color.text_secondary))
        content.addView(tv(getString(R.string.accounts_hint_engine), 12f, R.color.text_muted).apply {
            setPadding(0, dp(6), 0, 0)
        })

        if (providers.isEmpty()) {
            content.addView(tv(getString(R.string.accounts_empty), 13f, R.color.text_muted).apply {
                setPadding(0, dp(16), 0, 0)
            })
        } else {
            providers.forEach { content.addView(providerCard(it)) }
        }

        val add = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = getDrawable(R.drawable.bg_btn_outline)
            layoutParams = LinearLayout.LayoutParams(matchParent, dp(46)).apply { topMargin = dp(12) }
            setOnClickListener { addProviderDialog() }
        }
        add.addView(tv(getString(R.string.accounts_add_provider), 14f, R.color.accent, true))
        content.addView(add)
    }

    private fun providerCard(p: MultiAccountStore.Provider): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_card)
            setPadding(dp(14), dp(12), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(matchParent, wrapContent).apply { topMargin = dp(12) }
        }

        // ---- 标题行：名字 + 删除 ----
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(tv(p.label, 16f, R.color.text_primary, true), LinearLayout.LayoutParams(0, wrapContent, 1f))
        head.addView(tv(getString(R.string.accounts_delete), 12f, R.color.danger).apply {
            setPadding(dp(10), dp(4), 0, dp(4))
            setOnClickListener { confirmDeleteProvider(p) }
        })
        card.addView(head)

        // ---- 引用 + 路由 ----
        val sub = if (p.route.isNotEmpty()) p.ref + "  ·  " + p.route else p.ref
        card.addView(tv(sub, 11f, R.color.text_muted).apply { setPadding(0, dp(2), 0, dp(8)) })

        // ---- 切换策略 ----
        card.addView(tv(getString(R.string.accounts_mode), 12f, R.color.text_secondary))
        val spinner = Spinner(this).apply {
            setPopupBackgroundDrawable(getDrawable(R.drawable.bg_spinner_popup))
            adapter = ArrayAdapter(this@AccountsActivity, R.layout.item_spinner_dark, modeLabels())
            layoutParams = LinearLayout.LayoutParams(matchParent, wrapContent).apply { topMargin = dp(2) }
        }
        val modeIndex = modes.indexOf(p.mode).coerceAtLeast(0)
        spinner.setSelection(modeIndex, false)
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val next = modes.getOrNull(position) ?: return
                if (next == p.mode) return
                update(p.id) { it.copy(mode = next) }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        card.addView(spinner)

        // ---- 账号列表 ----
        if (p.accounts.isEmpty()) {
            card.addView(tv(getString(R.string.accounts_no_accounts), 12f, R.color.text_muted).apply {
                setPadding(0, dp(10), 0, 0)
            })
        } else {
            val rt = runtime[p.id]
            val now = System.currentTimeMillis()
            p.accounts.forEach { a ->
                card.addView(accountRow(p, a, rt, now))
            }
        }

        // ---- 当前生效 ----
        val eff = p.effective()
        val rt = runtime[p.id]
        val line = StringBuilder()
        if (eff != null) line.append(getString(R.string.accounts_active)).append(": ").append(eff.label)
        if (rt != null && rt.reason.isNotEmpty()) {
            line.append(" · ").append(reasonLabel(rt.reason))
        }
        if (line.isNotEmpty()) {
            card.addView(tv(line.toString(), 11f, R.color.accent).apply { setPadding(0, dp(8), 0, 0) })
        }

        // ---- 操作：加账号 / 立即切换 ----
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(matchParent, wrapContent).apply { topMargin = dp(10) }
        }
        actions.addView(
            actionLabel(getString(R.string.accounts_add_account)) { addAccountDialog(p) },
            LinearLayout.LayoutParams(0, wrapContent, 1f),
        )
        actions.addView(
            actionLabel(getString(R.string.accounts_switch_now)) { switchNow(p) },
            LinearLayout.LayoutParams(0, wrapContent, 1f),
        )
        card.addView(actions)

        return card
    }

    private fun accountRow(
        p: MultiAccountStore.Provider,
        a: MultiAccountStore.Account,
        rt: MultiAccountStore.Runtime?,
        now: Long,
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }

        val coolingUntil = rt?.failures?.get(a.id) ?: 0L
        val label = StringBuilder(a.label)
        label.append("  ").append(MultiAccountStore.mask(a.key))
        if (coolingUntil > now) label.append("  ").append(getString(R.string.accounts_cooldown))
        if (!a.enabled) label.append("  ·  " + getString(R.string.accounts_mode_off))

        val radio = RadioButton(this).apply {
            text = label.toString()
            textSize = 13f
            setTextColor(getColor(if (a.enabled) R.color.text_primary else R.color.text_muted))
            isChecked = p.effective()?.id == a.id
            setPadding(0, 0, 0, 0)
            setOnClickListener {
                update(p.id) { it.copy(activeId = a.id) }
            }
        }
        row.addView(radio, LinearLayout.LayoutParams(0, wrapContent, 1f))

        val sw = Switch(this).apply {
            isChecked = a.enabled
            setOnCheckedChangeListener { _, checked ->
                update(p.id) { cur ->
                    val list = cur.accounts.map { if (it.id == a.id) it.copy(enabled = checked) else it }
                    cur.copy(accounts = list)
                }
            }
        }
        row.addView(sw, LinearLayout.LayoutParams(wrapContent, wrapContent))

        row.addView(tv("×", 18f, R.color.text_muted).apply {
            setPadding(dp(12), 0, dp(2), 0)
            setOnClickListener { confirmDeleteAccount(p, a) }
        })

        return row
    }

    private fun actionLabel(text: String, onClick: () -> Unit): TextView =
        tv(text, 12f, R.color.accent, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(6))
            setOnClickListener { onClick() }
        }

    private fun reasonLabel(reason: String): String = when (reason) {
        "error" -> getString(R.string.accounts_reason_error)
        "round-robin" -> getString(R.string.accounts_reason_roundrobin)
        "boot" -> getString(R.string.accounts_reason_start)
        "manual", "config" -> getString(R.string.accounts_reason_manual)
        else -> reason
    }

    // ------------------------------------------------------------------ 变更

    private fun update(id: String, block: (MultiAccountStore.Provider) -> MultiAccountStore.Provider) {
        val i = providers.indexOfFirst { it.id == id }
        if (i < 0) return
        providers[i] = block(providers[i])
        MultiAccountStore.save(this, providers)
        reload()
    }

    /** 立即切到下一个启用账号（手动档也是"一键换人"）。 */
    private fun switchNow(p: MultiAccountStore.Provider) {
        val list = p.enabledAccounts
        if (list.size < 2) {
            toast(getString(R.string.accounts_need_two))
            return
        }
        val curId = p.effective()?.id
        val idx = list.indexOfFirst { it.id == curId }
        val next = list[(idx + 1).mod(list.size)]
        update(p.id) { it.copy(activeId = next.id) }
    }

    private fun addAccountDialog(p: MultiAccountStore.Provider) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
        }
        val name = EditText(this).apply {
            hint = getString(R.string.accounts_account_label)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val key = EditText(this).apply {
            hint = getString(R.string.accounts_key_label)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        box.addView(name)
        box.addView(key)

        AlertDialog.Builder(this)
            .setTitle(R.string.accounts_add_account)
            .setMessage(p.ref)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val k = key.text?.toString()?.trim().orEmpty()
                if (k.isEmpty()) {
                    toast(getString(R.string.accounts_invalid_key))
                    return@setPositiveButton
                }
                val fallback = getString(R.string.accounts_account_default, p.accounts.size + 1)
                val acc = MultiAccountStore.Account(
                    id = MultiAccountStore.newId(),
                    label = name.text?.toString()?.trim().orEmpty().ifEmpty { fallback },
                    key = k,
                    enabled = true,
                )
                update(p.id) { cur ->
                    cur.copy(
                        accounts = cur.accounts + acc,
                        activeId = if (cur.activeId.isEmpty()) acc.id else cur.activeId,
                    )
                }
                toast(getString(R.string.accounts_saved))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun addProviderDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
        }
        val known = MultiAccountStore.KNOWN
        val spinner = Spinner(this).apply {
            setPopupBackgroundDrawable(getDrawable(R.drawable.bg_spinner_popup))
            adapter = ArrayAdapter(
                this@AccountsActivity,
                R.layout.item_spinner_dark,
                known.map { it.label + "  ·  " + it.ref },
            )
        }
        val label = EditText(this).apply { hint = getString(R.string.accounts_label_hint) }
        val customRef = EditText(this).apply { hint = getString(R.string.accounts_ref_hint) }
        box.addView(spinner)
        box.addView(label)
        box.addView(customRef)

        AlertDialog.Builder(this)
            .setTitle(R.string.accounts_add_provider)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val pick = known.getOrNull(spinner.selectedItemPosition)
                val custom = customRef.text?.toString()?.trim().orEmpty()
                val ref = if (custom.isNotEmpty()) custom else pick?.ref.orEmpty()
                if (!MultiAccountStore.isRefName(ref)) {
                    toast(getString(R.string.accounts_ref_invalid))
                    return@setPositiveButton
                }
                if (providers.any { it.ref.equals(ref, ignoreCase = true) }) {
                    toast(getString(R.string.accounts_dup_ref))
                    return@setPositiveButton
                }
                val route = if (custom.isEmpty()) pick?.route.orEmpty() else ""
                val name = label.text?.toString()?.trim().orEmpty()
                    .ifEmpty { pick?.label ?: ref }
                providers.add(
                    MultiAccountStore.Provider(
                        id = MultiAccountStore.newId(),
                        label = name,
                        route = route,
                        ref = ref,
                        mode = MultiAccountStore.MODE_MANUAL,
                        cooldownMs = MultiAccountStore.DEFAULT_COOLDOWN_MS,
                        activeId = "",
                        accounts = emptyList(),
                    )
                )
                MultiAccountStore.save(this, providers)
                reload()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteAccount(p: MultiAccountStore.Provider, a: MultiAccountStore.Account) {
        AlertDialog.Builder(this)
            .setTitle(R.string.accounts_delete_account_title)
            .setMessage(getString(R.string.accounts_delete_body, a.label))
            .setPositiveButton(R.string.accounts_delete) { _, _ ->
                update(p.id) { cur ->
                    val list = cur.accounts.filter { it.id != a.id }
                    cur.copy(
                        accounts = list,
                        activeId = if (cur.activeId == a.id) (list.firstOrNull { it.enabled }?.id ?: "") else cur.activeId,
                    )
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteProvider(p: MultiAccountStore.Provider) {
        AlertDialog.Builder(this)
            .setTitle(R.string.accounts_delete_provider_title)
            .setMessage(getString(R.string.accounts_delete_body, p.label))
            .setPositiveButton(R.string.accounts_delete) { _, _ ->
                providers.removeAll { it.id == p.id }
                MultiAccountStore.save(this, providers)
                reload()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ 小工具

    private fun tv(text: String, size: Float, colorRes: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(getColor(colorRes))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
