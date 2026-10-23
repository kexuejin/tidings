// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.guards

// 搬到 com.kapp.tidings.guards 之后，领域层的同包符号不再自动可见 —— 必须显式 import。
// （它原来靠 `package com.kapp.tidings.domain` 隐式访问，那正是层级错位掩盖的依赖。）
import com.kapp.tidings.domain.peerCandidatesFrom
import com.kapp.tidings.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **出码必须用「宣告」那份地址，不是「本地」那份。**
 *
 * ## 两份地址的分工（见 `NetworkUi` 的 KDoc）
 *
 * | 字段 | 内容 | 用途 |
 * |---|---|---|
 * | `localAddresses` | **刻意窄**：过滤掉 `utun` / `feth` / `tailscale` | 广播、同网段判断 |
 * | `advertisedAddresses` | 含隧道网段（Tailscale `100.x`、ZeroTier `10.x`） | 单播告知「该往哪儿拨」、**出码** |
 *
 * ## 真机症状
 *
 * 手机卸载重装 ⇒ nodeId 变了 ⇒ 那台机器上的 `known-peers` 是空的。三条发现路径同时断：
 *
 *  1. 受限广播 `255.255.255.255` —— 出不了本机子网；
 *  2. 子网广播 —— `utun` 是 `/32` 点对点，`broadcast` 等于自己，被 `subnetBroadcastAddresses`
 *     那行「`/31` `/32` 没有广播语义」判掉；
 *  3. mDNS —— 同样限于局域网。
 *
 * 那时唯一还能用的就是**扫桌面端的码**，而码里恰好没有 Tailscale 地址
 * （五处出码点全用了 `localAddresses`）⇒ 手机永远连不上那台走 Tailscale 的 Mac。
 *
 * 讽刺的是源码注释写着「配对链接用**全部候选**……只印一个 IPv4 时，手机换到移动网就只剩
 * 手动填地址一条路」—— **意图是对的，传进去的列表少了一档**。
 */
class QrUsesAdvertisedAddressesTest {

    /**
     * 五个出码/出链接点都必须传 `advertisedAddresses`。
     *
     * 这是**源码级守卫**：这条纪律是「多处必须一致」，类型系统表达不出来，
     * 而漏一处的后果是「这张码在那台设备上永远连不通」。
     */
    @Test
    fun everyPairingPayloadCallSiteUsesTheAdvertisedList() {
        val files = listOf(
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopConversationsPane.kt",
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopShell.kt",
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopStatusBar.kt",
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/nodes/NodesScreen.kt",
        )
        val offenders = mutableListOf<String>()
        for (path in files) {
            val text = readRepoFile(path)
            // 只看活代码：注释里正当提到 localAddresses（解释分工）不该被判红。
            val live = text.lineSequence()
                .filterNot { it.trimStart().startsWith("//") }
                .joinToString("\n")
            // 出码/出链接那一行长这样：`addresses = state.network.localAddresses,`
            for (line in live.lineSequence()) {
                val t = line.trim()
                if (!t.startsWith("addresses = ")) continue
                if (t.contains("localAddresses")) offenders += "$path: $t"
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "出码必须用 advertisedAddresses（含 Tailscale/ZeroTier），" +
                "用 localAddresses 会让走隧道的手机永远连不上：\n" + offenders.joinToString("\n"),
        )
    }

    /**
     * **隧道地址确实会被收进候选池。**
     *
     * 上一条守「调用点传对了列表」，这条守「传进去之后不会被过滤掉」——
     * `peerCandidatesFrom` 只该丢掉回环 / 链路本地 / 多播 / 非法串，不该丢 `100.x`。
     */
    @Test
    fun tailscaleAddressesSurviveCandidateBuilding() {
        val candidates = peerCandidatesFrom(
            addresses = listOf(
                "100.66.238.121",                  // Tailscale
                "192.168.2.82",                    // LAN
                "2001:db8:2020:cb44::1",          // 公网 v6
                "fe80::1",                         // 链路本地：该被丢
                "127.0.0.1",                       // 回环：该被丢
            ),
            port = 17878,
        )
        val hosts = candidates.map { it.host }.toSet()
        assertTrue("100.66.238.121" in hosts, "Tailscale 的 100.x 必须留在候选池里")
        assertTrue("192.168.2.82" in hosts)
        assertTrue("2001:db8:2020:cb44::1" in hosts)
        assertTrue("fe80::1" !in hosts, "链路本地连不通，不该进码")
        assertTrue("127.0.0.1" !in hosts, "回环不该进码")
    }
}

/**
 * 读仓库内文件。
 *
 * 原先自己拼 `../` / `../../`（"测试运行目录是模块根，仓库根在上两级"）——
 * 那是个隐含事实：换模块、换任务、换 Gradle 配置都会静默失效，而症状是
 * `error("读不到 …")`，看起来像文件被删了。改走 GuardSourceScan 的仓库根探测。
 */
private fun readRepoFile(path: String): String = GuardSourceScan.read(path)