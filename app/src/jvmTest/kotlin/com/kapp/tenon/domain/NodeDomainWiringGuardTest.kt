// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 节点域**依赖面**守卫：三个节点分片只能建在窄缝 [NodeDomainHost] 上，状态写入只能走
 * [NodeDomainHost.updateNodeState]，跨域动作只能走宿主收口方法（`clearConversation` 一类），
 * 主类仍作门面委托。
 *
 * 与 [AgentDomainWiringGuardTest] 同一条理由：`internal` 在同一模块内对任何文件可见，
 * 「节点域不碰消息表 / 传输表」这条纪律在源码上是看不见的，行为用例也不会因为构造参数
 * 被改回去而变红 —— 依赖方向必须由一条能区分对错的守卫钉住。
 *
 * 三条判据都只读活代码（[GuardSourceScan.stripComments] 先剥注释，避免守卫被自己的注释绊倒）。
 */
class NodeDomainWiringGuardTest {

    private val domainPrefix = "app/src/commonMain/kotlin/com/kapp/tenon/domain/"

    private val nodeFamily = listOf(
        "RelayControllerCidrComponent.kt",
        "RelayControllerAvatarComponent.kt",
        "RelayControllerSettingsComponent.kt",
    )

    @Test
    fun nodeFamilyIsBuiltOnTheNarrowHost() {
        for (file in nodeFamily) {
            val source = live(domainPrefix + file)
            assertTrue(
                source.contains("host: NodeDomainHost"),
                "$file 的宿主必须是 NodeDomainHost：改回 RelayController 等于把整类依赖面重新对节点域开放，" +
                    "而行为用例察觉不到（它们测业务行为，不测依赖方向）",
            )
            assertTrue(
                !source.contains("host: RelayController"),
                "$file 不得把宿主类型写回 RelayController —— 那会让本域重新能碰任意跨域状态",
            )
        }
    }

    @Test
    fun nodeFamilyDoesNotTouchCrossDomainState() {
        for (file in nodeFamily) {
            val source = live(domainPrefix + file)
            for (forbidden in listOf("host._state", "host.core", "host.stateLock", "host.messagesCtl")) {
                assertTrue(
                    !source.contains(forbidden),
                    "$file 出现 \"$forbidden\"：状态写入必须走 host.updateNodeState、跨域动作必须走宿主收口方法；" +
                        "直接拿跨域状态等于绕过域边界",
                )
            }
        }
    }

    @Test
    fun controllerKeepsNodeFacadeDelegation() {
        val source = live(domainPrefix + "RelayController.kt")
        assertTrue(
            source.contains("nodeDomain.cidr") &&
                source.contains("nodeDomain.avatar") &&
                source.contains("nodeDomain.settings"),
            "主类必须继续以同名成员（cidrCtl / avatarCtl / settingsCtl）暴露三族（UI 与壳层直接连它，公开 API 不能变）",
        )
        assertTrue(
            !source.contains("RelayControllerCidrComponent(") &&
                !source.contains("RelayControllerAvatarComponent(") &&
                !source.contains("RelayControllerSettingsComponent("),
            "主类不再自己构造节点分片：构造点只能在 NodeDomainService（否则域收口只是换了个变量名）",
        )
    }

    private fun live(relativePath: String): String =
        GuardSourceScan.stripComments(GuardSourceScan.read(relativePath))
}
