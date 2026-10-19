// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.transport

import app.relay.core.protocol.MsgTypes
import app.relay.core.protocol.Profile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * task-34：手机侧诊断报告的口径。
 *
 * 两条最关键：① `skipReason` 必须原样带出来（那是"没拨"的唯一线索）；
 * ② 报告里**绝不能有凭据**（pin / 密钥）—— 它是给 `run-as cat` 看的文件。
 */
class LinkDiagnosticsTest {

    private fun record(nodeId: String, ip: String) = PeerRecord(
        profile = Profile(
            nodeId = nodeId,
            nick = nodeId,
            company = "",
            dept = "",
            team = "",
            avatar = 0,
            profileRev = 0,
            host = "h",
            platform = "linux",
            tcpPort = 17879,
            tlsPort = 17880,
            ver = "relay-0.1.0",
            caps = listOf(MsgTypes.CAP_LINK),
        ),
        ip = ip,
        udpPort = 17878,
        lastSeen = 1L,
        online = true,
    )

    @Test
    fun theReportCarriesTheSkipReasonAndTheDialCounters() {
        val snapshot = LinkSnapshot(
            carriers = mapOf("host-a" to "plaintext"),
            total = 1,
            dial = mapOf(
                "host-a" to LinkDialStats(attempts = 3, failures = 3, lastTarget = "2001:db8::9:17880", lastError = "tls-handshake-failed"),
                "host-b" to LinkDialStats(skipReason = "no-pin"),
            ),
        )
        val report = buildLinkDiagnostics(
            selfNodeId = "phone",
            linkEnabled = true,
            snapshot = snapshot,
            peers = listOf(record("host-a", "192.168.2.82"), record("host-b", "2001:db8::1")),
            now = 42L,
        )

        assertEquals(1, report.linkTotal)
        val a = report.peers.first { it.nodeId == "host-a" }
        assertEquals(true, a.linkConnected)
        assertEquals("plaintext", a.linkCarrier)
        assertEquals(3, a.dialAttempts)
        assertEquals(3, a.dialFailures)
        assertEquals("tls-handshake-failed", a.lastError)
        assertEquals("2001:db8::9:17880", a.dialLastTarget)
        assertEquals(null, a.skipReason)

        val b = report.peers.first { it.nodeId == "host-b" }
        assertEquals(false, b.linkConnected)
        assertEquals(0, b.dialAttempts)
        assertEquals("no-pin", b.skipReason, "没拨的原因必须原样带出来")
    }

    @Test
    fun theJsonIsHumanReadableAndCarriesNoCredentials() {
        val json = linkDiagnosticsJson(
            selfNodeId = "phone",
            linkEnabled = false,
            snapshot = LinkSnapshot(dial = mapOf("host-b" to LinkDialStats(skipReason = "no-pin"))),
            peers = listOf(record("host-b", "2001:db8::1")),
            now = 7L,
        )
        assertTrue(json.contains("\"skipReason\": \"no-pin\""), "字段名要能被 adb/人眼直接读：$json")
        assertTrue(json.contains("\"linkEnabled\": false"), "显式写出 false，不靠缺项表达：$json")
        assertTrue(!json.contains("sha256:"), "报告里绝不能出现 pin：$json")
    }
}
