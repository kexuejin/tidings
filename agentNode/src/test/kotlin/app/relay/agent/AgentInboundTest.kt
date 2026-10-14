// SPDX-License-Identifier: GPL-3.0-only

package app.relay.agent

import app.relay.core.protocol.Codec
import app.relay.core.protocol.MsgTypes
import app.relay.core.protocol.makeEnvelope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [AgentInbound.kt] 入站帧转换与台账记账的完整单元测试。
 * 覆盖 8 类 Agent 帧（session, event, queue, run, approval, cancel, artifact, ack）的入站解析、
 * 游标就地替换、排序与状态流转。
 */
class AgentInboundTest {

    private val nodeId = "peer-node-1"
    private val sessionId = "s-session-123"

    @Test
    fun nonAgentFrameIsIgnored() {
        val registry = AgentSessionRegistry()
        val env = makeEnvelope(
            from = nodeId,
            type = MsgTypes.MSG,
            payload = Codec.textPayload("hello world"),
        )
        registry.recordInbound(env)
        assertTrue(registry.sessions().isEmpty())
        assertEquals(0, registry.eventCount())
    }

    @Test
    fun recordInboundSession() {
        val registry = AgentSessionRegistry()
        val payload = Codec.agentSessionPayload(
            sessionId = sessionId,
            alias = "my agent",
            agentKind = "codex",
            state = "busy",
            title = "task title",
            startedAt = 1000L,
        )
        val env = makeEnvelope(from = nodeId, type = MsgTypes.AGENT_SESSION, payload = payload)
        registry.recordInbound(env)

        val sessions = registry.sessions()
        assertEquals(1, sessions.size)
        val record = sessions.first()
        assertEquals(nodeId, record.nodeId)
        assertEquals(sessionId, record.sessionId)
        assertEquals("my agent", record.alias)
        assertEquals("codex", record.agentKind)
        assertEquals("busy", record.state)
        assertEquals("task title", record.title)
        assertEquals(1000L, record.startedAt)
        assertFalse(record.announcedOut)
        assertTrue(record.announcedIn)
        assertFalse(record.synthesized)
    }

    @Test
    fun recordInboundEventAndDeduplicateCursor() {
        val registry = AgentSessionRegistry()

        // 乱序收到 cursor 2 然后 cursor 1
        val env2 = makeEnvelope(
            from = nodeId,
            type = MsgTypes.AGENT_EVENT,
            payload = Codec.agentEventPayload(
                sessionId = sessionId,
                cursor = 2L,
                eventType = "tool.completed",
                summary = "second step",
                body = "result 2",
                ref = "tool-2",
            ),
        )
        val env1 = makeEnvelope(
            from = nodeId,
            type = MsgTypes.AGENT_EVENT,
            payload = Codec.agentEventPayload(
                sessionId = sessionId,
                cursor = 1L,
                eventType = "tool.started",
                summary = "first step",
                body = "result 1",
                ref = "tool-1",
            ),
        )

        registry.recordInbound(env2)
        registry.recordInbound(env1)

        val events = registry.events(nodeId, sessionId)
        assertEquals(2, events.size)
        assertEquals(1L, events[0].cursor)
        assertEquals("first step", events[0].summary)
        assertEquals(2L, events[1].cursor)
        assertEquals("second step", events[1].summary)

        // 重复发送 cursor 2，应就地替换而非追加
        val env2Updated = makeEnvelope(
            from = nodeId,
            type = MsgTypes.AGENT_EVENT,
            payload = Codec.agentEventPayload(
                sessionId = sessionId,
                cursor = 2L,
                eventType = "tool.completed",
                summary = "second step updated",
                body = "result 2 updated",
                ref = "tool-2",
            ),
        )
        registry.recordInbound(env2Updated)

        val updatedEvents = registry.events(nodeId, sessionId)
        assertEquals(2, updatedEvents.size)
        assertEquals("second step updated", updatedEvents[1].summary)
    }

