// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

/**
 * L3 高危操作**租约屏障**的纯判定件（设计计划书 §八.2 与 §九.2「高危操作租约屏障」）。
 *
 * 为什么单独一个文件：fail-closed 的判定一旦混进编排器的接线就没法独立测 ——
 * 而这一条恰恰是全链路里唯一「不该在真实时间里等 60 秒才能验」的判定。这里全部
 * 依赖**注入时钟**（与 [SandboxIdleWatchdog] 同一模式）：测试拨动毫秒数即可推进
 * 「审批迟到 60 秒」，一个真实 sleep 都不需要。
 *
 * 职责分界（宿主接线由编排层统一做，本件不碰任何进程）：
 * - 词表与命中判定（[HighRiskCommands]）：哪些命令串必须先拿租约；
 * - 租约时效判定（[LeaseBarrier]）：宿主对高危动作签发 [LeaseToken]，手机端的
 *   审批在 TTL 内到达是 GRANTED；超过 TTL 才到是 EXPIRED（fail-closed：沙箱执行器
 *   拒绝执行并销毁现场）；审批还没回来是 PENDING（继续扣住不放）。
 */

/**
 * 高危命令词表与命中判定。
 *
 * 词表是**可测常量**：调用方可以读它生成审批提示文案，测试逐条钉边界。三类动作
 * 对应计划书 §八.2 L3 的清单 —— 破坏性删除、配置重置、网络穿透类。
 */
object HighRiskCommands {

    /** 词表：命中判定的唯一来源（取值域只许有一份定义，AGENTS 协议纪律）。 */
    val LIST: List<String> = listOf(
        // 破坏性删除：递归强删（常见变体一并收进来，挡在执行之前）。
        "rm -rf", "rm -fr", "rm -r -f", "rm -f -r",
        // 配置重置：把工作成果/库表/磁盘打回出厂的形态。
        "git reset --hard", "git clean -fd",
        "drop table", "truncate table",
        "mkfs",
        // 网络穿透类：把内网面暴露出去或从外面拉隧道进来。
        "ssh -r", "ngrok", "frpc", "cloudflared tunnel", "portproxy", "nat traversal",
    )

    /** 一次判定的结论：命中（要租约）或放行（不走 L3 屏障）。 */
    enum class Verdict { HIGH_RISK, SAFE }

    /**
     * 命令串是否命中高危词表。
     *
     * 边界口径（每条都有测试钉着）：
     * - **整词命中**：`rm -rf` 命中；`rm -rfx` 不命中 —— 词表词的尾部必须贴着
     *   分隔（空白或串尾），防「前缀同形的安全词」被误伤；
     * - **参数中缀命中**：`xargs rm -rf` 命中 —— 词表词出现在参数位也算，
     *   危险不因为前面垫了别的词而消失；
     * - **大小写不敏感**：`RM -RF` 命中 —— 删除不区分大小写，屏障也不该区分；
     * - **邻近对判定**：多 token 词表词要求相邻出现，`rm -f x -rf` 不命中 ——
     *   那是另一条命令的形状，不是递归强删。
     */
    fun classify(command: String): Verdict {
        val tokens = command.lowercase().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        for (entry in LIST) {
            val wanted = entry.lowercase().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (wanted.isEmpty()) continue
            for (start in 0..tokens.size - wanted.size) {
                var hit = true
                for (offset in wanted.indices) {
                    if (tokens[start + offset] != wanted[offset]) {
                        hit = false
                        break
                    }
                }
                if (hit) return Verdict.HIGH_RISK
            }
        }
        return Verdict.SAFE
    }
}

/** 一次高危租约签发的事实：宿主把高危命令拦下时发。 */
data class LeaseToken(
    /** 宿主给这次签发的编号（审批回执按它对上号）。 */
    val leaseId: String,
    /** 被拦下的高危命令串（原样保存，审批卡片上展示的就是它）。 */
    val command: String,
    /** 签发时刻（毫秒墙钟；由注入时钟给出，判定件不自取）。 */
    val issuedAtMs: Long,
    /** 时效（毫秒）：超过它到达的审批一律作废。 */
    val ttlMs: Long,
)

/**
 * 租约时效的**三态判定**。无状态：判定只依赖「签发事实 + 审批到达时刻」，
 * 时序由调用方持有，本类不跑任何线程 —— 与 [SandboxIdleWatchdog] 一样，
 * 时间走注入时钟。
 */
object LeaseBarrier {

    /** TTL 默认 60 秒（§八.2 的时效倒计时；宿主接线用它，测试另给短值）。 */
    const val DEFAULT_TTL_MS: Long = 60_000L

    enum class Verdict {
        /** 审批还没到：继续等，沙箱执行器必须扣住不放。 */
        PENDING,

        /** 审批在 TTL 内到达：放行。 */
        GRANTED,

        /** 审批超过 TTL 才到：作废（fail-closed），沙箱执行器拒绝执行并销毁现场。 */
        EXPIRED,
    }

    /**
     * 审批在 [arrivedAtMs] 到达时对 [token] 的裁决。
     *
     * 严格大于 TTL 才算过期：恰在 TTL 边界到达的审批仍在租约内 ——「60 秒内有效」
     * 按含端点读，把卡点到达的审批判成 EXPIRED 是误杀。
     */
    fun evaluate(token: LeaseToken, arrivedAtMs: Long): Verdict = when {
        arrivedAtMs < token.issuedAtMs -> Verdict.EXPIRED
        arrivedAtMs - token.issuedAtMs <= token.ttlMs -> Verdict.GRANTED
        else -> Verdict.EXPIRED
    }

    /**
     * 审批未到时，[nowMs] 时刻租约是否已倒计时归零。宿主签发后按拍判定：
     * true 就把扣着的动作就地作废 —— 不等那条可能永远不来的审批。
     */
    fun isTimedOut(token: LeaseToken, nowMs: Long): Boolean = nowMs - token.issuedAtMs > token.ttlMs
}
