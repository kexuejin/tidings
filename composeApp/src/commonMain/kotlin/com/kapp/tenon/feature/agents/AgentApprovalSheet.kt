// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.kapp.tenon.domain.model.AgentApprovalDecision
import com.kapp.tenon.domain.model.AgentApprovalUi
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.approval_sheet_title
import com.kapp.tenon.ui.theme.LocalAppSpacing
import org.jetbrains.compose.resources.stringResource

/**
 * 手机主页的**原位审批面板**（UX 规范 §2.2②）。
 *
 * ## 它解决的是「迷宫横跳」，不是「没地方看审批」
 *
 * 主页本来就有逐条审批卡；问题是置顶那张「N 项待审批」的按钮把人**甩进聊天页**。
 * 用户为了批一条 `rmdir /tmp/cache`，要先离开主页、进群、再在消息流里找那张卡。
 * 面板把同一批卡提到手边：点一下就地处置，处置完自动更新。
 *
 * ## 为什么是薄壳
 *
 * 逐条处置（批准 / 拒绝 / 本会话免审 / L3 确认 / diff 预览 / 2 秒留痕）**只有一份**
 * 实现，就是 [AgentApprovalsSection]。这个文件只负责把它装进 `ModalBottomSheet`、
 * 给一个标题与滚动容器 —— 面板里**没有**第二套按钮，也没有第二套权限判据：
 * [isReadOnly] 与 [attribution] 由调用方按全仓唯一口径传进来。
 *
 * 桌面壳不用它：宽屏右栏 inspector 已经常驻同一批卡，再叠一层面板是重复渲染
 * （§2.2① 的同一条理由）。参数由看板的 `inPlaceApprovals` 控制，默认关。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentApprovalSheet(
    approvals: List<AgentApprovalUi>,
    onRespond: ((approvalId: String, allow: Boolean) -> Unit)?,
    onRespondChoice: ((approvalId: String, decision: AgentApprovalDecision) -> Unit)?,
    isReadOnly: (AgentApprovalUi) -> Boolean,
    attribution: (AgentApprovalUi) -> String?,
    onDismiss: () -> Unit,
) {
    val sp = LocalAppSpacing.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = sp.lg, vertical = sp.sm6),
            verticalArrangement = Arrangement.spacedBy(sp.sm6),
        ) {
            Text(
                text = stringResource(Res.string.approval_sheet_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            AgentApprovalsSection(
                approvals = approvals,
                onRespond = onRespond,
                onRespondChoice = onRespondChoice,
                isReadOnly = isReadOnly,
                attribution = attribution,
            )
            // 底部留白：最后一条卡贴着手势条会点不到。
            Spacer(Modifier.height(sp.xxl))
        }
    }
}
