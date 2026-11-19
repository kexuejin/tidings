// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

/**
 * 逐底座登录态的**判定**：哪一家用什么探针、证据强度算几级。
 *
 * ## 为什么判定住在驱动库，而不是 UI 旁
 *
 * 「agy 只能靠真跑 `agy models` 拿到 STRONG」「opencode 的 `stored` 只证明存过、不许画绿灯」
 * 「codebuddy 的 auth session 路径哈希不可靠，只能报 UNKNOWN」——这些是**驱动知识**，
 * 与界面无关。以前它们住在编排层的映射里，于是想要同一份结论的第二个人（诊断面）
 * 只能复制一遍；复制出来的第二份迟早与第一份分叉，而症状是「界面说已登录、doctor 说未判定」。
 *
 * 编排层现在只做两件事：把这里的三态折成 UI 枚举、按 key 选引导文案。
 *
 * ## 三态 + 证据强度是两个维度
 *
 * 弱证据（文件/env 在位）只能用于**快败提示**，不能用于放行绿灯；强证据（真跑厂家子命令）
 * 才配说「已登录 / 未登录」。任何把两维压成一个 Boolean 的写法都会把判据抹掉。
 */
object DriverAuthJudgement {

    /**
     * 一家驱动的**原始两维**快照。
     *
     * @param agyState agy 的真跑探针结果；null = 探针没跑成（未安装 / 抛异常）
     * @param zcodeSettingPath zcode `setting.json` 绝对路径；空串 = 读不到（按「没选过模型」判）
     * @param cliAuthState codex / claude / opencode 的只读 status 查询结果；null = 没跑成
     */
    suspend fun snapshotOf(
        key: String,
        env: Map<String, String>,
        agyState: suspend () -> DriverAuthProbes.State?,
        zcodeSettingPath: String,
        cliAuthState: suspend (String) -> DriverAuthProbes.State?,
    ): DriverAuthProbes.Snapshot {
        val declared = CliDriverSpecs.authProbeOf(key)
        return when {
            // 真跑一次厂家的只读子命令，按「退出码 / 未登录签名」判（CLI_MARKER）。判据是**该家**
            // 的声明（DriverAuthProbes.classify 按 key 取），但执行器目前只有 agy 一台——超时、
            // 线程泵、登录态记忆与 invalidate 都在 AgyAuthProbe 里。守卫钉住「CLI_MARKER 只有
            // agy」：新增第二家必须先把执行器抽象出来，否则这里会拿 agy 的探针去跑别人的命令。
            declared?.kind == CliDriverSpecs.AuthProbeKind.CLI_MARKER ->
                DriverAuthProbes.strongSnapshot(key, agyState())
                    ?: DriverAuthProbes.Snapshot(key, DriverAuthProbes.State.UNKNOWN, DriverAuthProbes.Evidence.STRONG)
            // 凭证物位置完全已知（env key + 凭证文件）⇒ 两处都没有才判「确定未登录」（FILE）。
            // 判据来自声明，新增 FILE 驱动自动生效；从前这里写死 `key == "dsh"`，新驱动会静默
            // 掉进兜底分支变成「永远未判定」。
            declared?.kind == CliDriverSpecs.AuthProbeKind.FILE ->
                DriverAuthProbes.hintSnapshot(key, DriverAuthProbes.hasDeclaredCredential(env, declared))
            // 能不能跑厂家只读子命令，由 JSON 的 authProbe.kind 决定（CLI_CMD）——新增一家
            // CLI_CMD 会自动落到这里，不会静默掉进兜底分支变成「永远未判定」。
            // 探针没跑成（null）仍降级 UNKNOWN，不把「读不到」说成「没有」。
            //
            // 这条必须排在 zcode 的 bespoke 分支**之前**：zcode 今天声明 NONE（supports 为假）
            // 所以顺序不影响结果；但 JSON 一旦改成 CLI_CMD，若 bespoke 分支先命中，新声明就
            // 静默失效了——而守卫是单向的（kind==CLI_CMD ⇒ supports 为真），不会失败。
            CliAuthProbes.supports(key) -> {
                val state = cliAuthState(key)
                val hintOnly = declared?.authenticatedIsHint == true
                when {
                    state == null -> DriverAuthProbes.Snapshot(
                        key, DriverAuthProbes.State.UNKNOWN, DriverAuthProbes.Evidence.HINT,
                    )
                    // 声明了 authenticatedIsHint 的探针（opencode 的 stored）不画绿灯。
                    hintOnly && state == DriverAuthProbes.State.AUTHENTICATED ->
                        DriverAuthProbes.Snapshot(
                            key, DriverAuthProbes.State.UNKNOWN, DriverAuthProbes.Evidence.HINT,
                        )
                    else -> DriverAuthProbes.Snapshot(key, state, DriverAuthProbes.Evidence.STRONG)
                }
            }
            // zcode 声明为 NONE（不跑子命令），但它的「应用里选过模型」弱信号是 `setting.json`
            // 的扫描机制，投影表达不了（JSON 的 note 已交代）。这是唯一一条 bespoke 分支：
            // 新增驱动若也有 bespoke 弱信号，别在这里继续加 if——先给它一个 kind。
            key == "zcode" -> DriverAuthProbes.hintSnapshot(
                key,
                DriverAuthProbes.zcodeHasModelSelected(zcodeSettingPath),
            )
            // codebuddy 的 auth session 路径哈希不可靠，且 `-p` 探测会触发真实派单
            // （本机实测报 Authentication required，不能当只读探针用）⇒ 只报 UNKNOWN。
            else -> DriverAuthProbes.Snapshot(key, DriverAuthProbes.State.UNKNOWN, DriverAuthProbes.Evidence.HINT)
        }
    }

