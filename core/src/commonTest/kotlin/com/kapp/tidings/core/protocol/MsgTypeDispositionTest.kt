// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「每一种已知报文类型都必须有一句写下来的处置」这条规矩，由测试钉住。
 *
 * 为什么需要它：协议里有 17 种帧，本端有意不做其中的 7 种（群元数据、自定义头像、更新源、
 * 共享柜、投屏、gossip 摘要、网段分享）。如果"不做"只表现为某个 `when` 少一个分支，
 * 那么读代码的人无法区分**"我们认得这帧，理由是这个"**和**"我们没见过这种帧"** ——
 * 而这两件事的下一步动作是相反的。
 *
 * 所以 [MsgTypes.HANDLED] 与 [MsgTypes.UNHANDLED] 必须是 [MsgTypes.KNOWN] 的一个**划分**：
 * 以后往协议里加一种帧，这里就红，逼着加的人当场表态（做，还是不做、为什么）。
 *
 * 注意这里只断言"表是完整的、且不自相矛盾"。理由本身是中文散文，写在 [MsgTypes.UNHANDLED]
 * 的 KDoc 和仓库根的 `PROTOCOL-COVERAGE.md` 里 —— 生产代码里放中文散文会被 CJK 字面量门禁
 * （`tools/cjk-literal-gate.gradle.kts`）拦下，那是给用户可见文案用的棘轮。
 */
class MsgTypeDispositionTest {

    @Test
    fun everyKnownMessageTypeIsClassifiedExactlyOnce() {
        val overlap = MsgTypes.HANDLED intersect MsgTypes.UNHANDLED
        assertEquals(
            emptySet(),
            overlap,
            "同一种报文不能既说处理又说不做：$overlap",
        )
        assertEquals(
            MsgTypes.KNOWN,
            MsgTypes.HANDLED + MsgTypes.UNHANDLED,
            "每一种已知报文类型都必须被分到 HANDLED 或 UNHANDLED 里：新增一种帧就要当场表态",
        )
    }

    /**
     * 声明 `caps` 的三种能力都必须落在**真的会处理**的那一半里。
     *
     * 这条防的是"声明了却不处理"（PPE-1 §4：声明了却没实现 = 老用户被静默吞帧）：
     * 能力键是给对端看的承诺，承诺的对象就是某一种帧的处置。
     */
    @Test
    fun theCapabilitiesWeDeclarePointAtRealHandling() {
        for (type in listOf(MsgTypes.MSG, MsgTypes.AGENT_SESSION, MsgTypes.AGENT_EVENT, MsgTypes.AVATAR)) {
            assertTrue(
                type in MsgTypes.HANDLED,
                "$type 对应我们声明的一种能力，它必须出现在 HANDLED 里",
            )
        }
    }

    /**
     * `file-ctl` 归文件传输、`ack` 归可靠投递，两者都不进消息流 —— 但它们**是**被处理的，
     * 不该出现在 `UNHANDLED` 里被当成"没做"。
     *
     * 这条是给以后改分类的人看的：`UNHANDLED` 的含义是"产品上决定不做"，不是"不在这条代码路径上"。
     */
    @Test
    fun unhandledMeansADeliberateProductDecisionNotAnUnrelatedCodePath() {
        for (type in listOf(MsgTypes.ACK, MsgTypes.FILE_CTL)) {
            assertTrue(
                type in MsgTypes.HANDLED,
                "$type 在核心层有真实处理，不能被归进「有意不做」",
            )
        }
    }
}