    @Test
    fun recordInboundQueue() {
        val registry = AgentSessionRegistry()
        val env = makeEnvelope(
            from = nodeId,
            type = MsgTypes.AGENT_QUEUE,
            payload = Codec.agentQueuePayload(
                taskId = "task-42",
                sessionId = sessionId,
                op = "enqueue",
                prompt = "do some work",
                queuedAt = 2000L,
            ),
        )
        registry.recordInbound(env)

        val tasks = registry.queues(nodeId, sessionId)
        assertEquals(1, tasks.size)
        val q = tasks.first()
        assertEquals("task-42", q.taskId)
        assertEquals("enqueue", q.op)
        assertEquals("do some work", q.prompt)
        assertEquals(2000L, q.queuedAt)
        assertFalse(q.outbound)

        // 未宣告过会话时，队列到达会合成占位会话
        val sess = registry.sessions().firstOrNull()
        assertNotNull(sess)
        assertTrue(sess.synthesized)
    }

    @Test
    fun recordInboundRun() {
        val registry = AgentSessionRegistry()
        val env1 = makeEnvelope(
            from = nodeId,
            type = MsgTypes.AGENT_RUN,
            payload = Codec.agentRunPayload(
                sessionId = sessionId,
                runId = "run-99",
                state = "running",
                at = 3000L,
                note = "executing tool",
            ),
        )
        registry.recordInbound(env1)

        val runs = registry.runs(nodeId, sessionId)
        assertEquals(1, runs.size)
        assertEquals("running", runs.first().state)
        assertEquals("executing tool", runs.first().note)

        // 状态更新就地替换
        val env2 = makeEnvelope(
            from = nodeId,
            type = MsgTypes.AGENT_RUN,
            payload = Codec.agentRunPayload(
                sessionId = sessionId,
                runId = "run-99",
                state = "completed",
                at = 3500L,
                note = "done",
            ),
        )
        registry.recordInbound(env2)

        val runsAfter = registry.runs(nodeId, sessionId)
        assertEquals(1, runsAfter.size)
        assertEquals("completed", runsAfter.first().state)
    }

    @Test
    fun recordInboundApprovalAndAck() {
        val registry = AgentSessionRegistry()
        val env = makeEnvelope(
            from = nodeId,
            type = MsgTypes.AGENT_APPROVAL,
            payload = Codec.agentApprovalPayload(
                approvalId = "appr-1",
                sessionId = sessionId,
                tool = "bash",
                summary = "run rm -rf",
                expiresAt = 9999L,
                choices = "yes,no",
            ),
        )
        registry.recordInbound(env)

        val approvals = registry.approvals(nodeId, sessionId)
        assertEquals(1, approvals.size)
        val appr = approvals.first()
        assertEquals("appr-1", appr.approvalId)
        assertEquals("bash", appr.tool)
        assertNull(appr.status)

        // 收到 ack 推进审批状态
        val ackEnv = makeEnvelope(
            from = nodeId,
            type = MsgTypes.AGENT_ACK,
            payload = Codec.agentAckPayload(
                ackOf = "appr-1",
                kind = "approved",
                at = 4000L,
            ),
        )
        registry.recordInbound(ackEnv)

        val ackRecord = registry.ack(nodeId, "appr-1")
        assertNotNull(ackRecord)
        assertEquals("approved", ackRecord.kind)

        val updatedAppr = registry.approvals(nodeId, sessionId).first()
        assertEquals("approved", updatedAppr.status)
    }

    @Test
    fun recordInboundCancel() {
        val registry = AgentSessionRegistry()
        val env = makeEnvelope(
            from = nodeId,
            type = MsgTypes.AGENT_CANCEL,
            payload = Codec.agentCancelPayload(
                sessionId = sessionId,
                runId = "run-1",
                reason = "user requested abort",
            ),
        )
        registry.recordInbound(env)

        val cancels = registry.cancels(nodeId, sessionId)
        assertEquals(1, cancels.size)
        assertEquals("user requested abort", cancels.first().reason)
    }

    @Test
    fun recordInboundArtifact() {
        val registry = AgentSessionRegistry()
        val env = makeEnvelope(
            from = nodeId,
            type = MsgTypes.AGENT_ARTIFACT,
            payload = Codec.agentArtifactPayload(
                sessionId = sessionId,
                artifactId = "art-1",
                name = "report.pdf",
                fileId = "f-123",
                bytes = 1024L,
                mime = "application/pdf",
            ),
        )
        registry.recordInbound(env)

        val arts = registry.artifacts(nodeId, sessionId)
        assertEquals(1, arts.size)
        val art = arts.first()
        assertEquals("art-1", art.artifactId)
        assertEquals("report.pdf", art.name)
        assertEquals(1024L, art.bytes)
    }
}
