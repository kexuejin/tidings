// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 传输域**依赖面**守卫：文件传输 / 共享柜 / 投屏三个分片只能建在窄缝 [TransferDomainHost] 上，
 * 状态写入只能走 [TransferDomainHost.updateTransferState]，主类仍作门面委托。
 *
 * 与 [AgentDomainWiringGuardTest] / [NodeDomainWiringGuardTest] 同一条理由：`internal`
 * 在同一模块内对任何文件可见，「传输域不碰设置/agent/群组」这条纪律在源码上看不见，
 * 行为用例也不会因为构造参数被改回去而变红 —— 依赖方向必须由一条能区分对错的守卫钉住。
 *
 * 本域**允许** `host.core` / `host.stateLock`：传输表与聊天表同住、共用一把锁，那是
 * Phase 3 第三步要清的已知耦合（接口 KDoc 里有记账）。守卫只钉「不许绕过写入口」
 * 与「不许点名其它域的分片」，不假装那层耦合已经消失。
 */
class TransferDomainWiringGuardTest {

    private val domainPrefix = "app/src/commonMain/kotlin/com/kapp/tenon/domain/"

    private val transferFamily = listOf(
        "RelayControllerTransfersComponent.kt",
        "RelayControllerCabinetComponent.kt",
        "RelayControllerScreenComponent.kt",
    )

    @Test
    fun transferFamilyIsBuiltOnTheNarrowHost() {
        for (file in transferFamily) {
            val source = live(domainPrefix + file)
            assertTrue(
                source.contains("host: TransferDomainHost"),
                "$file 的宿主必须是 TransferDomainHost：改回 RelayController 等于把整类依赖面重新对传输域开放，" +
                    "而行为用例察觉不到（它们测业务行为，不测依赖方向）",
            )
            assertTrue(
                !source.contains("host: RelayController"),
                "$file 不得把宿主类型写回 RelayController —— 那会让本域重新能碰任意跨域状态",
            )
        }
    }

    @Test
    fun transferFamilyWritesStateOnlyThroughTheDomainEntry() {
        for (file in transferFamily) {
            val source = live(domainPrefix + file)
            assertTrue(
                !source.contains("host._state"),
                "$file 出现 \"host._state\"：状态写入必须走 host.updateTransferState；直接拿流等于绕过域写入口",
            )
            for (crossDomain in listOf("host.messagesCtl", "host.groupsCtl", "host.agentCtl", "host.invokeCtl", "host.settingsCtl", "host.cidrCtl")) {
                assertTrue(
                    !source.contains(crossDomain),
                    "$file 出现 \"$crossDomain\"：跨域动作必须走 TransferDomainHost 的收口方法，不能直接点名别的分片",
                )
            }
        }
    }

    @Test
    fun controllerKeepsTransferFacadeDelegation() {
        val source = live(domainPrefix + "RelayController.kt")
        assertTrue(
            source.contains("transferDomain.transfers") &&
                source.contains("transferDomain.cabinet") &&
                source.contains("transferDomain.screen"),
            "主类必须继续以同名成员（transfersCtl / cabinetCtl / screenCtl）暴露三族（UI 与壳层直接连它，公开 API 不能变）",
        )
        assertTrue(
            !source.contains("RelayControllerTransfersComponent(") &&
                !source.contains("RelayControllerCabinetComponent(") &&
                !source.contains("RelayControllerScreenComponent("),
            "主类不再自己构造传输分片：构造点只能在 TransferDomainService（否则域收口只是换了个变量名）",
        )
    }

    private fun live(relativePath: String): String =
        GuardSourceScan.stripComments(GuardSourceScan.read(relativePath))
}
