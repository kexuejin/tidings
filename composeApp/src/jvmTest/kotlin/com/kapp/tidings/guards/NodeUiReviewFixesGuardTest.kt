// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.guards

import com.kapp.tidings.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 本轮 UI review 修复的源码级守卫（本仓没有 Compose UI 测试基建，只能在源码层钉住）。
 *
 * 三条都**不可能**由现有门禁抓到：
 *  - 横幅画在哪个分支里：`designStateSlotGate` 的分母是"状态槽"，横幅是跨槽的全局元素；
 *  - 状态文字的颜色：token/规格门禁只比取值集合，不计算前景/背景对比度；
 *  - 命中区高度：token 门禁看的是 dimension 字面量，按钮 padding 组合出的**有效高度**不在量具里。
 *
 * 反向验证：把横幅移回列表分支 / 把彩色还给状态文字 / 删掉任一 `minimumInteractiveComponentSize()`，
 * 对应用例即变红。
 */
class NodeUiReviewFixesGuardTest {

    private val nodesScreen = "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/nodes/NodesScreen.kt"
    private val nodeDetail = "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/nodes/NodeDetail.kt"
    private val nodeRows = "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/nodes/NodeRows.kt"
    private val cabinetView = "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/cabinet/PeerCabinetView.kt"
    private val desktopNodes = "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopNodesPane.kt"
    private val controller = "app/src/commonMain/kotlin/com/kapp/tidings/domain/RelayController.kt"

    private fun live(path: String) = GuardSourceScan.stripComments(GuardSourceScan.read(path))

    @Test
    fun bannerHostSitsAboveTheDetailAndListBranches() {
        val text = live(nodesScreen)
        val hostAt = text.indexOf("state.banner?.let")
        val branchAt = text.indexOf("if (detailItem != null)")
        assertTrue(hostAt >= 0, "节点页必须有一个横幅宿主（否则失败/回执没有出口）。")
        assertTrue(branchAt >= 0, "找不到详情/列表分支 —— 结构变了，这条守卫要跟着改。")
        assertTrue(
            hostAt < branchAt,
            "横幅必须在详情/列表**两个分支之上**：画在列表分支里时，用户停在节点详情就看不到" +
                "绑定失败、握手提示与移除回执 —— 那正是 review 报的 P1。",
        )
    }

    @Test
    fun statusTextIsNotCarriedByLowContrastAccentColors() {
        val rows = live(nodeRows)
        assertFalse(
            rows.contains("item.online -> MaterialTheme.accents.online"),
            "列表行的状态文字不能用 accents.online：绿字压白底实测 3.41:1，低于 AA 的 4.5:1。" +
                "状态由三色点 + 文字（在线/离线/没有回应）表达，颜色只做辅助。",
        )
        assertFalse(
            rows.contains("item.never -> MaterialTheme.colorScheme.error"),
            "「没有回应」的红字在白底只有 4.17:1，同样不到 AA；红点照样在头像角上。",
        )
        val detail = live(nodeDetail)
        assertTrue(
            detail.contains("container = MaterialTheme.accents.online.copy(alpha = 0.18f)"),
            "详情页「在线」药丸的底仍是 18% 绿（原型 color-mix）。",
        )
        assertFalse(
            Regex("container = MaterialTheme\\.accents\\.online\\.copy\\(alpha = 0\\.18f\\)\\s*\\n\\s*content = MaterialTheme\\.accents\\.online")
                .containsMatchIn(detail),
            "绿字压 18% 绿底实测 ≈2.8:1：药丸文字要回到 onSurface，否则同一屏最该读清的两个字最看不清。",
        )
    }

    @Test
    fun inlineSmallButtonsCarryA48dpHitTarget() {
        // 小档按钮自身（12sp + 6px padding）只撑到 ~32dp，低于触屏 48dp 下限。
        val sites = mapOf(
            nodeRows to "mDNS 行的「连接」",
            nodeDetail to "详情页的复制 / 移除 / 连接",
            nodesScreen to "「清理没有回应的」",
            cabinetView to "文件柜底栏的下载 / 上传",
            desktopNodes to "桌面左栏的「清理没有回应的」",
        )
        for ((path, what) in sites) {
            assertTrue(
                live(path).contains("minimumInteractiveComponentSize()"),
                what + " 必须补 minimumInteractiveComponentSize()：视觉尺寸不变、命中区到 48dp。",
            )
        }
    }

    @Test
    fun manualAddressHasAReconnectAffordance() {
        assertTrue(
            live(controller).contains("fun reconnectManualPeer(entry: ManualPeerEntry)"),
            "控制器要有「重连一条已保存地址」的入口：mDNS 行被判重滤掉后，这是唯一能把设备连回来的动作" +
                "（重新添加会被判重复）。",
        )
        val detail = live(nodeDetail)
        assertTrue(detail.contains("onReconnectPeer"), "详情页要收这个回调，否则按钮无处可点。")
        assertTrue(
            detail.contains("Res.string.disc_connect"),
            "按钮文案复用 disc_connect（连接）；不要新造一个近义词。",
        )
        assertTrue(
            live(nodesScreen).contains("onReconnectPeer = onReconnectPeer"),
            "手机壳要把回调穿进详情体（App.kt 的最终接线由 lead 补）。",
        )
    }
}
