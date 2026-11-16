// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.drivers.DshCredentials
import com.kapp.tenon.agent.drivers.DriverAuthProbes
import com.kapp.tenon.agent.drivers.DriverKeys
import com.kapp.tenon.domain.DriverAuthEvidenceUi
import com.kapp.tenon.domain.DriverAuthRemediationUi
import com.kapp.tenon.domain.DriverAuthStateEntryUi
import com.kapp.tenon.domain.DriverAuthStateUi

/**
 * 「在位驱动 id 集合 → 逐底座登录态」的唯一映射（装配期纯逻辑，可合成输入测试）。
 *
 * ## 为什么把判定强度写死在各分支
 *
 * 弱探针（文件 / env 在位）**不能**证明认证成功，也不能在「我们没覆盖全部凭证位置」时
 * 证明未登录。所以：
 *
 * - 只有 [DriverKeys.AGY] 真跑 `agy models`，拿得到 STRONG 的三态；
 * - dsh 的凭证位置是完整已知的（凭证文件 + env key），两处都没有才判 NOT_AUTHENTICATED；
 * - zcode 的核心前置「应用里选了哪个模型」持久化在 `setting.json`，位置固定 ⇒ 选不出模型
 *   （对象为空/缺键/文件不在）可判 NOT_AUTHENTICATED；选过仍是 HINT/UNKNOWN，不画绿灯；
 * - codebuddy 的 auth session 文件名经过哈希、路径不可靠 ⇒ env/文件缺失时**一律 UNKNOWN**，
 *   只给引导，不冒充「确定未登录」。把「读不到」说成「没有」正是本仓最贵的那类静默缺陷。
 *
 * [remediationOf] 是引导类别的唯一词表：UI 只认这个枚举选文案，不按驱动名另写 if 链。
 */
internal object DriverAuthStateMapping {

    /** 这些 key 的登录态需要出现在界面上；其余（native 等）不进 map。 */
    private val REPORTABLE = setOf(
        DriverKeys.AGY,
        "claude", "codex", "opencode", "dsh", "codebuddy", "zcode",
    )

    /**
     * @param zcodeSettingPath zcode `setting.json` 绝对路径（装配层按存储目录推导）；
     *   传空串 = 读不到该文件，按「没选过模型」判 NOT_AUTHENTICATED。
     * @param cliAuthState codex / claude / opencode 的只读 status 查询结果；null = 探针
     *   没跑成（命令不存在 / 超时 / 解析不了）⇒ UNKNOWN，不冒充「确定未登录」。
     *   装配件把 `CliAuthProbes.Verdict` 转成这里的三态，映射层不碰子进程。
     */
    suspend fun map(
        inServiceDriverIds: Set<String>,
        env: Map<String, String>,
        agyState: suspend () -> DriverAuthProbes.State?,
        zcodeSettingPath: String = "",
        cliAuthState: suspend (String) -> DriverAuthProbes.State? = { null },
    ): Map<String, DriverAuthStateEntryUi> {
        val keys = inServiceDriverIds
            .map(DriverKeys::stripCliSuffix)
            .mapNotNull(DriverKeys::canonicalKey)
            .distinct()
            .filter { it in REPORTABLE }
        if (keys.isEmpty()) return emptyMap()

        val out = LinkedHashMap<String, DriverAuthStateEntryUi>()
        // 固定顺序：inService 是 Set，遍历顺序不稳会让界面行序每次重组都跳。
        for (key in REPORTABLE) {
            if (key !in keys) continue
            val probe = probeFor(key, env, agyState, zcodeSettingPath, cliAuthState)
            out[key] = DriverAuthStateEntryUi(
                driverKey = key,
                state = toUi(probe.state),
                evidence = when (probe.evidence) {
                    DriverAuthProbes.Evidence.STRONG -> DriverAuthEvidenceUi.STRONG
                    DriverAuthProbes.Evidence.HINT -> DriverAuthEvidenceUi.HINT
                },
                remediation = remediationOf(key),
                remediationDetail = remediationDetailOf(key),
            )
        }
        return out
    }

