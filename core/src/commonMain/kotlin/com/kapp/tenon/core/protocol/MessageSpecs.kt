// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

/**
 * 一种已知帧的**处置规格**。34 种帧各一行，集中住在 [MessageSpecs.all]。
 *
 * 这是四张手写集合（[MsgTypes.KNOWN] / [MsgTypes.RELIABLE] / [MsgTypes.HANDLED] /
 * [MsgTypes.UNHANDLED]）收敛后的唯一事实来源：那四个 `val` 全部由 [MessageSpecs.all]
 * 派生，新增一种帧只需在表里加一行、如实填好三个位，不必再同步改四张表。
 *
 * - [known]：本实现认不认得这帧（解码后的 `known` 标志；本批次 34 项全为 true）。
 * - [reliable]：是否必须回 ACK、进可靠投递队列。
 * - [handled]：本端是否真的会处理；false 的两项即 [MsgTypes.UNHANDLED]。
 * - [label]：给人看的稳定标识，保留作显示名扩展点；本批次复用帧类型串本身，
 *   不含中文、不参与编解码 / 校验 / 任何线上字节。
 *
 * 三个位的取值必须与改造前四张 `setOf` 逐项等价，由 commonTest 的 parity 测试
 * 用改造前的名单快照钉住。
 */
data class MessageSpec(
    val type: String,
    val known: Boolean = true,
    val reliable: Boolean,
    val handled: Boolean,
    val label: String,
)

/**
 * 34 种已知帧的单一处置表，按改造前 [MsgTypes.KNOWN] 的书写顺序逐项列全。
 *
 * 这里只引用 [MsgTypes] 的 `const val`（编译期常量，内联为字面量），不触碰任何
 * [MsgTypes] 的 `val` 属性 —— 因此 [MsgTypes] 初始化派生四张集合时反过来读 [all]
 * 不会形成对象初始化环。
 */
object MessageSpecs {
    /** 全部 34 项，顺序与改造前 KNOWN 的书写顺序一致。 */
    val all: List<MessageSpec> = listOf(
        MessageSpec(type = MsgTypes.ENTRY, known = true, reliable = false, handled = true, label = MsgTypes.ENTRY),
        MessageSpec(type = MsgTypes.ALIVE, known = true, reliable = false, handled = true, label = MsgTypes.ALIVE),
        MessageSpec(type = MsgTypes.EXIT, known = true, reliable = false, handled = true, label = MsgTypes.EXIT),
        MessageSpec(type = MsgTypes.PRESENCE, known = true, reliable = false, handled = true, label = MsgTypes.PRESENCE),
        MessageSpec(type = MsgTypes.PROFILE, known = true, reliable = false, handled = true, label = MsgTypes.PROFILE),
        MessageSpec(type = MsgTypes.PEERS, known = true, reliable = false, handled = false, label = MsgTypes.PEERS),
        MessageSpec(type = MsgTypes.SCAN_RANGES, known = true, reliable = false, handled = true, label = MsgTypes.SCAN_RANGES),
        MessageSpec(type = MsgTypes.MSG, known = true, reliable = true, handled = true, label = MsgTypes.MSG),
        MessageSpec(type = MsgTypes.ACK, known = true, reliable = false, handled = true, label = MsgTypes.ACK),
        MessageSpec(type = MsgTypes.FILE_CTL, known = true, reliable = true, handled = true, label = MsgTypes.FILE_CTL),
        MessageSpec(type = MsgTypes.GROUP, known = true, reliable = true, handled = true, label = MsgTypes.GROUP),
        MessageSpec(type = MsgTypes.AVATAR, known = true, reliable = true, handled = true, label = MsgTypes.AVATAR),
        MessageSpec(type = MsgTypes.UPDATE, known = true, reliable = true, handled = false, label = MsgTypes.UPDATE),
        MessageSpec(type = MsgTypes.SHARE, known = true, reliable = true, handled = true, label = MsgTypes.SHARE),
        MessageSpec(type = MsgTypes.SCREEN, known = true, reliable = true, handled = true, label = MsgTypes.SCREEN),
        MessageSpec(type = MsgTypes.AGENT_SESSION, known = true, reliable = true, handled = true, label = MsgTypes.AGENT_SESSION),
        MessageSpec(type = MsgTypes.AGENT_EVENT, known = true, reliable = true, handled = true, label = MsgTypes.AGENT_EVENT),
        MessageSpec(type = MsgTypes.AGENT_QUEUE, known = true, reliable = true, handled = true, label = MsgTypes.AGENT_QUEUE),
        MessageSpec(type = MsgTypes.AGENT_RUN, known = true, reliable = true, handled = true, label = MsgTypes.AGENT_RUN),
        MessageSpec(type = MsgTypes.AGENT_APPROVAL, known = true, reliable = true, handled = true, label = MsgTypes.AGENT_APPROVAL),
        MessageSpec(type = MsgTypes.AGENT_CANCEL, known = true, reliable = true, handled = true, label = MsgTypes.AGENT_CANCEL),
        MessageSpec(type = MsgTypes.AGENT_ACK, known = true, reliable = true, handled = true, label = MsgTypes.AGENT_ACK),
        MessageSpec(type = MsgTypes.AGENT_ARTIFACT, known = true, reliable = true, handled = true, label = MsgTypes.AGENT_ARTIFACT),
        MessageSpec(type = MsgTypes.AGENT_WORKSPACE, known = true, reliable = true, handled = true, label = MsgTypes.AGENT_WORKSPACE),
        MessageSpec(type = MsgTypes.AGENT_GRANT_REQUEST, known = true, reliable = true, handled = true, label = MsgTypes.AGENT_GRANT_REQUEST),
        MessageSpec(type = MsgTypes.AGENT_GRANT, known = true, reliable = true, handled = true, label = MsgTypes.AGENT_GRANT),
        MessageSpec(type = MsgTypes.AGENT_GRANT_LIST, known = true, reliable = true, handled = true, label = MsgTypes.AGENT_GRANT_LIST),
        MessageSpec(type = MsgTypes.AGENT_SESSION_PAGE, known = true, reliable = true, handled = true, label = MsgTypes.AGENT_SESSION_PAGE),
        MessageSpec(type = MsgTypes.RECOVERY_REQ, known = true, reliable = true, handled = true, label = MsgTypes.RECOVERY_REQ),
        MessageSpec(type = MsgTypes.RECOVERY_DATA, known = true, reliable = true, handled = true, label = MsgTypes.RECOVERY_DATA),
        MessageSpec(type = MsgTypes.AGENT_INVOKE, known = true, reliable = true, handled = true, label = MsgTypes.AGENT_INVOKE),
        MessageSpec(type = MsgTypes.AGENT_INVOKE_RESULT, known = true, reliable = true, handled = true, label = MsgTypes.AGENT_INVOKE_RESULT),
        MessageSpec(type = MsgTypes.AGENT_CHANNEL, known = true, reliable = true, handled = true, label = MsgTypes.AGENT_CHANNEL),
        MessageSpec(type = MsgTypes.AGENT_CHANNEL_RESULT, known = true, reliable = true, handled = true, label = MsgTypes.AGENT_CHANNEL_RESULT),
    )

    /** 按帧类型串索引，供按 type 查规格的调用点使用。 */
    val byType: Map<String, MessageSpec> = all.associateBy { it.type }
}
