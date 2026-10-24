// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.cabinet

import com.kapp.tidings.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 文件柜的两处**接线**（本仓没有 Compose UI 测试基建，只能在源码块上钉）：
 *
 * 1. 桌面文件柜必须有横幅宿主。上传/下载开始、最近有人放进来、停止共享、下载被拒
 *    四类反馈都走 `state.banner`，手机端 `CabinetScreen` 有自己的那一条；
 *    桌面 `CabinetPane` 过去一条都没有 —— 用户点了「上传」，界面上一个字都不出现。
 * 2. 超时哨兵必须有本地化映射。漏了它，`timeout` 这个 token 会原样印在界面上。
 *
 * 反身性：删掉 `CabinetPane` 里的 `state.banner?.let` 那段、或删掉
 * `PeerCabinetView.cabinetReasonText` 里那条 `CABINET_ERR_TIMEOUT` 分支，
 * 对应用例立刻变红。
 */
class CabinetBannerHostGuardTest {

    @Test
    fun theDesktopCabinetPaneHostsTheWindowBanner() {
        val live = GuardSourceScan.stripComments(GuardSourceScan.read(DESKTOP_PANE))
        assertTrue(
            live.contains("state.banner?.let"),
            "桌面文件柜又没有横幅宿主了：" +
                "上传/下载/停止共享四类反馈在桌面会一个字都不出现",
        )
        assertTrue(
            live.contains("AppBanner("),
            "横幅宿主必须用共用件 AppBanner（不许在文件柜里另画一条）",
        )
        assertTrue(
            live.contains("controller.dismissBanner()"),
            "横幅必须能关：onDismiss 接 controller，缺了它用户只能等它自己消失",
        )
    }

    @Test
    fun theTimeoutSentinelIsLocalizedInsteadOfPrintedAsAToken() {
        val live = GuardSourceScan.stripComments(GuardSourceScan.read(PEER_VIEW))
        assertTrue(
            live.contains("CABINET_ERR_TIMEOUT -> stringResource"),
            "超时哨兵没有映射成文案 —— 界面会把 `timeout` 这个协议 token 直接印给用户",
        )
        assertTrue(
            live.contains("cabinet_reason_timeout"),
            "超时文案必须走 resources（两侧都要有），不许在 Composable 里写死一句话",
        )
    }

    private companion object {
        const val DESKTOP_PANE =
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/CabinetPane.kt"
        const val PEER_VIEW =
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/cabinet/PeerCabinetView.kt"
    }
}
