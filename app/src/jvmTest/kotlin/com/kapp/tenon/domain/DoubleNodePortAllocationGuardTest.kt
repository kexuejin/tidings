// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.testsupport.GuardSourceScan
import com.kapp.tenon.testutil.TestPorts
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 双节点测试的端口分配纪律（源码守卫 + 行为探针）。
 *
 * 现场：`:testStabilityGate` 第 2 轮 `TwoNodeAgentInvokeTest` 报
 * `等了 30000ms 仍未发生：B 起来`；单跑该测试类 6/6 全绿。四个双节点测试原先
 * 各抄一份 `ServerSocket(0).use { it.localPort }`：只探 TCP、拿到就放、还连拿两次，
 * 在满载 883 用例的 JVM 里会与残留的 UDP socket 或紧邻的第二次调用抢同一组端口。
 *
 * 这里不判「跑得快不快」（那是观测预算，不是断言），只钉装置的结构：
 * 四个测试走同一个分配器、分配器按真实协议探测、连续发号不重叠且落在系统
 * 临时端口段之下。
 *
 * 反向验证：把任一个测试改回私有 `ServerSocket(0)`，第一条用例变红；
 * 把 `TestPorts` 的 `DatagramSocket` 探测摘掉，第二条用例变红；
 * 把 `getAndIncrement()` 换成固定值，第三条用例变红。
 */
class DoubleNodePortAllocationGuardTest {

    private val doubleNodeTests = listOf(
        "app/src/jvmTest/kotlin/com/kapp/tenon/domain/TwoNodeAgentInvokeTest.kt",
        "app/src/jvmTest/kotlin/com/kapp/tenon/domain/TwoNodeDeliveryTest.kt",
        "app/src/jvmTest/kotlin/com/kapp/tenon/domain/GroupDissolvePropagationTest.kt",
        "app/src/jvmTest/kotlin/com/kapp/tenon/domain/OfflinePatchBundleTest.kt",
    )

    private fun source(path: String): String =
        File(GuardSourceScan.scaffoldRoot(), path).readText()

    @Test
    fun everyDoubleNodeTestUsesTheSharedAllocator() {
        for (path in doubleNodeTests) {
            val body = source(path)
            assertTrue(
                body.contains("TestPorts.freeUdpBase()"),
                "$path 必须走共享分配器，不许各自拿端口",
            )
            assertFalse(
                body.contains("ServerSocket(0)"),
                "$path 不许再用只探 TCP 的 ServerSocket(0) 拿号",
            )
            assertFalse(
                body.contains("private fun freePort"),
                "$path 不许再抄一份私有 freePort（同一个值被问两次，迟早两处不一致）",
            )
        }
    }

    @Test
    fun theAllocatorProbesBothUdpAndTcpAndNeverReusesABlock() {
        val body = source("app/src/jvmTest/kotlin/com/kapp/tenon/testutil/TestPorts.kt")
        assertTrue(
            body.contains("DatagramSocket(base)"),
            "UDP 发现口必须真 bind 探测：ServerSocket 只证明 TCP 空闲",
        )
        assertTrue(
            body.contains("ServerSocket(base + offset)"),
            "派生 TCP / TLS / 跨网端口必须逐个真 bind 探测",
        )
        assertTrue(
            body.contains("getAndIncrement()"),
            "同一 JVM 内不许复用同一块（残留 socket 会比测试活得久）",
        )
    }

    @Test
    fun consecutiveBlocksNeverOverlapAndStayBelowTheEphemeralRange() {
        val bases = (1..200).map { TestPorts.freeUdpBase() }
        assertEquals(200, bases.toSet().size, "同 JVM 内每一块只能发一次")
        val ranges = bases.map { it..it + 4 }
        for (i in ranges.indices) {
            for (j in i + 1 until ranges.size) {
                assertTrue(
                    ranges[i].last < ranges[j].first || ranges[j].last < ranges[i].first,
                    "块 ${bases[i]} 与 ${bases[j]} 的派生端口重叠了",
                )
            }
        }
        assertTrue(
            bases.all { it >= 21_000 && it + 4 < 32_768 },
            "必须落在内核临时端口段之下，否则别的测试的 ServerSocket(0) 还会撞上来",
        )
    }
}
