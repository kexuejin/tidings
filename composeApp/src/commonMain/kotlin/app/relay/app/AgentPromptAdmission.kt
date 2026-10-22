// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import app.relay.core.protocol.AgentGrantAccess
import app.relay.core.protocol.AgentGrantPolicies

/**
 * 「谁能把一句话注入到本机 DSH 会话」的准入判据。
 *
 * ## 为什么这必须先于「接通」做
 *
 * 接通注入之后，手机上一句话会让本机 agent **真的执行命令**（bash / 文件读写 / 代码搜索）。
 * 这比现在的「native 兜底只能回一句话」危险一个量级 —— 而且是**不可撤销**的：命令跑了就跑了。
 * 所以先把准入判据定死、测住，再接通道。
 *
 * ## 判据的三个维度
 *
 * 1. **消息本身该不该叫醒 agent** —— 判据只有一份：Tidings 侧的 `shouldTriggerAgent`。
 *    桥接侧**不许重算**（AGENTS §2.4：一个事实一个来源）。重算的后果是两边对「什么算 @」
 *    的理解慢慢分叉，最后出现「手机上显示已 @、宿主没执行」或反过来的静默。
 * 2. **有没有可投递的会话** —— 必须带 `agentSessionId`。没带就**不投**，
 *    而不是「猜一个最近的」。投错会话会让 agent 在无关上下文里执行，比不执行更糟。
 * 3. **发送方有没有下发任务的权限** —— 复用 `agr1` 的既有权模型。
 *    `ro`（只读）明确写着「不可下发任务或审批」，所以它必须被拒。
 *
 * ## 一条刻意的「不做」
 *
 * 这里**不看**群成员关系，也不看对方是不是好友。理由：群成员身份是**可达性**，不是**授权**。
 * 把可达性当授权，等于「进了群就能让我的机器执行命令」—— 那正是这套判据要挡住的东西。
 */
internal object AgentPromptAdmission {

    /** 准入结果。带原因而不是布尔量：拒绝时说得出「为什么」，才排得动。 */
    enum class Verdict {
        /** 可以投递。 */
        Allow,

        /** 这条消息本来就不该叫醒 agent（没 @、不是回复 agent 等）。 */
        NotAddressedToAgent,

        /** 没带会话归属：不知道投给哪个会话，拒投（不猜）。 */
        NoSession,

        /** 发送方只有只读权限：`ro` 不含「下发任务」。 */
        ReadOnlyGrant,

        /** 找不到任何授权记录：默认拒绝（fail-closed）。 */
        NoGrant,

        /**
         * 授权档还是默认的 `auto`：读取可以，**执行不行**。
         *
         * `auto` 的意思只是「已添加的节点都可见」，不是「都可在本机执行」。
         * 详情见 [admitForExecution]。
         */
        DefaultPolicyTooOpen,
    }

    /**
     * 判定一次注入。
     *
     * @param addressedToAgent Tidings 侧 `shouldTriggerAgent` 的结果，**原样传入，不重算**。
     * @param agentSessionId 消息携带的会话归属；null = 没带。
     * @param senderAccess 发送方对**本机 agent** 的访问档；null = 没有任何授权记录。
     */
    fun admit(
        addressedToAgent: Boolean,
        agentSessionId: String?,
        senderAccess: String?,
    ): Verdict {
        // 顺序有意义：先问「这条是不是给 agent 的」，再问「投哪里」，最后问「有没有权限」。
        // 反过来会把「没 @ 但也没权限」报成 ReadOnlyGrant，掩盖真正的原因。
        if (!addressedToAgent) return Verdict.NotAddressedToAgent
        if (agentSessionId.isNullOrBlank()) return Verdict.NoSession
        val access = senderAccess?.trim()?.lowercase()
        if (access.isNullOrBlank()) return Verdict.NoGrant
        // `ro` = 只能浏览、不能下发任务（见 AgentGrantAccess.RO 的 KDoc）。
        // 其余三档（rw / admin / owner）都含「下发任务」的能力。
        return if (access == AgentGrantAccess.RO) Verdict.ReadOnlyGrant else Verdict.Allow
    }

    /**
     * **执行面的加严判据**：能不能真的把这句话交给 `agent.followup()`。
     *
     * 与 [admit] 分开是刻意的，因为两者面对的风险不同：
     * - [admit] 回答「这条消息有没有资格谈注入」（会话、寻址、有没有授权条目）；
     * - 这个函数回答「**在当前的授权档位下**，允许它执行吗」。
     *
     * ## 为什么 `auto` 档必须被拦在注入之外
     *
     * `AgentGrantPolicies.AUTO` 是**默认档**，它的 KDoc 写着「任何已添加的节点可见全部 agent
     * （= 今天的零门控行为，零迁移）」。对**读取**（看工作区、看会话流、看产物）来说，
     * 这个默认是合理的 —— 可见性不该开箱就要求配置。
     *
     * 但**注入执行**是另一类动作：一句话会让宿主跑 bash、读写文件、搜代码。
     * 在 `auto` 下，任何一台**已配对**设备都能做到这件事 —— 而配对的门槛只是「扫过码」。
     * 把读取的默认档直接套到执行上，等于「扫过码就能在我机器上执行命令」。
     *
     * 所以注入只在**显式授权档**（byDevice / request）下放行：那时宿主明确配过白名单，
     * 或者逐台批过。默认档下不开放执行，用户要开就在界面上切一次档 —— 这是**显式同意**。
     *
     * ## 这条不是「再加一个设置开关」
     *
     * AGENTS §2.5 反对「用一个开关表达安全策略」。这里没有新增开关：
     * 复用的是**已有的** `agent-grant` 策略档（`agr1` 能力位，桌面端已能切换）。
     * 变的只是「注入这条路径要求哪一档」，而且要求比读取更高。
     *
     * @param policy 宿主当前授权档（`AgentGrantPolicies` 的取值）。
     * @param verdict [admit] 的结论。
     */
    fun admitForExecution(
        policy: String?,
        verdict: Verdict,
    ): Verdict {
        if (verdict != Verdict.Allow) return verdict
        // 默认档（含 null / 未知取值）一律不放行执行：未知不等于宽松。
        // **不要先把 policy lowercase 再比**：协议取值 `byDevice` 是驼峰（`AgentGrantPolicies.BY_DEVICE`），
        // 小写化之后就成了 `bydevice`，与常量永远不相等 —— 表现为「明明切到了按设备授权，注入仍被拒」。
        // 这是测试 `explicitPoliciesPermitExecution` 当场抓到的。
        return when (policy?.trim()) {
            AgentGrantPolicies.BY_DEVICE, AgentGrantPolicies.REQUEST -> Verdict.Allow
            else -> Verdict.DefaultPolicyTooOpen
        }
    }

    /** 这个结果能不能真的去调 `agent.followup()`。 */
    fun Verdict.permitsInjection(): Boolean = this == Verdict.Allow
}
