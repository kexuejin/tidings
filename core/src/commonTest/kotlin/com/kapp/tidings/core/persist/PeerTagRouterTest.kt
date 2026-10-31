// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.persist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `targetTag` 路由的确定性判据（M7-T4，决策包 §3.7 第 3 条）：
 * **在线优先 + nodeId 字典序**，写死钉住 —— 多节点同标签时谁承接必须有唯一答案，
 * 否则同一次派单可能落到不同机器（用户看到的是「有时在这台跑、有时在那台跑」）。
 *
 * 反向验证路径：摘掉「在线优先」⇒ 离线优先用例红；摘掉字典序 ⇒ 顺序用例红。
 */
class PeerTagRouterTest {

    @Test
    fun onlineNodeWinsOverOfflineAtSameTag() {
        // 离线候选故意取字典序更小者：摘掉「在线优先」时它会赢 ⇒ 用例红（反向验证成立）。
        assertEquals(
            "node-z-online",
            PeerTagRouter.resolve(
                "gpu",
                candidatesWithTag = listOf("node-a-offline", "node-z-online"),
                isOnline = { it == "node-z-online" },
            ),
            "在线优先：离线节点不该承接（发起方只能看见本机缓存的可达性）",
        )
    }

    @Test
    fun dictionaryOrderBreaksTiesAmongOnlineNodes() {
        assertEquals(
            "node-a",
            PeerTagRouter.resolve(
                "gpu",
                candidatesWithTag = listOf("node-c", "node-a", "node-b"),
                isOnline = { true },
            ),
            "同在线 ⇒ nodeId 字典序最小者承接（确定性判据）",
        )
    }

    @Test
    fun allOfflineStillYieldsTheDeterministicCandidate() {
        // 全离线不是 null：标签路由的意义就是「等它上线」—— 派单结果由接收侧租约兜底。
        assertEquals(
            "node-aaa",
            PeerTagRouter.resolve(
                "gpu",
                candidatesWithTag = listOf("node-bbb", "node-aaa"),
                isOnline = { false },
            ),
            "全离线仍给确定性候选（调用方照发，接收侧离线队列/租约兜底）",
        )
    }

    @Test
    fun malformedTagYieldsNullInsteadOfGuessing() {
        assertNull(
            PeerTagRouter.resolve("GPU", candidatesWithTag = listOf("node-a"), isOnline = { true }),
            "坏形状的标签没有候选语义 ⇒ null（调用方如实回 no-tag-match）",
        )
        assertNull(
            PeerTagRouter.resolve("gpu", candidatesWithTag = emptyList(), isOnline = { true }),
            "没有节点挂着这个标签 ⇒ null（no-tag-match 的诚实回执来源）",
        )
    }
}
