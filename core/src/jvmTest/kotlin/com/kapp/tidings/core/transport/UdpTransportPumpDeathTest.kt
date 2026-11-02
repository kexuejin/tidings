// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import kotlinx.coroutines.delay
import com.kapp.tidings.core.testutil.awaitTrue
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * 收包泵死亡必须让 [UdpTransport.send] 如实报失败（task-29 第二死法，2026-11-02 真机）。
 *
 * ## 守的是什么
 *
 * 真机现场：AP 重启导致 Wi-Fi 抖动，ktor 的 `bound.incoming` 通道**不抛异常地正常结束**，
 * 收包循环体面退出 —— socket 依然绑定（内核 UDP 计数器照常增长、NoPorts 不动）、
 * 依然发得出去，但**收包永远停了**。双方各自发消息、各自永远等不到 ack。
 * 旧的「发送失败自愈」对这种死法永不触发，因为发送一直是成功的。
 *
 * 修复：泵退出 ⇒ `pumpAlive=false` ⇒ send 返回 false ⇒ 复用 Discovery 的
 * 「连续 0 交付 ⇒ restart」自愈路径重建整个 transport。
 *
 * 这里测的是那座桥的两端：
 * 1. 泵死 ⇒ send 报 false，且 lastSendError 能和「内核拒绝」区分开；
 * 2. restart 之后 send 恢复 true（自愈闭环真的能走通）。
 */
class UdpTransportPumpDeathTest {

    @Test
    fun aDeadReceivePumpMakesSendReportFailureInsteadOfLying() = runBlocking {
        val t = UdpTransport(port = 0)
        t.start()
        try {
            // 健康时 loopback 单播必须成功（也等于验证 pumpAlive 初始为真，没有把
            // 正常路径误伤成恒 false）。
            assertTrue(
                t.send(byteArrayOf(0x01), "127.0.0.1", t.boundPort),
                "健康 transport 的 loopback 发送应当成功",
            )

            // 模拟「通道静默结束、socket 还在」的死泵状态。
            t.markReceivePumpDeadForTest()
            assertFalse(
                t.send(byteArrayOf(0x01), "127.0.0.1", t.boundPort),
                "收包泵已死的 transport 必须报发送失败 —— 能发不能收的 UDP 通道毫无用处，" +
                    "报 true 会让自愈永不触发（真机 2026-11-02 现场就是死在这里）",
            )
            assertTrue(
                (t.lastSendError ?: "").contains("receive-pump-dead"),
                "失败原因必须能与「内核拒绝」区分（实际：${t.lastSendError}）",
            )
        } finally {
            t.stop()
        }
    }

    @Test
    fun restartResurrectsTheTransportAfterPumpDeath() = runBlocking {
        val t = UdpTransport(port = 0)
        t.start()
        try {
            t.markReceivePumpDeadForTest()
            assertFalse(t.send(byteArrayOf(0x01), "127.0.0.1", t.boundPort))

            // 自愈路径就是 restart()（等价 stop+start）——它必须把泵救活。
            t.restart()
            // 绑定新端口/内核记账需要一点点时间：轮询「发送恢复」这个确定事件，
            // 不用固定 delay 赌内核记账完成（等待策略统一走 testutil.awaitTrue）。
            awaitTrue(
                "restart 之后发送必须恢复（自愈闭环）",
                timeoutMs = 30_000,
            ) { t.send(byteArrayOf(0x01), "127.0.0.1", t.boundPort) }
        } finally {
            t.stop()
        }
    }

    @Test
    fun stopStopsSendingToo() = runBlocking {
        val t = UdpTransport(port = 0)
        t.start()
        val port = t.boundPort
        t.stop()
        // stop 后 socket 已空，send 必须如实报 false（泵的 finally 也会把 pumpAlive 置 false，
        // 两条路径殊途同归；这条钉住的是「stop 之后不能再报 true」）。
        assertFalse(t.send(byteArrayOf(0x01), "127.0.0.1", port))
    }
}