    private suspend fun probeFor(
        key: String,
        env: Map<String, String>,
        agyState: suspend () -> DriverAuthProbes.State?,
        zcodeSettingPath: String,
        cliAuthState: suspend (String) -> DriverAuthProbes.State?,
    ): DriverAuthProbes.Snapshot = when (key) {
        DriverKeys.AGY ->
            DriverAuthProbes.agySnapshot(agyState())
                ?: DriverAuthProbes.Snapshot(key, DriverAuthProbes.State.UNKNOWN, DriverAuthProbes.Evidence.STRONG)
        "dsh" -> DriverAuthProbes.hintSnapshot(
            key,
            DriverAuthProbes.hasCredential(env, DshCredentials.ENV_KEY) ||
                DshCredentials.hasCredentialFile(env),
        )
        "zcode" -> DriverAuthProbes.hintSnapshot(
            key,
            DriverAuthProbes.zcodeHasModelSelected(zcodeSettingPath),
        )
        // 三家有厂家自己的只读 status 子命令（实测可用）⇒ 真跑，给确定的三态。
        // 探针没跑成（null）仍降级 UNKNOWN，不把「读不到」说成「没有」。
        "claude", "codex", "opencode" -> {
            val state = cliAuthState(key)
            when {
                state == null -> DriverAuthProbes.Snapshot(
                    key, DriverAuthProbes.State.UNKNOWN, DriverAuthProbes.Evidence.HINT,
                )
                // opencode 的 `stored` 只证明存过凭证，不保证还有效 ⇒ 不许画绿灯。
                key == "opencode" && state == DriverAuthProbes.State.AUTHENTICATED ->
                    DriverAuthProbes.Snapshot(
                        key, DriverAuthProbes.State.UNKNOWN, DriverAuthProbes.Evidence.HINT,
                    )
                else -> DriverAuthProbes.Snapshot(key, state, DriverAuthProbes.Evidence.STRONG)
            }
        }
        // codebuddy 的 auth session 路径哈希不可靠，且 `-p` 探测会触发真实派单
        // （本机实测报 Authentication required，不能当只读探针用）⇒ 只报 UNKNOWN。
        "codebuddy" ->
            DriverAuthProbes.Snapshot(key, DriverAuthProbes.State.UNKNOWN, DriverAuthProbes.Evidence.HINT)
        else -> DriverAuthProbes.Snapshot(key, DriverAuthProbes.State.UNKNOWN, DriverAuthProbes.Evidence.HINT)
    }

    /** 引导类别的唯一词表（UI 侧只按枚举选本地化文案）。 */
    fun remediationOf(key: String): DriverAuthRemediationUi = when (key) {
        DriverKeys.AGY, "codebuddy", "claude", "codex", "opencode" -> DriverAuthRemediationUi.TERMINAL_LOGIN
        "dsh" -> DriverAuthRemediationUi.TOKEN_ENV
        "zcode" -> DriverAuthRemediationUi.APP_MODEL
        else -> DriverAuthRemediationUi.NONE
    }

    /**
     * 每家驱动**确切**的补凭证动作（登录子命令 / 环境变量名），与 [remediationOf]
     * 一一对应：前者给「哪一类动作」，这里给「敲哪一条」。
     *
     * 命令来自各家 CLI 自身帮助的实测：codex `login`、claude `auth`、opencode `auth`
     * 都是真子命令；agy / codebuddy 没有 login 子命令，靠首次交互会话完成浏览器登录，
     * 所以给可执行程序名本身；dsh / zcode 走环境变量（zcode 也可在应用里选模型）。
     * 只在这里写一次，UI 直接把值填进文案模板 —— 在 UI 再写一遍驱动名 if 链必然分叉。
     */
    fun remediationDetailOf(key: String): String = when (key) {
        DriverKeys.AGY -> "agy"
        "claude" -> "claude auth"
        "codex" -> "codex login"
        "opencode" -> "opencode auth"
        "codebuddy" -> "codebuddy"
        "dsh" -> DshCredentials.ENV_KEY
        "zcode" -> "ZCODE_API_KEY"
        else -> ""
    }

    /** domain 三态 ↔ 探针三态的纯映射，供测试与装配共用一份口径。 */
    fun toUi(state: DriverAuthProbes.State): DriverAuthStateUi = when (state) {
        DriverAuthProbes.State.AUTHENTICATED -> DriverAuthStateUi.AUTHENTICATED
        DriverAuthProbes.State.NOT_AUTHENTICATED -> DriverAuthStateUi.NOT_AUTHENTICATED
        DriverAuthProbes.State.UNKNOWN -> DriverAuthStateUi.UNKNOWN
    }
}
