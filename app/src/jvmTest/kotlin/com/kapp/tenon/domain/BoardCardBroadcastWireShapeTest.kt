// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 卡广播**线上形状**的守卫：群路径的 `rt.sendGroupText` 不得带会话归属键。
 *
 * ## 守的是什么缺陷
 *
 * 群文本的 `agentSessionId` 必须与 `agentCursor` **成对**出现（`CodecMsg.validateMsg`
 * 的成对校验），而卡广播没有事件游标可带。此前广播把卡里的 `sessionId` 单独塞进
 * 群帧 —— 编码成功、本机落库成功，但**每个接收端整帧拒收**（bad-payload:msg）→
 * 无 ACK → 入队 → 队列重放自解码也拒 → 从队列删除并标 failed。工单一开跑就带
 * sessionId 的那些帧（executing / review_ready / blocked / failed 终态）在所有对端
 * 的界面上都不存在，且发送侧全程无错 —— AGENTS §3「发送侧一切正常、接收侧什么
 * 都没有」的真身（e2e hello 现场：rev=3 卡广播 acked=false，flush drop
 * `undecodable:bad-payload:msg`，1154 B）。
 *
 * ## 为什么在源码层守
 *
 * 「带没带这个键」类型系统表达不了：`rt.sendGroupText` 的这两个参数是可选的，
 * 编译器不拦。构造点侧的不变量（成对才许拼帧）由 `:core` 的
 * `CodecTest.theBuilderRefusesAnUnpairedSessionAttribution` 守住；这份守**调用方**
 * —— 把键从广播里拆掉的修复不能被悄悄加回来。
 */
class BoardCardBroadcastWireShapeTest {

    private val live = GuardSourceScan.stripComments(
        GuardSourceScan.read(
            "app/src/commonMain/kotlin/com/kapp/tenon/domain/RelayControllerAgentCardActions.kt",
        ),
    )

    @Test
    fun theGroupCardBroadcastCarriesNoSessionAttribution() {
        val args = GuardSourceScan.parenBlockAfter(live, "rt.sendGroupText(")
        assertTrue(
            !args.contains("agentSessionId"),
            "群卡广播不得带 agentSessionId：没有 agentCursor 配对，带出去的帧整帧被拒。" +
                "会话归属随 card 键自身的 sessionId 过线，不占 msg 级键。实参表：$args",
        )
        assertTrue(
            !args.contains("agentSessionTitle"),
            "agentSessionTitle 单独出现同样被拒（没有主语的标题）：$args",
        )
        assertTrue(
            args.contains("card = finalCard"),
            "守卫必须切在真广播那一处（卡不在实参里说明切错了调用）：$args",
        )
    }

    @Test
    fun localRowsKeepTheSessionAttribution() {
        // 拆的是**线上键**，不是宿主自己的历史归属：本机落库行（MessageUi 与原位替换
        // 两处）仍带 agentSessionId，桌面端聊天里的任务分段线照画。
        assertTrue(
            live.contains("agentSessionId = cardSessionId"),
            "本机落库行必须保留会话归属（拆掉的只是线上键）",
        )
    }
}
