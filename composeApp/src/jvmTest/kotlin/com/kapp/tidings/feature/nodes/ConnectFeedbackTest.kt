// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.nodes

import com.kapp.tidings.domain.BannerMessage
import com.kapp.tidings.domain.BannerTone
import com.kapp.tidings.domain.BannerUi
import com.kapp.tidings.domain.ManualPeerEntry
import com.kapp.tidings.domain.handshakingBannerStillPending
import com.kapp.tidings.domain.manualPeerDisplayName
import com.kapp.tidings.domain.manualPeersWithout
import com.kapp.tidings.domain.model.PeerUi
import com.kapp.tidings.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「连接 / 移除」两条反馈链的领域侧判据。
 *
 * 1. **握手提示的时限**：超时窗口过去后，「已向 x 打招呼，等它回应」必须退场 ——
 *    否则它和弹层里那句「对方 3 秒内没回应」同屏互斥。清的时候只清**同一个目标**的
 *    那一条，别人的提示不能被顺手抹掉。
 * 2. **移除回执**：横幅里的名字必须与详情页菜单项同一来源（[manualPeerDisplayName]）；
 *    摘除判据必须是整条记录（host + port + name），否则同一台机器的另一个端口会被一起删。
 *
 * 反向验证：
 *  - [handshakingBannerStillPending] 改成恒 `true` → [otherBannersAreNotTouched] 变红；
 *  - 改成恒 `false` → [sameTargetIsStillPending] 变红；
 *  - [manualPeersWithout] 改成按 host 过滤 → [removalOnlyDropsTheClickedEntry] 变红；
 *  - 控制器里不调 `expireHandshakingBanner` / 不回执 `PeerRemoved` →
 *    [connectPathSchedulesTheExpiry] / [removalSendsTheReceipt] 变红。
 */
class ConnectFeedbackTest {

    private val relayController = "app/src/commonMain/kotlin/com/kapp/tidings/domain/RelayController.kt"

    private fun handshaking(target: String) =
        BannerUi(BannerMessage.Handshaking(target), BannerTone.Info)

    private fun peer(
        nodeId: String = "laozhou-node",
        nick: String = "老周",
        ip: String = "192.168.1.31",
        udpPort: Int = 17_878,
    ) = PeerUi(nodeId = nodeId, nick = nick, ip = ip, udpPort = udpPort, online = true)

    // ── 握手提示 ─────────────────────────────────────────────────────────────

    @Test
    fun sameTargetIsStillPending() {
        assertTrue(handshakingBannerStillPending(handshaking("192.168.1.31:17878"), "192.168.1.31:17878"))
    }

    @Test
    fun otherBannersAreNotTouched() {
        // 换了目标、或者换成了别的提示：那不是这次尝试留下的，过期任务不许动。
        assertFalse(handshakingBannerStillPending(handshaking("192.168.1.31:17878"), "192.168.1.99:17878"))
        assertFalse(handshakingBannerStillPending(null, "192.168.1.31:17878"))
        assertFalse(
            handshakingBannerStillPending(
                BannerUi(BannerMessage.PeerRemoved("老周的旧笔记本"), BannerTone.Info),
                "192.168.1.31:17878",
            ),
        )
    }

    @Test
    fun connectPathSchedulesTheExpiry() {
        val text = GuardSourceScan.stripComments(GuardSourceScan.read(relayController))
        assertTrue(
            text.contains("expireHandshakingBanner(\"\$host:\$port\")"),
            "设置握手横幅之后必须排一次过期；否则横幅永远停在「等它回应」，与超时红字同屏互斥。",
        )
        val body = GuardSourceScan.bracedBlockAfter(text, "private fun expireHandshakingBanner")
        assertTrue(body.contains("delay(CONNECT_ATTEMPT_TIMEOUT_MS)"), "过期窗口用共享常量。")
        assertTrue(body.contains("st.copy(banner = null)"), "到期就清掉横幅。")
        assertTrue(
            body.contains("handshakingBannerStillPending("),
            "只清同一个目标的提示 —— 别人的横幅不能被上一拍的连接动作删掉。",
        )
    }

    // ── 移除 ─────────────────────────────────────────────────────────────────

    @Test
    fun removalOnlyDropsTheClickedEntry() {
        val udp = ManualPeerEntry(host = "192.168.1.31", port = 17_878, name = "旧笔记本")
        val agent = ManualPeerEntry(host = "192.168.1.31", port = 17_891, name = "agentNode")
        assertEquals(listOf(agent), manualPeersWithout(listOf(udp, agent), udp))
        assertEquals(emptyList(), manualPeersWithout(listOf(udp), udp))
        // 不在表里的那条：原样返回（点错一次不会把表清空）。
        assertEquals(listOf(udp), manualPeersWithout(listOf(udp), agent))
    }

    @Test
    fun receiptNameIsTheSameAsTheMenu() {
        // 握过手：菜单与横幅都用对端的显示名。
        assertEquals(
            "老周",
            manualPeerDisplayName(ManualPeerEntry("192.168.1.31", 17_878, "旧笔记本"), listOf(peer())),
        )
        // 没握过手：用用户起的名字。
        assertEquals(
            "旧笔记本",
            manualPeerDisplayName(ManualPeerEntry("192.168.1.31", 17_878, "旧笔记本"), emptyList()),
        )
        // 没名字：回落地址，与列表行标题同一句。
        assertEquals(
            "192.168.1.31:17878",
            manualPeerDisplayName(ManualPeerEntry("192.168.1.31", 17_878), emptyList()),
        )
    }

    @Test
    fun removalSendsTheReceipt() {
        val text = GuardSourceScan.stripComments(GuardSourceScan.read(relayController))
        val body = GuardSourceScan.bracedBlockAfter(text, "override fun removeManualPeer(")
        assertTrue(body.contains("manualPeersWithout("), "摘除必须走那条整条记录的判据。")
        assertTrue(
            body.contains("PeerRemoved(name)"),
            "移除后要有回执：一行从列表上消失本身不解释任何事。",
        )
    }
}
