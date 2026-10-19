// SPDX-License-Identifier: GPL-3.0-only
package app.relay.feature.agents

import app.relay.app.model.AgentApprovalUi
import app.relay.core.protocol.AgentApprovalPayload
import app.relay.core.protocol.Codec
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AgentApprovalCardsTest {

    @Test
    fun testRiskLevelResolution() {
        assertEquals("L3", resolveRiskLevel(null, "bash"))
        assertEquals("L3", resolveRiskLevel(null, "exec_command"))
        assertEquals("L3", resolveRiskLevel(null, "shell"))
        assertEquals("L3", resolveRiskLevel(null, "terminal"))
        assertEquals("L2", resolveRiskLevel(null, "write_file"))
        assertEquals("L2", resolveRiskLevel(null, "replace_file_content"))
        assertEquals("L2", resolveRiskLevel(null, "delete_item"))
        assertEquals("L1", resolveRiskLevel(null, "view_file"))
        assertEquals("L1", resolveRiskLevel(null, "search_web"))
        assertEquals("L1", resolveRiskLevel(null, "unknown_tool"))

        // Explicit risk override always wins
        assertEquals("L3", resolveRiskLevel("l3", "view_file"))
        assertEquals("L1", resolveRiskLevel("L1", "bash"))
    }

    @Test
    fun testAgentApprovalPayloadWithRiskAndDiff() {
        val payload = AgentApprovalPayload(
            approvalId = "appr-001",
            sessionId = "sess-001",
            tool = "bash",
            summary = "rm -rf /tmp/cache",
            expiresAt = 1700000000000L,
            choices = "allow,deny,allow_session",
            riskLevel = "L3",
            diff = "--- /tmp/cache\n+++ /dev/null",
        )
        val jsonElement = Codec.agentApprovalPayload(
            approvalId = payload.approvalId,
            sessionId = payload.sessionId,
            tool = payload.tool,
            summary = payload.summary,
            expiresAt = payload.expiresAt,
            choices = payload.choices,
            riskLevel = payload.riskLevel,
            diff = payload.diff,
        )
        val jsonString = jsonElement.toString()
        val decoded = Json.decodeFromString<AgentApprovalPayload>(jsonString)

        assertEquals("appr-001", decoded.approvalId)
        assertEquals("sess-001", decoded.sessionId)
        assertEquals("bash", decoded.tool)
        assertEquals("L3", decoded.riskLevel)
        assertEquals("--- /tmp/cache\n+++ /dev/null", decoded.diff)
    }

    @Test
    fun testAgentApprovalUiFields() {
        val ui = AgentApprovalUi(
            approvalId = "appr-1",
            sessionId = "sess-1",
            peerId = "node-1",
            tool = "bash",
            summary = "Run git status",
            expiresAt = 1000L,
            choices = "allow,deny",
            status = "pending",
            riskLevel = "L3",
            diff = "diff --git a/file b/file",
        )
        assertEquals("L3", ui.riskLevel)
        assertNotNull(ui.diff)
        assertEquals("diff --git a/file b/file", ui.diff)
    }

    @Test
    fun testApprovalPermissionModel() {
        fun canApprove(
            selfNodeId: String,
            approverPeerId: String,
            grant: app.relay.app.model.GrantUi?,
        ): Boolean {
            return (approverPeerId == selfNodeId) ||
                (grant?.isMyDevice == true) ||
                (grant?.access == app.relay.core.protocol.AgentGrantAccess.OWNER) ||
                (grant?.access == app.relay.core.protocol.AgentGrantAccess.ADMIN && grant.delegatedApproval)
        }

        fun makeGrant(
            peerId: String,
            isMyDevice: Boolean = false,
            access: String = app.relay.core.protocol.AgentGrantAccess.RW,
            delegatedApproval: Boolean = false,
        ) = app.relay.app.model.GrantUi(
            peerId = peerId,
            agentKey = null,
            scope = "all",
            state = "approved",
            at = 1000L,
            isMyDevice = isMyDevice,
            access = access,
            delegatedApproval = delegatedApproval,
        )

        val selfNode = "node-self"
        // 1. Host approval
        kotlin.test.assertTrue(canApprove(selfNode, selfNode, null))

        // 2. Private trust circle (my device)
        val myDeviceGrant = makeGrant(peerId = "node-phone", isMyDevice = true, access = app.relay.core.protocol.AgentGrantAccess.RW)
        kotlin.test.assertTrue(canApprove(selfNode, "node-phone", myDeviceGrant))

        // 3. Owner role
        val ownerGrant = makeGrant(peerId = "node-owner", isMyDevice = false, access = app.relay.core.protocol.AgentGrantAccess.OWNER)
        kotlin.test.assertTrue(canApprove(selfNode, "node-owner", ownerGrant))

        // 4. Admin with delegated approval enabled
        val adminWithApproval = makeGrant(peerId = "node-admin1", isMyDevice = false, access = app.relay.core.protocol.AgentGrantAccess.ADMIN, delegatedApproval = true)
        kotlin.test.assertTrue(canApprove(selfNode, "node-admin1", adminWithApproval))

        // 5. Admin WITHOUT delegated approval -> cannot approve
        val adminNoApproval = makeGrant(peerId = "node-admin2", isMyDevice = false, access = app.relay.core.protocol.AgentGrantAccess.ADMIN, delegatedApproval = false)
        kotlin.test.assertFalse(canApprove(selfNode, "node-admin2", adminNoApproval))

        // 6. External collaborator (rw) -> cannot approve
        val collabGrant = makeGrant(peerId = "node-dev", isMyDevice = false, access = app.relay.core.protocol.AgentGrantAccess.RW)
        kotlin.test.assertFalse(canApprove(selfNode, "node-dev", collabGrant))

        // 7. Observer (ro) -> cannot approve
        val observerGrant = makeGrant(peerId = "node-observer", isMyDevice = false, access = app.relay.core.protocol.AgentGrantAccess.RO)
        kotlin.test.assertFalse(canApprove(selfNode, "node-observer", observerGrant))
    }
}

