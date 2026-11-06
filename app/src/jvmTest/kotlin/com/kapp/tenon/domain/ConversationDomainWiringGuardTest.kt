// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 会话域**依赖面**守卫：消息本体 / 群 / 小游戏卡 / 投屏记录四个分片只能建在窄缝
 * [ConversationDomainHost] 上；跨域信封分发只能走端口（`host.agent` / `host.transfer` /
 * `host.node`），不能退回直连别的域的分片或其服务实例。
 *
 * ## 为什么本域这条守卫比前三个域更严
 *
 * 会话域是**入站总分发处**：一个信封进来先由消息分片按类型分发到其它三个域。它天然
 * 想伸手去拿 `agentCtl` / `screenCtl` / `cidrCtl` —— 那正是本次收口要断掉的路。
 * 如果只钉「宿主类型」，有人可以继续在分片里写 `host.agentCtl.handleOnAgentSession(...)`；
 * 依赖矩阵里那条「Messages 一个分片点名另外八个组件」的分叉就会原地长回来。
 *
 * 所以这里同时禁掉：其它域的分片句柄（`*Ctl`）与其它域的服务实例
 * （`host.agentDomain` 一类）—— 分片只能用端口。
 *
 * 本域**允许** `host.core` / `host.stateLock`：消息/群/传输表同住、共用一把锁，那是
 * Phase 3 第三步要清的已知耦合（接口 KDoc 有记账）。守卫只钉「不许绕过端口」和
 * 「不许直接点名牌」，不假装那层耦合已经消失。
 */
class ConversationDomainWiringGuardTest {

    private val domainPrefix = "app/src/commonMain/kotlin/com/kapp/tenon/domain/"

    private val conversationFamily = listOf(
        "RelayControllerMessagesComponent.kt",
        "RelayControllerGroupsComponent.kt",
        "RelayControllerPkComponent.kt",
        "RelayControllerScreenHistoryComponent.kt",
    )

    @Test
    fun conversationFamilyIsBuiltOnTheNarrowHost() {
        for (file in conversationFamily) {
            val source = live(domainPrefix + file)
            assertTrue(
                source.contains("host: ConversationDomainHost"),
                "$file 的宿主必须是 ConversationDomainHost：改回 RelayController 等于把整类依赖面重新开放，行为用例察觉不到",
            )
            assertTrue(
                !source.contains("host: RelayController"),
                "$file 不得把宿主类型写回 RelayController",
            )
        }
    }

    @Test
    fun conversationFamilyReachesOtherDomainsOnlyThroughPorts() {
        val forbidden = listOf(
            "host._state",
            // 其它域的分片句柄
            "host.agentCtl", "host.invokeCtl",
            "host.screenCtl", "host.cabinetCtl",
            "host.cidrCtl", "host.avatarCtl", "host.settingsCtl",
            // 其它域的服务实例
            "host.agentDomain", "host.transferDomain", "host.nodeDomain",
            // 本域其它分片也走宿主成员，不直接互相拿
            "host.groupsCtl", "host.pkCtl", "host.messagesCtl", "host.screenHistoryCtl",
        )
        for (file in conversationFamily) {
            val source = live(domainPrefix + file)
            for (name in forbidden) {
                assertTrue(
                    !source.contains(name),
                    "$file 出现 \"$name\"：状态写入走 publish 路径、跨域动作只能走端口 " +
                        "（host.agent / host.transfer / host.node）；直接点名别的域的分片就是本次收口要断掉的依赖",
                )
            }
        }
    }

    @Test
    fun controllerKeepsConversationFacadeDelegation() {
        val source = live(domainPrefix + "RelayController.kt")
        assertTrue(
            source.contains("conversationDomain.messages") &&
                source.contains("conversationDomain.groups") &&
                source.contains("conversationDomain.pk") &&
                source.contains("conversationDomain.screenHistory"),
            "主类必须继续以同名成员（messagesCtl / groupsCtl / pkCtl / screenHistoryCtl）暴露四族（UI 与壳层直接连它）",
        )
        assertTrue(
            !source.contains("RelayControllerMessagesComponent(") &&
                !source.contains("RelayControllerGroupsComponent(") &&
                !source.contains("RelayControllerPkComponent(") &&
                !source.contains("RelayControllerScreenHistoryComponent("),
            "主类不再自己构造会话分片：构造点只能在 ConversationDomainService（否则域收口只是换了个变量名）",
        )
        assertTrue(
            !source.contains("RelayControllerAgentComponent(") &&
                !source.contains("RelayControllerTransfersComponent(") &&
                !source.contains("RelayControllerCidrComponent("),
            "前三个域的分片构造点仍必须只在各自的服务里（本次改动不得把它们搬回主类）",
        )
    }

    private fun live(relativePath: String): String =
        GuardSourceScan.stripComments(GuardSourceScan.read(relativePath))
}
