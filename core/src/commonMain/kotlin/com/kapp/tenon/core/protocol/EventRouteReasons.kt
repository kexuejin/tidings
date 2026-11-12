// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

/**
 * 事件路由的成因 token —— 「这条会话的消息为什么没进群」的单一真源。
 *
 * 这些字符串原本散在 `AgentEventForwarder` 的八处调用点上。界面侧要按成因查表就必须
 * 再抄一份，抄两处必错一处（同一个教训见 [InboxUnresolved]）。收成词表后：
 *
 * - **生产方**只引用常量，拼错编译就红；
 * - **展示方**按 token 查文案，认不出就原样显示（`原因 <token>`）—— 那恰恰是
 *   「宿主与界面版本不一致」的证据，翻译成「未知原因」就把线索抹了。
 *
 * token 跨进程与跨语言**不翻译**，进日志、进计数、进页面都用同一串。
 */
object EventRouteReasons {

    /** 会话没有任何会话/目录绑定 —— 既不在台账里，三键反查也没命中。 */
    const val NO_CONV_BOUND: String = "no-conv-bound"

    /** 台账说这条会话属于某个私聊。事件照常落本地，只是不进任何群。 */
    const val BOUND_TO_1TO1: String = "bound-to-1to1"

    /** 台账指向的群已经不在本机名单里（退群 / 对方重建）。**不回落**到目录反查。 */
    const val BOUND_GROUP_GONE: String = "bound-group-gone"

    /** 同一个 sessionId 在不同设备上报出不同的工作区归属（跨设备撞 id）。 */
    const val AMBIGUOUS_OWNER: String = "ambiguous-owner"

    /** 这个目录同时绑了多个群。**阻断**而不是多播 —— 多播正是串群的形态。 */
    const val AMBIGUOUS_GROUP: String = "ambiguous-group"

    /** 正常路径：投给了 N 个群（N 只进诊断行，**不进聚合键**，见下）。 */
    const val DISPATCH: String = "dispatch"

    /** 正常路径：这条事件已经投递过。 */
    const val DUPLICATE_DELIVERY: String = "duplicate-delivery"

    /** 正常路径：聊天里已经有同一条。 */
    const val DUPLICATE_IN_CHAT: String = "duplicate-in-chat"

    /**
     * 「为什么没进群」的成因，页面只列这几档（顺序即展示次序，词表里靠前的更该被看见）。
     *
     * 不列 [DISPATCH] 与两条 `duplicate-*`：它们是**正常路径**，混进成因列表只会把
     * 真问题淹掉。`bound-to-1to1` 列进来是因为它虽然不算故障，但用户确实会问
     * 「我在这个会话里说的话，群里怎么没有」—— 一句解释比一个空缺好。
     */
    val BLOCKING: List<String> = listOf(
        AMBIGUOUS_GROUP,
        BOUND_GROUP_GONE,
        NO_CONV_BOUND,
        AMBIGUOUS_OWNER,
        BOUND_TO_1TO1,
    )

    /**
     * 正常路径：投递成功或去重生效。页面**不列**这三档。
     *
     * 为什么用「显式排除」而不是「只列 [BLOCKING] 白名单」：后者会把**未来新增的成因**
     * 静默丢掉，而界面看不见的那一档恰恰是「宿主比界面新」的证据。所以口径是
     * 「除这三档之外一律显示」，认不出的 token 排最后、原样显示。
     */
    val NORMAL: List<String> = listOf(DISPATCH, DUPLICATE_DELIVERY, DUPLICATE_IN_CHAT)
}
