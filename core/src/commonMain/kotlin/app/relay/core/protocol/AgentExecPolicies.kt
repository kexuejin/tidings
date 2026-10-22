// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.protocol

/**
 * **谁能触发本机 agent 执行** —— 只有两档，名单之外没有第三套规则。
 *
 * ## 为什么从四档收到两档
 *
 * 四档（只有我 / 可信设备 / 加群主管理员 / 群内任何人）表达的是**整体宽严**，
 * 但它和「逐设备勾选名单」是两套规则，会互相干扰：
 *
 * - 名单上没勾的人，档位却可能把他放进来（「群内任何人」）；
 * - 名单上勾了的人，档位收紧后又被挡在外面。
 *
 * 用户看到的是一个勾选框，实际生效的却是两套规则的交集 —— 这就是「界面在说假话」。
 *
 * 所以收敛成两档：
 *
 * - [LIST]（默认）：**只有名单里的设备**能执行。名单是唯一授权来源，一次一件事。
 * - [ANY_MEMBER]：群内任何成员都能执行。明确的逃生档，用于完全可信的群；
 *   选它意味着用户接受「进群 = 能在我机器上跑命令」。
 *
 * 群主 / 管理员**不再有结构性特权**（用户明确定的口径）—— 他们想执行，
 * 要么进名单，要么整个群切到 [ANY_MEMBER]。
 *
 * ## 默认是 [LIST]
 *
 * 装上就生效的行为必须是最严的那一侧，因为「执行」不可撤销。
 * 名单初始为空 ⇒ 除本机用户外没人能执行；加谁进来是**显式动作**。
 */
object AgentExecPolicies {

    /** 只有名单里的设备能执行。**默认档**。 */
    const val LIST = "list"

    /** 群内任何成员都能执行。明确的逃生档，选它 = 接受「进群即可执行」。 */
    const val ANY_MEMBER = "anyMember"

    val VALUES: Set<String> = setOf(LIST, ANY_MEMBER)

    /** 缺省 / 未知取值一律收到最严一档（fail-closed）。 */
    fun normalize(raw: String?): String {
        val v = raw?.trim()
        return if (v != null && v in VALUES) v else LIST
    }
}
