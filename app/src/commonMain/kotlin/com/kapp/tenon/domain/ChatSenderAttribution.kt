// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.domain.model.MessageUi

/**
 * 一条消息在聊天流里**归谁**（署名 / 头像 / Markdown / 长按 @ 共用这一份）。
 *
 * ## 为什么单聊也要过这一层
 *
 * 这里原来是 `group != null ? resolveSenderIdentity(senderId) : resolveNodeIdentity(peer.nodeId)`：
 * 群聊按 `senderId` 认人，**单聊一律当成"对端那台设备"**，署名也恒为 null。
 * 而 1:1 直派（`dispatchDirectInvoke`）的会话里，说话的有两方：对端设备上的人，
 * 以及**那台设备上的 agent**（`senderId = agent:<宿主>:<kind>`，宿主以它署名发回
 * 「Ticket accepted…」/「改动已就绪…」）。全都按设备算的结果就是用户看到的那一幕：
 * 一屏消息"看起来都是对端/移动端发的"，agent 说的话没有名字。
 *
 * ## 三条判据
 *
 * 1. **自己发的**：没有"别人"可署，恒 null。
 * 2. **群聊**：会话键是 `group:<id>`，气泡左右分不出"哪个人"，`senderId` 是唯一线索。
 * 3. **单聊**：
 *    - `senderId = agent:…` ⇒ 按 agent 解析（名字 + agent 头像），**这是本文件存在的理由**；
 *    - 有 `senderId` 且带文件 ⇒ 按**产物来源**解析：文件不是人手打的，归属那台设备/它的
 *      agent（[identityCtx]，agent 宿主显示 agent 名 —— 与头部标题同一个口径）。
 *      这里故意用 [identityCtx] 而不是 `bareNodeIdIsHuman` 那份：后者防的是"人的裸
 *      nodeId 被印成 agent 名"，而文件卡不存在这个歧义；
 *    - 没有 `senderId`（老端帧 / 普通人的消息）⇒ 退回会话对端，且**不署名**
 *      （会话标题已经说明了在跟谁说话，再署一次是同一件事的第二个说法）。
 */
object ChatSenderAttribution {

    /**
     * @param identityCtx 成员/产物口径的上下文（agent 宿主的裸 nodeId **认作** agent）
     * @param bubbleCtx 气泡口径的上下文（`bareNodeIdIsHuman = true`，人的裸 nodeId 是人）
     */
    fun identityOf(
        message: MessageUi,
        isGroup: Boolean,
        peerNodeId: String?,
        identityCtx: SenderIdentityContext,
        bubbleCtx: SenderIdentityContext,
    ): SenderIdentity? = when {
        message.mine -> null
        isGroup -> resolveSenderIdentity(message.senderId, bubbleCtx)
        message.senderId == null ->
            // 老端帧（协议里没有 senderId）与设备级消息：按**对端身份**解析。
            // 用 [identityCtx] 而不是气泡口径：1:1 的对端若是 agent 宿主，
            // 这个会话的对端身份就是那台机器上的 agent（名字 `dsh`），而不是机器名
            // —— 与头部标题、文件卡署名同一个口径。人对端不受影响（identityCtx 里
            // 没有 agent kind 时仍解析成人名，且不署名）。
            peerNodeId?.let { resolveNodeIdentity(it, identityCtx) }
        message.file != null -> resolveNodeIdentity(message.senderId, identityCtx)
        else -> resolveSenderIdentity(message.senderId, bubbleCtx)
    }

    /**
     * 这条消息要不要在气泡上方印名字。
     *
     * 群聊恒印（不印就分不清是谁）；单聊只在**对方是 agent** 时印 —— 那是"这句话是
     * dsh 说的"这个事实的唯一可见处。人对端不印：会话标题已经写了他是谁。
     */
    fun showsName(message: MessageUi, isGroup: Boolean, identity: SenderIdentity?): Boolean =
        !message.mine && (isGroup || identity?.isAgent == true)
}
