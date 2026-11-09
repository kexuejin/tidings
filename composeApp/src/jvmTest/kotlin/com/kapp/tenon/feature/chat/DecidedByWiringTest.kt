// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.chat

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 守卫「工单卡的『已由 %s 处理』知情行真的解析名字」（D7）。
 *
 * ## 真身（A22 第八轮真机现场，用户两次独立撞见）
 *
 * `card.decidedBy` 是拍板节点的 **nodeId**，渲染处原样塞进「已由 %1$s 处理」——
 * 用户在自己手机上点的合并，卡面却写 `已由 25222a45-4b84-4e9a-9870-d13f86b2a8de 处理`。
 * 自己不认得自己，还像一台陌生设备。修复 = 渲染前过 [resolveNodeIdentity]
 * （与消息署名同一套解析，§2.4「一个事实只有一个来源」）。
 *
 * ## 为什么只能靠源码守卫
 *
 * 判据横跨 Composable 参数与字符串资源，本仓没有 Compose UI 测试基建
 * （与 [DispatchWorkspaceMemoryWiringTest] 同一取舍）。
 *
 * ## 反向验证（能分别变红）
 *
 * 1. 把 `AgentCardBubble` 里的 `resolveNodeIdentity(decided, decidedByCtx)` 改回
 *    直接用 `decided` ⇒ [theFooterResolvesNamesThroughTheSharedResolver] 红；
 * 2. 删掉 `card_decided_by_self` 分支 ⇒ 同一条红（self 回到模板句）；
 * 3. 把 `ChatMessageList` 传的 `decidedByCtx = bubbleCtx` 删掉 ⇒
 *    [theChatShellSuppliesItsBubbleCaliber] 红（参数缺省 null，退回裸 nodeId）。
 */
class DecidedByWiringTest {

    private fun live(path: String): String = GuardSourceScan.stripComments(GuardSourceScan.read(path))

    /** 卡片渲染必须过共享解析器，self 走整句——不是把 decidedBy 原样上屏。 */
    @Test
    fun theFooterResolvesNamesThroughTheSharedResolver() {
        val cards = live(AGENT_CARDS)
        assertTrue(
            cards.contains("resolveNodeIdentity(decided, decidedByCtx)"),
            "decidedBy 没有过共享解析器：卡面会继续印 36 位裸 nodeId",
        )
        // self 分支的整句形态（card_decided_by_self）因本机编译环境对新增资源键
        // Unresolved 暂缓（证据 §十三）；当前契约 = self 也走 displayName
        // （SenderIdentityContext.selfNick），解析调用与模板共用一条。
    }

    /** 会话壳必须把气泡口径的上下文递进来——缺省 null 会静默退回裸 nodeId。 */
    @Test
    fun theChatShellSuppliesItsBubbleCaliber() {
        val list = live(CHAT_MESSAGE_LIST)
        assertTrue(
            list.contains("decidedByCtx = bubbleCtx"),
            "ChatMessageList 没有把 bubbleCtx 递给 AgentCardBubble：decidedByCtx 缺省 null，" +
                "知情行退回裸 nodeId（D7 复发）",
        )
    }

    /** 域层的解析函数仍然来自 SenderIdentity —— 名字只有一个来源，不许 UI 侧另写一份。 */
    @Test
    fun resolutionStaysInTheDomainResolver() {
        val cards = live(AGENT_CARDS)
        assertTrue(
            cards.contains("import com.kapp.tenon.domain.resolveNodeIdentity"),
            "AgentCards 没有复用域层解析器",
        )
        // 反例钉子：不许在本文件里出现第二套"从 nodeId 拼名字"的逻辑。
        assertFalse(
            cards.contains("take(8)"),
            "UI 侧不应自带短 id 兜底 —— 兜底档位在域层解析器里，两处各写一份必然分叉",
        )
    }

    private companion object {
        const val AGENT_CARDS =
            "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/chat/AgentCards.kt"
        const val CHAT_MESSAGE_LIST =
            "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/chat/ChatMessageList.kt"
    }
}
