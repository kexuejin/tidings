// SPDX-License-Identifier: GPL-3.0-only

package app.relay.agent

import app.relay.core.protocol.Codec
import app.relay.core.protocol.Envelope
import app.relay.core.protocol.MsgTypes
import app.relay.core.protocol.makeEnvelope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 工作区台账的 order（注册表持久序）测试。
 *
 * 守的是一条真机缺陷：桌面端自己也是宿主（advertiseAgent = true），它的工作区台账经
 * [AgentWorkspaceRegistry] 记账后落进 AgentWorkspaceEntity.host_order。而这张台账曾经
 * **没有 order 字段** —— 桌面库里 15/15 工作区的 host_order 是 NULL，手机库同一批却是
 * 0/1/2/3/5/6/7，于是同一批工作区在两端排序不同。
 *
 * 三条不变量：
 *  1. 出站记一次（ControlPlane 那一路）+ 入站记一次，order 仍在；
 *  2. 缺 order 的帧**不抹掉**已有值（合并纪律与其它字段一致）；
 *  3. 没人报过就是 null，不猜一个 0。
 */
class AgentWorkspaceOrderTest {

    private fun inbound(from: String, order: Long?): Envelope = makeEnvelope(
        MsgTypes.AGENT_WORKSPACE,
        from,
        Codec.agentWorkspacePayload(
            wsId = "ws-order-1",
            agentId = "dsh",
            agentKind = "dsh",
            order = order,
        ),
    )

    @Test
    fun outboundRecordThenInboundRecordKeepsOrder() {
        val registry = AgentWorkspaceRegistry()
        // 出站那一路：ControlPlane.agentWorkspace 在发出帧之后记本宿主的工作区。
        registry.record(
            nodeId = "host",
            agentKey = "dsh",
            wsId = "ws-order-1",
            wsTitle = "工作区",
            order = 3L,
            outbound = true,
        )
        assertEquals(3L, registry.workspacesOf("host").single().order, "出站记的 order 必须进台账")

        // 入站那一路：设备侧的 recordInboundWorkspace 从 payload 读 order。
        registry.recordInboundWorkspace(inbound("host", order = 7L))
        assertEquals(7L, registry.workspacesOf("host").single().order, "入站帧的非空 order 覆盖旧值")
    }

    @Test
    fun inboundFrameWithoutOrderDoesNotWipeExistingOrder() {
        val registry = AgentWorkspaceRegistry()
        registry.recordInboundWorkspace(inbound("host", order = 7L))
        assertEquals(7L, registry.workspacesOf("host").single().order)

        // 只更新状态的收尾帧不带 order —— 抹掉它，顺序就退回"按到达序"，重启一次就乱。
        registry.recordInboundWorkspace(inbound("host", order = null))
        assertEquals(7L, registry.workspacesOf("host").single().order, "缺 order 的帧不该抹掉已有值")
    }

    /**
     * 并发两次更新**不许丢字段**（task-60 审查修）。
     *
     * 为什么必须有：控制面是 16 条线程的池，入站帧（recordInboundWorkspace）与出站自宣告
     * （ControlPlane 发完帧后记本宿主）会并发落到同一个 key。修之前是「读 old → 拼新 → 写回」，
     * 两次合并各基于过期快照 ⇒ 甲写 order=7、乙紧接着写 order = (null ?: null) = **null**，
     * order 被抹掉 —— 正是这一列当初要修的那个真机缺陷换个形态回来。
     *
     * 诚实说明：这是**概率性**回归哨兵，不是确定性证明（并发下"必红"的测试不存在，除非给
     * 生产代码加测试专用插桩）。用 CyclicBarrier 让每轮两线程尽量同时进入，把旧写法撞红的
     * 概率拉到最高；改用 ConcurrentHashMap.compute 之后它**恒绿**。真正的保证来自 compute
     * 对同一个 key 的原子性，这条测试只是让"退回读—改—写"更可能被抓住。
     */
    @Test
    fun concurrentUpdatesNeverWipeTheOrder() {
        val registry = AgentWorkspaceRegistry()
        val barrier = java.util.concurrent.CyclicBarrier(2)
        val threads = listOf(7L, null).map { order ->
            Thread {
                repeat(200) {
                    barrier.await()
                    registry.record(
                        nodeId = "host",
                        agentKey = "dsh",
                        wsId = "ws-concurrent",
                        order = order,
                        outbound = false,
                    )
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(
            7L,
            registry.workspacesOf("host").single().order,
            "并发下「非空覆盖、空保留旧值」必须仍成立：缺 order 的那条不许把已有的 7 抹成 null",
        )
    }

    @Test
    fun orderStaysNullWhenNobodyReportedIt() {
        val registry = AgentWorkspaceRegistry()
        registry.recordInboundWorkspace(inbound("host", order = null))
        assertNull(registry.workspacesOf("host").single().order, "没人报过就是 null，不猜一个 0")
    }
}
