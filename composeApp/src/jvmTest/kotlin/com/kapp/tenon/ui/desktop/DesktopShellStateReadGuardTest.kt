// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.ui.desktop

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Phase 4 桌面壳「整状态只作默认派生」守卫。
 *
 * 迁移后的桌面壳/窗格保留 `state: RelayUiState? = null`，但它**只许**出现在参数默认值表达式里：
 * 切片参数默认从它派生，预览/截图门禁不传切片时行为不变。屏体里再出现一次 `state` 读点，
 * 就等于订阅面没真的断开 —— 根换了窄片、屏又在读整状态，重组风暴只是换了个地方。
 *
 * 反向验证：在任一只读窗格函数体里写一句 `val peek = state?.peers`，本用例变红。
 */
class DesktopShellStateReadGuardTest {

    private val files = listOf(
        "DesktopShell.kt",
        "DesktopChatPane.kt",
        "DesktopConversationsPane.kt",
        "DesktopNodesPane.kt",
        "DesktopStatusBar.kt",
        "DesktopChrome.kt",
        "DesktopFilesPane.kt",
        "CabinetPane.kt",
    ).map { "composeApp/src/commonMain/kotlin/com/kapp/tenon/ui/desktop/" + it } +
        listOf(
            "desktopApp/src/main/kotlin/com/kapp/tenon/desktop/DesktopHosting.kt",
            "desktopApp/src/main/kotlin/com/kapp/tenon/desktop/DesktopTrustedDevicesPanel.kt",
        )

    private val standaloneState = Regex("(?<![.\\w])state(?![A-Za-z0-9_])")

    private val allowed = listOf(
        Regex("^\\s*public fun FileCardUi\\.stateLabel.*$"),
        Regex("^\\s*\\w+Slice: \\w+Slice = state\\?\\.to\\w+Slice\\(\\) \\?: \\w+Slice\\(\\),$"),
        Regex("^\\s*val \\w+Slice: \\w+Slice = state\\.to\\w+Slice\\(\\),$"),
        Regex("^\\s*state: RelayUiState\\? = null,$"),
        Regex("^\\s*desktopBadges: Map<DesktopView, Int> = if \\(state != null\\) aggregateDesktopBadges\\(state\\) else emptyMap\\(\\),$"),
        Regex("^\\s*(private |internal )?fun aggregateDesktopBadges\\(state: RelayUiState\\).*$"),
        Regex("^\\s*internal fun nodeEntries\\(state: RelayUiState\\).*$"),
        Regex("^\\s*nodeEntries\\(state\\.to\\w+Slice\\(\\).*$"),
        Regex("^\\s*state = (?!state,).*$"),
        Regex("^\\s*state = .*$"),
        Regex("^\\s*\\w+\\(.*state = .*$"),
    )

    @Test
    fun wholeStateSurvivesOnlyInDefaultExpressions() {
        val offenders = mutableListOf<String>()
        for (path in files) {
            val source = GuardSourceScan.stripComments(GuardSourceScan.read(path))
            source.split("\n").forEachIndexed { index, line ->
                if (!standaloneState.containsMatchIn(line)) return@forEachIndexed
                if (allowed.any { it.matches(line.trimEnd()) }) return@forEachIndexed
                offenders += path.substringAfterLast("/") + ":" + (index + 1) + "  " + line.trim()
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "桌面壳/窗格的 state 只许出现在参数默认值表达式里；屏体读整状态 = 订阅面没断开。命中：" + offenders.joinToString(" | "),
        )
    }
}
