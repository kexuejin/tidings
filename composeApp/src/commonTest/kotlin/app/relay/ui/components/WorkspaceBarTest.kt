// SPDX-License-Identifier: GPL-3.0-only
package app.relay.ui.components

import app.relay.app.model.AgentSessionUi
import app.relay.app.model.WorkspaceUi
import app.relay.core.protocol.AgentSessionPayload
import app.relay.core.protocol.Codec
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkspaceBarTest {

    @Test
    fun testAgentSessionPayloadSerializationWithGitBranchAndSegment() {
        val payload = AgentSessionPayload(
            sessionId = "sess-git-001",
            title = "Refactor network layer",
            wsId = "ws-relay",
            wsTitle = "teahouse-mobile",
            wsPath = "/workspace/src/teahouse-mobile",
            gitBranch = "feature/ipv6-dual-mode",
            segmentId = "seg-42",
        )
        val jsonElement = Codec.agentSessionPayload(
            sessionId = payload.sessionId,
            title = payload.title,
            wsId = payload.wsId,
            wsTitle = payload.wsTitle,
            wsPath = payload.wsPath,
            gitBranch = payload.gitBranch,
            segmentId = payload.segmentId,
        )
        val decoded = Json.decodeFromString<AgentSessionPayload>(jsonElement.toString())

        assertEquals("sess-git-001", decoded.sessionId)
        assertEquals("teahouse-mobile", decoded.wsTitle)
        assertEquals("feature/ipv6-dual-mode", decoded.gitBranch)
        assertEquals("seg-42", decoded.segmentId)
    }

    @Test
    fun testAgentSessionUiModelDefaults() {
        val session = AgentSessionUi(
            peerId = "peer-host",
            sessionId = "sess-01",
            gitBranch = "main",
            segmentId = "seg-1",
        )
        assertEquals("main", session.gitBranch)
        assertEquals("seg-1", session.segmentId)

        val bareSession = AgentSessionUi(
            peerId = "peer-host",
            sessionId = "sess-02",
        )
        assertNull(bareSession.gitBranch)
        assertNull(bareSession.segmentId)
    }
}
