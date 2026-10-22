// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.protocol

/**
 * 「谁的话可以让本机 agent 真的执行」的判据。
 *
 * ## 为什么需要它
 *
 * 注入接通之后，群里一句 `@dsh xxx` 会让宿主**真的跑命令**（bash / 读写文件 / 搜代码）。
 * 而群成员身份只是**可达性**，不是授权 —— 把可达性当授权，等于「进了群就能在我机器上执行」。
 *
 * ## 判据：只有两类人默认可以，其余一律要人工批过
 *
 * **默认可以**（不需要任何批准）：
 * 1. **agent 自己的节点用户** —— 那台机器的主人。这不是「额外授权」，是「不必再问一次」；
 *    他本来就能在自己机器上执行。
 * 2. **台账里已批准的设备** —— 就是「可信设备」面板点过按钮的那台（`isMyDevice` / `OWNER` / `RW`）。
 *
 * **默认不可以**（必须已在台账里）：
 * - **群主**（`ownerId`）；
 * - **管理员**（`adminIds`）；
 * - 普通成员。
 *
 * ## 为什么群主/管理员不默认放行（用户明确定的口径）
 *
 * 「群主 / 管理员的确不能默认批准。」
 *
 * 理由：**群主可能不是这台机器的主人**。他建了这个群，不等于他能命令我的机器；
 * 而 `AgentGrantStore.canApprove` 的既有立场也是「`OWNER` 由『批准为我的设备』这个**人工动作**
 * 产生」，不是由群角色产生。让群角色直接换到执行权，等于绕过可信设备面板那一层。
 *
 * 出口不是没有：本机用户可以在可信设备面板里把他批成可信设备。
 * 「有资格申请，但仍需我点头」—— 这才是那条面板存在的意义。
 *
 * ## 一条刻意的从严：私聊不在此判据内
 *
 * 一对一私聊没有群角色可言，那边走 `GrantUi` 的三级回退（整台设备 / agent / 工作区）。
 * 本判据只回答**群里**的问题，两者不要混 —— 混了就会出现
 * 「跟某人是好友，于是他在群里也能让我执行」。
 */
object AgentExecAuthorizer {

    /** 判定结果。带原因而不是布尔量：拒绝时说得出为什么，用户才知道找谁批。 */
    enum class Verdict {
        /** agent 自己的节点用户（那台机器的主人）。 */
        AgentHost,

        /** 台账里已批准的设备（可信设备面板批过）。 */
        GrantedDevice,

        /** 群内任意成员 —— 仅在 anyMember 档下给出。 */
        AnyMemberGranted,

        /** 群主，但宿主没放开这一档。 */
        OwnerNeedsGrant,

        /** 管理员，但宿主没放开这一档。 */
        AdminNeedsGrant,

        /** 只是群成员，没有任何执行授权。 */
        MemberOnly,

        /** 连成员都不是（外部注入 / 伪造）。 */
        NotAMember,
    }

    /**
     * 判一次执行授权。
     *
     * @param senderId 真正的说话者（群里取 `envelope.from`，**不是**会话键）。
     * @param group 该群元数据；null = 不是群聊（本判据不适用，fail-closed）。
     * @param grantAllows 台账查询：这个节点是不是已批准的可信设备。
     *   null = 没有台账可用 ⇒ 只有 agent 宿主用户能执行。
     */
    fun authorize(
        senderId: String?,
        group: GroupMeta?,
        /** 台账查询：这个节点对该 **agent** 有没有执行权（带 agentKey 才判得准）。 */
        grantAllows: ((String) -> Boolean)? = null,
        /**
         * 这个 **agent** 的执行范围（[AgentExecPolicies] 的取值）。
         *
         * **按 agent，不按宿主**：一台机器可以同时装 dsh 与 codex，
         * 「允许群主用 dsh」与「允许群主用 codex」是两个问题 —— 宿主级一份配置做不到这件事。
         *
         * 缺省（null / 未知取值 / 这台机器上没配过这个 agent）
         * ⇒ 最严一档 [AgentExecPolicies.LIST]。
         */
        policy: String? = null,
        /**
         * 本机 nodeId；null = 调用方不知道「我是谁」。
         *
         * 为什么需要它：本机用户在群里发指令时，发送者 id 就是他自己的 nodeId ——
         * 而**他不是「agent 宿主」**（`virtualAgents` 只记托管 agent 的那台设备）。
         * 没有这一档，「我在自己机器上让自己的 agent 干活」会被判成没授权。
         */
        selfNodeId: String? = null,
    ): Verdict {
        val scope = AgentExecPolicies.normalize(policy)
        val sender = senderId?.trim()?.takeIf { it.isNotEmpty() }
            ?: return Verdict.NotAMember
        if (group == null) {
            // 非群聊：不在本判据范围内。返回 NotAMember 而不是 Allow，是为了**fail-closed** ——
            // 用错地方时不会静默放行。
            return Verdict.NotAMember
        }
        // **成员身份是永远的前置门槛**：即使 policy = ANY_MEMBER，也要求发送者在成员表里。
        // 允许非成员执行会让「配对过」直接等于「能执行」—— 那是把信任根从群降到一台设备。
        if (sender !in group.members) return Verdict.NotAMember
        // ① **本机用户**：就在这台机器上操作的人，任何档位下都免问。
        //    这不是「授权」，是「他本来就在这里」。
        //    与下一条分开：`virtualAgents` 记的是**托管 agent 的那台设备**，
        //    而本机用户可能在群里用自己的 nodeId 说话（那时他不是 agent 宿主）。
        val self = selfNodeId?.trim()?.takeIf { it.isNotEmpty() }
        if (self != null && sender == self) return Verdict.AgentHost
        // ② agent 自己的节点用户：那台机器的主人，**任何档位下都免问**。
        //    这不是「策略放宽」，是「本来就在自己机器上」。
        if (group.virtualAgents?.any { it.hostNodeId == sender } == true) return Verdict.AgentHost
        // ③ 名单里的设备（`grantAllows` 查的就是 `scope=AGENT` + 本 agentKey 的授权）。
        //    这是 [AgentExecPolicies.LIST] 档下**唯一**的授权来源。
        if (grantAllows?.invoke(sender) == true) return Verdict.GrantedDevice
        // ④ 逃生档：群内任何成员。
        if (scope == AgentExecPolicies.ANY_MEMBER) return Verdict.AnyMemberGranted
        // ⑤ 不放行。群主/管理员各自一档，只为告诉他们「你差的是进名单」——
        //    他们**没有结构性特权**，这是用户明确定的口径。
        if (group.ownerId != null && group.ownerId == sender) return Verdict.OwnerNeedsGrant
        if (group.adminIds?.contains(sender) == true) return Verdict.AdminNeedsGrant
        return Verdict.MemberOnly
    }

    /** 这一档能不能真的执行。**只有两档可以。** */
    fun Verdict.permitsExecution(): Boolean = this in PERMITTING

    /** 允许执行的全部档位。集中一处，防止将来「顺手」再多放行一档。 */
    private val PERMITTING: Set<Verdict> = setOf(
        Verdict.AgentHost,
        Verdict.GrantedDevice,
        Verdict.AnyMemberGranted,
    )
}
