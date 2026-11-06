// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * §9 快捷指令的**接线守卫**：桥装上了吗、`/stop` 词表只有一份、消费必有可见回执。
 * 摘掉任何一环，编译照样绿 —— 真机形状是「打了 /status，界面一句话不说」。
 */
class QuickActionWiringGuardTest {

    private val orchestrator = GuardSourceScan.stripComments(
        GuardSourceScan.read("orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopWorkerOrchestrator.kt"),
    )

    private val extension = GuardSourceScan.livePackageOf("orchestration/src/main/kotlin/com/kapp/tenon/orchestration")

    @Test
    fun theQuickActionBridgeIsInstalledAndCleared() {
        assertTrue(
            extension.contains("controller.onQuickAction = { convId, text ->"),
            "快捷指令桥必须装上（§9.2）：不装 = /status 在真机上静默无响应",
        )
        assertTrue(
            extension.contains("controller.onQuickAction = null"),
            "快捷指令桥必须在 detach 里清空（与其它桥同纪律）",
        )
    }

    @Test
    fun stopSharesTheSingleAbortWordList() {
        assertTrue(
            orchestrator.contains("\"/stop\""),
            "/stop 必须进 AbortSignals 词表 —— 它与文本 stop 共用同一执行路径与回执（§9.1），" +
                "解析器若再抄一份词表就是第二事实源",
        )
    }

    @Test
    fun consumingACommandObligesAVisibleReceipt() {
        // 回执必须是**群广播**（真机 E2E 抓到的形状：本地 notice 只有宿主看得见，
        // 手机上的发送者面对静默）—— sendToConversation 全员可见，含发送者。
        assertTrue(
            extension.contains("controller.sendToConversation(convId, receipt, null)"),
            "快捷指令回执必须广播成群消息：本地 notice = 发送方静默（真机实锤）",
        )
        assertTrue(
            extension.contains("team config saved for this group"),
            "模板配置确认也要广播（同一纪律）：配置者多半在手机上",
        )
        assertTrue(
            extension.contains("NO_GIT_RECEIPT") && extension.contains("NO_WORKER_RECEIPT"),
            "环境不可用要有如实回执文案：null 不许被吞成静默",
        )
    }

    /** §9.3 芯片接缝：共享组件有参数、手机壳传清单 —— 摘掉任何一环，真机上芯片就静默消失。 */
    @Test
    fun theChipSeamIsWiredFromTheSharedComponentToThePhoneShell() {
        val composer = GuardSourceScan.stripComments(
            GuardSourceScan.read("composeApp/src/commonMain/kotlin/com/kapp/tenon/ui/components/MessageComposer.kt"),
        )
        val chatScreen = GuardSourceScan.stripComments(
            GuardSourceScan.read("composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/chat/ChatScreen.kt"),
        )
        assertTrue(
            composer.contains("quickActions: List<ComposerCapability> = emptyList()"),
            "MessageComposer 必须有 quickActions 参数（§9.3 接缝）：芯片是调用方给清单，不是组件内置",
        )
        assertTrue(
            chatScreen.contains("ComposerCapability(\"qa-status\"") &&
                chatScreen.contains("ConvId.isGroup(peerId)"),
            "手机壳必须在群会话构造四颗芯片（/status /diff /test /stop）：少一颗 = 那条命令只剩手打",
        )
    }
}
