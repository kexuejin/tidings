// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

/**
 * 子进程环境的**唯一装配点**。
 *
 * ## 它管的是「顺序」，不是「取哪几只键」
 *
 * 取哪几只是 [CliProviderEnv] 的事（凭证注入）；这里管的是那几只在最终环境里**落在谁头上**：
 * `PWD` 会不会被显式注入盖掉、`PATH` 补齐读到的是宿主 HOME 还是沙箱 HOME、派单 sessionId
 * 能不能被配置层同名变量顶掉。这几条以前在两个地方各写一遍（裸驱动与沙箱执行器），
 * 顺序一旦漂开，症状是「沙箱里能跑、裸路径 127」或「审批拿不到 sessionId 而只读约束
 * 静默失效」——都没有任何一处会报错。
 *
 * ## 继承是刻意的，不是漏了 `clear()`
 *
 * 子进程拿到的是**宿主全量环境**再加下面这些覆盖。这不是疏忽：
 *
 * - 智能体要在 worktree 里跑 `git commit` / `git push`，那需要 `SSH_AUTH_SOCK`、
 *   `GIT_*`、`HOME` 里的 git 配置；`clear()` 之后这些一律消失，症状是「命令跑通了、
 *   推不上去」，且 CLI 只报一句它自己的话。
 * - `LANG`/`LC_ALL`、`TMPDIR`、`USER`/`SHELL` 同样被各家运行时悄悄依赖（编码、临时目录、
 *   某些 SDK 的凭据缓存路径）。缺任一只都不报「缺环境变量」，只报风马牛不相及的错。
 *
 * 所以隔离边界不在这张表上：它是**沙箱工作目录**（`pb.directory(worktree)`）+ 沙箱分支的
 * **HOME 交换**（[WorktreeSandboxExecutor.prepareSandboxHome] 只链凭证目录）+
 * `TENON_OS_SANDBOX=strict` 时的 OS 级沙箱。环境继承的残留风险是「宿主 env 里的第三方
 * 密钥对子进程可见」——处置在宿主侧（自身环境最小化、需要更硬的隔离就开 strict），
 * 不在这里用一张永远列不全的 deny 表去猜。
 */
object CliChildEnv {

    /** 宿主中介 MCP 工具的工作区边界：显式传沙箱 worktree，绝不靠进程 cwd 猜。 */
    const val ENV_MCP_WORKSPACE: String = "TENON_MCP_WORKSPACE"

    /**
     * 继承模式：宿主全量环境 + 注入集。**这是安全语义，`driver doctor` 会照原样印出来**。
     *
     * 做成常量而不是只写在注释里：哪天有人真去做 `clear()`（比如为了让 env 变成可枚举的
     * 白名单），必须同时改这里，报告与真机行为才不会各说各话。
     */
    const val MODE_INHERIT: String = "inherit"

    /**
     * 一次装配的输入。
     *
     * [inherited] 通常就是 `ProcessBuilder.environment()`（宿主环境）；显式传进来是为了让
     * 结果可断言 —— 测试给一份合成环境就能看到「最终那只进程看到什么」，不必真的起进程。
     */
    data class Spec(
        val inherited: Map<String, String>,
        /** 显式注入（凭证 + 派单上下文的覆盖集）。白名单口径见 [CliProviderEnv]。 */
        val overrides: Map<String, String> = emptyMap(),
        /** 子进程真实 cwd；null = 不改 `PWD`（调用方没有设置工作目录）。 */
        val pwd: String? = null,
        /** 沙箱分支的中介工作区；裸路径为 null（本机自己的仓不需要中介）。 */
        val mcpWorkspace: String? = null,
        /** 沙箱 HOME；null = 不替换（裸路径、或调用方已显式注入 `HOME`）。 */
        val home: String? = null,
        /** 派单 sessionId（审批 / 只读策略的挂点）。永远最后写，不可被覆盖。 */
        val hookSessionId: String? = null,
    )

    /**
     * 算出子进程环境的全量快照。纯函数：同样输入恒得同样输出（不读 `System.getenv()`、
     * 不碰磁盘以外的状态，`childPath` 只查目录在不在）。
     *
     * 顺序即语义，逐条都有代价：
     *
     * 1. 继承宿主全量环境；
     * 2. `PWD` 跟随真实 cwd —— `directory()` 只改 getcwd，继承来的 `PWD` 仍指向宿主目录，
     *    而 opencode 是按 `$PWD` 解析工作区的（不改它，产物写进宿主仓库根）；
     * 3. 显式注入覆盖 1–2（装配处的意图赢过继承）；
     * 4. `PATH` 补齐 —— **必须在 HOME 替换之前**：`childPath` 会去 `$HOME/.nvm`、`$HOME/.volta`
     *    找 node，先换 HOME 就找不到（沙箱 HOME 只链了凭证目录），于是 `#!/usr/bin/env node`
     *    的 CLI 以 127 退出；
     * 5. `HOME` 替换 —— 只在显式注入没给 HOME 时（`useRealHome` 的驱动自己给了）；
     * 6. 派单 sessionId 最后写 —— 它是安全判据，配置层同名变量不许顶掉它。
     */
    fun compose(spec: Spec): Map<String, String> {
        val out = LinkedHashMap(spec.inherited)
        spec.pwd?.let { out["PWD"] = it }
        spec.mcpWorkspace?.let { out[ENV_MCP_WORKSPACE] = it }
        out.putAll(spec.overrides)
        if (spec.overrides["PATH"] == null) out["PATH"] = CliDiscovery.childPath(out)
        if (spec.overrides["HOME"] == null) spec.home?.let { out["HOME"] = it }
        spec.hookSessionId?.let { out[DriverKeys.ENV_HOOK_SESSION_ID] = it }
        return out
    }

    /**
     * 把 [Spec] 落到真进程上：`inherited` 取进程当前环境，再把算出来的全量快照写回去。
     *
     * 返回算出来的快照，方便调用方/测试在不重算的情况下断言「那只进程会看到什么」
     * （与 [compose] 逐字一致，不存在第二份判据）。
     */
    fun applyTo(pb: ProcessBuilder, spec: Spec): Map<String, String> {
        val composed = compose(spec.copy(inherited = pb.environment()))
        pb.environment().putAll(composed)
        return composed
    }
}
