// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui

import com.kapp.tidings.domain.RelayUiTexts
import com.kapp.tidings.domain.model.ScreenRecordUi
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.chat_nudge_peer
import com.kapp.tidings.generated.resources.workspace_group_initial_greeting
import com.kapp.tidings.generated.resources.workspace_group_session_brief
import com.kapp.tidings.generated.resources.workspace_group_session_busy
import com.kapp.tidings.generated.resources.workspace_group_session_ready
import com.kapp.tidings.ui.components.screenCardText
import org.jetbrains.compose.resources.getString

/**
 * [RelayUiTexts] 的**生产实现**：把领域侧要的语义落成 strings.xml 里的措辞。
 *
 * ## 为什么这个实现住在 `ui/` 而不是放在 App.kt 里当 lambda
 *
 * 它有四个方法、每个都对应一条真实的资源键。塞进 App.kt 会让那个文件多出一段与
 * 布局无关的资源映射；而这段映射**是产品文案的一部分**，与其它 UI 文案同属一层。
 *
 * ## 降级一律用 `runCatching`
 *
 * 与改动前的行为逐字一致：取不到资源就返回空串 / 兜底串，**不抛**。
 * 一条投屏记录、一次戳一碰通知，都比一句文案重要。兜底串里刻意**不含中文**
 * —— 它只在资源真的坏掉时出现，而那时编一句中文会绕开 CJK 门禁与多语言对账。
 */
internal object ResourceUiTexts : RelayUiTexts {

    override suspend fun screenCardText(record: ScreenRecordUi): String =
        runCatching { screenCardText(record) }.getOrNull().orEmpty()

    override suspend fun nudgeNotice(): String =
        runCatching { getString(Res.string.chat_nudge_peer) }.getOrNull().orEmpty()

    override suspend fun workspaceGreeting(agentKind: String, workspaceTitle: String): String =
        runCatching {
            getString(Res.string.workspace_group_initial_greeting, agentKind, workspaceTitle)
        }.getOrNull() ?: ("[" + agentKind + "] " + workspaceTitle)

    override suspend fun workspaceSessionBrief(sessionTitle: String, busy: Boolean): String {
        val stateText = runCatching {
            getString(
                if (busy) Res.string.workspace_group_session_busy
                else Res.string.workspace_group_session_ready,
            )
        }.getOrNull().orEmpty()
        return runCatching {
            getString(Res.string.workspace_group_session_brief, sessionTitle, stateText)
        }.getOrNull() ?: sessionTitle
    }
}
