// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.guards

import com.kapp.tidings.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 删除节点必须把**四份落盘一起缩掉**。
 *
 * 这条守卫守的是一类「不崩、也不报错」的缺陷：registry.forget 只动内存，落盘在
 * NodeRuntime.forgetPeer 里补。少写一处，症状各不相同且都不会在日志里出现：
 * - 漏 knownPeerStore.save ⇒ 重启后 known-peers 把那条放回来（「删了没用」）；
 * - 漏 peerEndpointStore.save ⇒ 继续往旧地址发心跳；
 * - 漏 peerPinStore.save / saveSources ⇒ pin 还在，但节点表里没有那一行（无主体的信任）。
 *
 * 源码级守卫在这里是正当手段（AGENTS §4）：这四处必须一致，类型系统表达不了。
 */
class ForgetPeerPersistenceGuardTest {

    private val runtimePath =
        "core/src/commonMain/kotlin/com/kapp/tidings/core/session/NodeRuntime.kt"

    @Test
    fun forgetPeerShrinksEveryPersistedStore() {
        val text = GuardSourceScan.stripComments(GuardSourceScan.read(runtimePath))
        val body = GuardSourceScan.bracedBlockAfter(text, "fun forgetPeer(")
        assertTrue(body.contains("registry.forget("), "先删内存里的节点身份")
        assertTrue(body.contains("knownPeerStore?.save("), "known-peers 必须立刻重写，否则重启后那条会回来")
        assertTrue(body.contains("peerEndpointStore.save("), "候选地址池必须一起缩，否则继续往旧地址发心跳")
        assertTrue(body.contains("peerPinStore.save("), "pin 必须一起清，否则留下一个没有主体的信任")
        assertTrue(body.contains("peerPinStore.saveSources("), "pin 的来源也要一起清（失步时来源会对不上）")
    }
}
