// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.guards

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 免批规则读撤（cap `atr1`）的**接线**守卫（住在 :composeApp —— 它读 UI 侧源码）。
 *
 * ## 守的是什么
 *
 * 免批表住宿主本机（`AgentToolApprovalStore`），此前只有桌面设置面板一份可见面；
 * 这次给它补了跨端读撤（帧族 + slice + 缝）。三条纪律类型系统表达不了：
 *
 * 1. **手机壳必须接**：手机不发请求就永远拿不到快照 —— 「免批表手机上不可见」
 *    正是这次要销的账。漏接的形态是编译过、测试绿、手机上仍然什么都看不见。
 * 2. **门控必须在壳侧写出来**：对不声明 `atr1` 的对端不发请求 —— 门控漏了，
 *    读请求就会打向旧宿主然后白等（读不入队，代价只是等，但徽标式白等也不行）。
 * 3. **共享视图真渲染**：区块只存在于 `WorkspaceDetailView` 的参数里不算数，
 *    必须真的画 `ToolApprovalsSection` —— 「只共享一半」（合了参数、忘了渲染）
 *    就是分叉从没合的地方长回来的形状（§2.6）。
 */
class ToolApprovalsWiringGuardTest {

    @Test
    fun thePhoneShellWiresTheCrossDeviceSource() {
        val live = GuardSourceScan.stripComments(
            GuardSourceScan.read("composeApp/src/commonMain/kotlin/com/kapp/tenon/App.kt"),
        )
        assertTrue(
            "toolApprovalsSourceOf" in live,
            "手机壳必须把免批规则缝接到 AgentConsoleScreen —— 漏了它，免批表在手机上仍然不可见",
        )
        assertTrue(
            "RelayToolApprovalsSource(" in live,
            "手机壳的 source 工厂必须产出跨端实现（帧快照），而不是桌面那份同进程直读",
        )
        assertTrue(
            "CAP_AGENT_TOOL_RULES" in live,
            "source 工厂必须先看对端 caps（atr1）：对旧宿主不发请求 —— 门控漏了就是一次白等",
        )
        assertTrue(
            "onRequestToolRules" in live,
            "手机壳必须把「拉一次快照」接上（controller.requestToolRules）—— " +
                "source 只会读缓存，没人发请求就永远没有数据",
        )
    }

    @Test
    fun theSettingsHomeHasTheHostAuthEntry() {
        val home = GuardSourceScan.stripComments(
            GuardSourceScan.read(
                "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/settings/SettingsHomeScreen.kt",
            ),
        )
        assertTrue(
            "SETTINGS_PAGE_HOST_AUTH" in home,
            "设置首页必须有「宿主授权」行 —— 免批规则的唯一入口不该藏在智能体工作区详情两层深",
        )
        val app = GuardSourceScan.stripComments(
            GuardSourceScan.read("composeApp/src/commonMain/kotlin/com/kapp/tenon/App.kt"),
        )
        assertTrue(
            "SETTINGS_PAGE_HOST_AUTH -> SettingsHostAuthSubpage" in app,
            "设置路由必须真的挂上宿主授权子页 —— 键存在但不路由 = 死键",
        )
        assertTrue(
            "hostAuthPeers" in app,
            "宿主清单必须与 source 工厂同一个 caps 谓词来源 —— 两处各写一遍迟早漂",
        )
        val screen = GuardSourceScan.stripComments(
            GuardSourceScan.read(
                "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/settings/SettingsScreen.kt",
            ),
        )
        assertTrue(
            "hostAuthPeerCount = hostAuthPeerCount" in screen,
            "SettingsScreen 必须把宿主计数转发给首页 —— 真机走查抓到过：App 算了、" +
                "首页参数有默认值 0，中间那一段没接 ⇒ 行永远显示「0 台宿主」" +
                "（AGENTS §2.6：只接线一半，分叉从没合的地方长回来）",
        )
        val subpage = GuardSourceScan.stripComments(
            GuardSourceScan.read(
                "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/settings/SettingsHostAuthSubpage.kt",
            ),
        )
        assertTrue(
            "ToolApprovalsSection(" in subpage,
            "宿主授权子页必须真的渲染共享的免批规则区块（§2.6：内容共享，不是再抄一份）",
        )
    }

    @Test
    fun theSharedViewActuallyRendersTheSection() {
        val live = GuardSourceScan.stripComments(
            GuardSourceScan.read(
                "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/agents/WorkspaceDetailView.kt",
            ),
        )
        assertTrue(
            "ToolApprovalsSection(" in live,
            "工作区详情必须真的渲染共享的免批规则区块 —— 参数存在但不画 = 只共享了一半（§2.6）",
        )
        assertTrue(
            "source == null" in live || "toolApprovalsSource != null" in live,
            "区块必须以「缝为 null ⇒ 整块不画」的方式诚实（读不到 ≠ 没有规则）",
        )
    }
}