    /**
     * 一次性问遍的默认装配：agy 真跑、三家跑厂家 status 子命令、zcode 读 `setting.json`。
     *
     * 给**短命诊断进程**用（`DriverDoctor`）：每次新建 agy 探针，不共享宿主装配面的记忆。
     * 长期驻留的装配面自己持有探针实例（登录成功后不再复探），那边继续注入自己的实例。
     */
    suspend fun snapshotWithDefaults(
        key: String,
        env: Map<String, String> = System.getenv(),
    ): DriverAuthProbes.Snapshot {
        // 用传入的 env 走发现器（与下面各家 resolveFor(k, env) 同一口径）：诊断进程合成的
        // env 必须决定 agy 用哪个可执行文件，不能回落到裸 "agy" 而读诊断进程自己的 PATH。
        val agy = AgyAuthProbe(command = AgyAuthProbe.resolvedCommand(env))
        val runner = CliAuthProbes.jvmRunner()
        return snapshotOf(
            key = key,
            env = env,
            agyState = { agyStateOf(agy) },
            zcodeSettingPath = CliDiscovery.zcodeSettingPathOf(env),
            cliAuthState = { k -> cliAuthStateOf(k, CliDiscovery.resolveFor(k, env), runner) },
        )
    }

    /**
     * agy 探针 → 三态；探针抛（未安装时 `start()` 会）⇒ null。
     *
     * 落地成 UNKNOWN 而不是「未判定之外的某个结论」：读不到 ≠ 没登录。
     */
    suspend fun agyStateOf(probe: AgyAuthProbe): DriverAuthProbes.State? =
        runCatching {
            when (probe.probe()) {
                AgyAuthProbe.State.AUTHENTICATED -> DriverAuthProbes.State.AUTHENTICATED
                AgyAuthProbe.State.NOT_AUTHENTICATED -> DriverAuthProbes.State.NOT_AUTHENTICATED
                AgyAuthProbe.State.UNKNOWN -> DriverAuthProbes.State.UNKNOWN
            }
        }.getOrNull()

    /**
     * 厂家只读 status 查询 → 三态；`UNKNOWN` 折成 null。
     *
     * 这条不对称是故意的：「探针跑不成」与「跑成了但判不出」在映射层要走不同的降级，
     * 在这里提前压成一个值就再也分不开了。
     */
    suspend fun cliAuthStateOf(
        key: String,
        cliPath: String?,
        runner: suspend (List<String>) -> CliAuthProbes.Output,
    ): DriverAuthProbes.State? = when (CliAuthProbes.probe(key, cliPath, runner)) {
        CliAuthProbes.Verdict.LOGGED_IN -> DriverAuthProbes.State.AUTHENTICATED
        CliAuthProbes.Verdict.LOGGED_OUT -> DriverAuthProbes.State.NOT_AUTHENTICATED
        CliAuthProbes.Verdict.UNKNOWN -> null
    }
}
