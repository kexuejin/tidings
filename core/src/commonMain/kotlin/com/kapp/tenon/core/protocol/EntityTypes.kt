// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

/**
 * 实体形态（`VirtualAgentMember.entityType`）取值域的**唯一**定义。
 *
 * ## 为什么要有这个对象
 *
 * `"single"` / `"team"` 这对字面量在本仓至少出现过五处：`VirtualAgentMember` 的默认值、
 * `CodecGroup` 的解码兜底、`isTeam` 的判定、群设置面板的展示分支、以及 agentNode 的
 * `PluginRegistration.TYPE_SINGLE/TYPE_TEAM`。取值域是多处各写一份清单，就是在制造
 * AGENTS §3 第 7 种缺陷（键对、值不在白名单里）的协议版：某一处改成 `"group"` 而另一处
 * 只认 `"team"`，发送侧一切正常、接收侧什么都没有。
 *
 * ## 未知值不拒帧（前向兼容优先）
 *
 * `group` 帧没有字段白名单（`CodecGroup.validateGroup` 刻意没有），`entityType` 是
 * **展示字段**不是动作指令：收到不认识的值时**解码不拒帧**，消费方用 [normalized]
 * 归一到 [SINGLE] —— 与「旧端忽略新键」同一条前向兼容策略。但**本端产出**只许这两个值：
 * 发送侧不产出注定被对方判坏的字段，是这个仓的第一不变量。
 *
 * agentNode 的 `PluginRegistration.TYPE_SINGLE/TYPE_TEAM` 引用这里的常量（同一值域
 * 不许两边各写一份）。
 */
object EntityTypes {
    /** 单兵（默认）：一个 agent 对外就是一个可 @ 的对象。 */
    const val SINGLE: String = "single"

    /** 协同团队：对外单一窗口 = leader 的 token，组员清单随 [VirtualAgentMember.teamMembers] 同步。 */
    const val TEAM: String = "team"

    /** 本端**允许产出**的全部值。 */
    val ALL: Set<String> = setOf(SINGLE, TEAM)

    /**
     * 未知值归一：认识的值原样返回，不认识（含 null）一律退回 [SINGLE]。
     *
     * 「归一到单兵」而不是「保持原样」是有意的：isTeam 的口径是
     * `entityType == TEAM || teamMembers 非空` —— 未知值如果原样保留，一条只带
     * `teamMembers` 的旧声明仍能被 isTeam 认出来（组员清单是事实）；而一个未知
     * entityType + 空组员清单的声明，按单兵展示是**如实的**降级，不是丢数据。
     */
    fun normalized(raw: String?): String = if (raw != null && raw in ALL) raw else SINGLE
}
