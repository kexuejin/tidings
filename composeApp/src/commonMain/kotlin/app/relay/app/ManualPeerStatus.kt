// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import app.relay.app.model.PeerUi
/**
 * 一个手动节点的处境 —— 单独成文件只为能被直接单测（同 `MdnsNaming.kt` / `QrPairing.kt`）。
 *
 * ## 为什么需要它
 *
 * 「加入」页原来只是把 `settings.manualPeers` 原样列出来：用户换了网段之后，旧网段那些
 * 地址永远连不上，但它们会**一直留在列表里**，看起来和能用的那些一模一样。用户看到的
 * 是"我明明配过了，怎么还是发不出去"，而界面上一句解释都没有。
 *
 * ## 三种处境必须分开
 *
 * - **有过回应**：这个地址上学到过节点。哪怕它现在离线也**要留着** —— 对方只是关机了，
 *   开机就会回来，删掉它等于每次对方重启都要用户重填一次 IP。
 * - **从来没有回应过**：学到过零个节点。这多半是地址写错、或者那个网段已经不用了。
 *   这才是可以清理的。
 * - 注意"没有回应"和"离线"**不是一回事**，混成一个字段会让上面两条规则打架。
 */
data class ManualPeerStatus(
    val entry: ManualPeerEntry,
    /** 这个地址上学到的节点 id；`null` = 从来没有回应过。 */
    val nodeId: String? = null,
    /** 学到过、但现在不在线。 */
    val offline: Boolean = false,
) {
    /** 从来没有回应过 —— 界面显示「没有回应」，也是「清理」按钮的判定条件。 */
    val unreachable: Boolean get() = nodeId == null
}

/**
 * 把手动节点和节点表对上号。
 *
 * 匹配条件是 `(地址, UDP 端口)` **两个都比**，不是只看地址：同一台机器上跑两个节点
 * （比如桌面茶话间 17878 与 agentNode 17891）时，只看 IP 会把"填错了端口的那条"
 * 误判成"有过回应"，于是它永远不会被标出来。
 *
 * [PeerUi.host] 那条分支是给"用户填的是主机名而不是 IP"准备的；IP 优先，
 * 因为协议里 `host` 是设备名，不是地址。
 *
 * **端口未知（`udpPort == 0`）时不判"没有回应"**：那等于把"我不知道"说成"它从来没
 * 回应过"，而后者是一个强断言（界面上会写「没有回应」，还会进清理名单）。
 * 同「不知道就别说话」这条规矩 —— 文件气泡不知道投递状态时不写「未知」。
 * 生产路径上 `udpPort` 总是有的（`PeerRecord.toUi()` 从握手包里拿），
 * 这条分支是给替身/预览用的，但方向必须是"少说"而不是"乱说"。
 */
internal fun manualPeerStatuses(
    entries: List<ManualPeerEntry>,
    peers: List<PeerUi>,
): List<ManualPeerStatus> = entries.map { entry ->
    val match = peers.firstOrNull { it.ip == entry.host && it.udpPortMatches(entry.port) }
        ?: peers.firstOrNull { it.host == entry.host && it.udpPortMatches(entry.port) }
    ManualPeerStatus(
        entry = entry,
        nodeId = match?.nodeId,
        offline = match != null && !match.online,
    )
}

private fun PeerUi.udpPortMatches(port: Int): Boolean = udpPort == port || udpPort == 0

