// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 捎话 Tidings contributors

package com.kapp.tenon.feature.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kapp.tenon.core.crypto.ArtifactVerification
import com.kapp.tenon.core.nowMillis
import com.kapp.tenon.core.protocol.AgentRiskLevels
import com.kapp.tenon.feature.chat.CardCmdBox
import com.kapp.tenon.feature.chat.CardContextBox
import com.kapp.tenon.domain.isApprovalExpired as domainIsApprovalExpired
import kotlinx.coroutines.delay
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.approval_focus_title
import com.kapp.tenon.generated.resources.approval_focus_action
import com.kapp.tenon.generated.resources.agent_approval_allow
import com.kapp.tenon.generated.resources.agent_approval_deny
import com.kapp.tenon.generated.resources.agent_artifact_verify_missing
import com.kapp.tenon.generated.resources.agent_artifact_verify_mismatch
import com.kapp.tenon.generated.resources.agent_artifact_verify_ok
import com.kapp.tenon.generated.resources.agent_artifact_verify_undeclared
import com.kapp.tenon.generated.resources.agent_artifact_verify_unavailable
import com.kapp.tenon.generated.resources.agent_approval_expired
import com.kapp.tenon.generated.resources.agent_approval_readonly
import com.kapp.tenon.generated.resources.agent_approval_status_approved
import com.kapp.tenon.generated.resources.agent_approval_status_denied
import com.kapp.tenon.generated.resources.agent_approval_status_revoked
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontFamily
import com.kapp.tenon.ui.components.AppButton
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import com.kapp.tenon.ui.components.AppButtonTone
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import com.kapp.tenon.ui.components.AppButtonSize
import com.kapp.tenon.ui.components.AppOutlinedButton
import com.kapp.tenon.ui.components.AppTextButton
import com.kapp.tenon.ui.components.RelayDialog
import com.kapp.tenon.ui.components.RelayDialogButton
import com.kapp.tenon.generated.resources.agent_approval_allow_always
import com.kapp.tenon.generated.resources.agent_approval_allow_session
import com.kapp.tenon.generated.resources.agent_approval_allow_session_hint
import com.kapp.tenon.generated.resources.agent_approval_confirm_always_body
import com.kapp.tenon.generated.resources.agent_approval_confirm_l3_body
import com.kapp.tenon.generated.resources.agent_approval_confirm_l3_title
import com.kapp.tenon.generated.resources.agent_approval_scope_hint
import com.kapp.tenon.generated.resources.agent_approval_diff_title
import com.kapp.tenon.generated.resources.agent_approval_expires_label
import com.kapp.tenon.generated.resources.agent_approval_risk_l1
import com.kapp.tenon.generated.resources.agent_approval_risk_l2
import com.kapp.tenon.generated.resources.agent_approval_risk_l3
import com.kapp.tenon.generated.resources.agent_approval_risk_unrated
import com.kapp.tenon.generated.resources.agent_approval_status_allowed_session
import com.kapp.tenon.generated.resources.agent_approval_title
import com.kapp.tenon.generated.resources.agent_approval_tool_label
import com.kapp.tenon.generated.resources.agent_approval_view_diff
import com.kapp.tenon.generated.resources.agent_artifact_no_file
import com.kapp.tenon.generated.resources.agent_artifacts_title
import com.kapp.tenon.generated.resources.agent_count_items
import com.kapp.tenon.generated.resources.agent_queue_cancel
import com.kapp.tenon.generated.resources.agent_queue_title
import com.kapp.tenon.generated.resources.agent_run_state_cancelled
import com.kapp.tenon.generated.resources.agent_run_state_failed
import com.kapp.tenon.generated.resources.agent_run_state_running
import com.kapp.tenon.generated.resources.agent_run_state_succeeded
import com.kapp.tenon.generated.resources.agent_runs_title
import com.kapp.tenon.generated.resources.common_cancel
import com.kapp.tenon.generated.resources.common_close
import com.kapp.tenon.ui.components.SectionHeader
import com.kapp.tenon.ui.components.formatClock
import com.kapp.tenon.ui.components.formatCount
import com.kapp.tenon.ui.theme.IconSizes
import com.kapp.tenon.ui.theme.LocalAppSpacing
import com.kapp.tenon.ui.theme.LocalCardShape
import com.kapp.tenon.ui.theme.relayCardShadow
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import com.kapp.tenon.domain.model.AgentApprovalUi
import com.kapp.tenon.domain.model.AgentArtifactUi
import com.kapp.tenon.domain.model.AgentQueueTaskUi
import com.kapp.tenon.domain.model.AgentRunUi
import com.kapp.tenon.domain.model.AgentApprovalDecision

/**
 * 一条审批的风险等级。
 *
 * 优先信**对端声明的** `riskLevel`；对端没声明时只做「往高处猜」的工具名启发：
 * `bash`/`exec` 这类判 L3、`write`/`delete` 这类判 L2 —— 往高猜是保守侧，错了只会
 * 多一次确认，不会替对端把危险说成安全。**其余一律 [RISK_UNRATED]**：此前这里猜 L1
 * （「只读」），等于客户端替对端宣布"这条是安全的" —— 那不是判断，是编造。
 */
internal fun resolveRiskLevel(risk: String?, tool: String?): String {
    if (!risk.isNullOrBlank()) return risk.uppercase()
    val t = tool?.lowercase().orEmpty()
    return when {
        t.contains("bash") || t.contains("exec") || t.contains("command") || t.contains("shell") || t.contains("terminal") -> AgentRiskLevels.HIGH
        t.contains("write") || t.contains("edit") || t.contains("replace") || t.contains("delete") || t.contains("remove") -> AgentRiskLevels.MODIFYING
        else -> RISK_UNRATED
    }
}

/** 对端未声明风险等级、工具名也看不出高低时的档位：显示「未分级」，不猜。 */
internal const val RISK_UNRATED = "UNRATED"

/**
 * 批准这条审批前要不要先弹确认卡。
 *
 * 只有 **L3（高危）** 需要：按下去就直接放行了，这一下必须有一次反悔的机会
 * （issue：一键批准，无确认无撤销）。L1/L2 保持一键 —— 确认卡本身也是打扰，
 * 只该花在真正危险的请求上。
 */
internal fun shouldConfirmApproval(riskLevel: String): Boolean = riskLevel == AgentRiskLevels.HIGH

/**
 * 这条审批是否已过有效期。
 *
 * 实现在领域层（`com.kapp.tenon.domain.isApprovalExpired`）—— 那是**唯一判据**：
 * 看板的「待审批」计数、审批段、行内徽标与领域侧派生必须同值，两处各写一遍
 * `expiresAt > 0 && now > expiresAt` 就是本仓反复抓过的「两把尺子」。
 * 这里保留同名薄封装，只是为了让本文件的调用点不必带包名。
 */
internal fun isApprovalExpired(approval: AgentApprovalUi, now: Long): Boolean =
    domainIsApprovalExpired(approval, now)

/** 处置完成后卡片保留的时长：足够看清「已批准/已拒绝」徽标，又不至于赖着不走。 */
internal const val DECISION_LINGER_MS = 2_000L

/**
 * 审批段这一轮要画的行：pending（未过期）+ 处置后仍在留痕窗口内的 + 历史已决项。
 *
 * 抽成纯函数是为了可测：「过期不计入待批」「已决 2 秒退场」都是这里说了算，
 * 放在组合体内只有截图能看见。[@see][isApprovalExpired] 是唯一的有效期判据。
 *
 * 看板 / 工作区详情 / 会话详情共用这一份过滤，不再各写一遍：[peerId] / [sessionIds]
 * 收窄来源，[lingerIds] 是看板的「本屏见过 pending」留痕白名单。两套留痕记账方向不同 ——
 * [lingerDismissed] 是退场黑名单（`!in` 才画），[lingerIds] 是留痕白名单（`in` 才画）——
 * 由调用方择一传入。函数只读集合、不写集合；组合期的记账写入不改变本轮结果
 * （本轮读到的"已决"一定来自上一轮的记账）。
 */
internal fun visibleApprovalRows(
    approvals: Collection<AgentApprovalUi>,
    now: Long,
    lingerDismissed: Set<String>,
    /**
     * `true` = 也画**已过期的 pending**（以「已过期」徽标常驻，不带任何按钮）。
     *
     * 为什么需要这一档：看板是清单页，过期项直接不画是对的（它不该占"待办"的位置）；
     * 但会话详情是**这一轮的历史**，一条审批到期后凭空消失，用户分不清"它被处理了"
     * 与"它超时了"。所以在会话详情的「审批记录」段里把它们画出来。
     */
    includeExpiredPending: Boolean = false,
    /** 收窄到某台设备；null = 不限。 */
    peerId: String? = null,
    /** 收窄到会话集；null = 不限。 */
    sessionIds: Set<String>? = null,
    /**
     * 看板口径的「本屏见过 pending」白名单：非 null 时已决行**只在名单里**才保留；
     * null（默认）时沿用 [lingerDismissed] 的退场黑名单口径。
     */
    lingerIds: Set<String>? = null,
): List<AgentApprovalUi> = approvals.filter { approval ->
    val scoped = (peerId == null || approval.peerId == peerId) &&
        (sessionIds == null || approval.sessionId in sessionIds)
    scoped && when {
        approval.status == "pending" -> includeExpiredPending || !isApprovalExpired(approval, now)
        else -> {
            val ids = lingerIds
            if (ids != null) approval.approvalId in ids else approval.approvalId !in lingerDismissed
        }
    }
}

/**
 * 宿主在这一条审批里声明了哪些处置（`choices` 是 CSV，如 `allow,deny,always`）。
 *
 * 为什么按**词**比而不是 `contains("always")`：`notalways` 这类值会误命中，
 * 而这一段判的是一件不可逆的事（点下去就再也不问了）。大小写与空白一并归一 ——
 * 这个字段是别的进程填的，不该由我们来赌它一定规整。
 */
internal fun choicesDeclare(choices: String?, value: String): Boolean {
    if (choices.isNullOrBlank()) return false
    return choices.split(',').any { it.trim().equals(value, ignoreCase = true) }
}

/**
 * 审批行**第二行**该画哪几档免审 —— 空列表 = 不画第二行。
 *
 * 三档处置的层次：首行两颗是「这次做不做」（拒绝 / 批准），第二行两档是「以后还问不问」。
 * 分行的理由不是审美：首行再塞一颗，393dp 上 `工具: {tool}` 就只剩二十几个 dp，
 * 而那是用户唯一能确认「我批的是哪条命令」的字段。
 *
 * ## 「始终允许」为什么必须由 `choices` 说了算
 *
 * 它不是本端的权力，是**宿主的能力**：只有宿主会把这条决定落成免批规则。对端没声明
 * `always` 时把按钮画出来，用户点下去得到的是：本端显示「已批准」，宿主当成一个不认识的
 * 取值、当前这条都没批 —— 正好是本仓最贵的那类缺陷（发送侧一切正常、接收侧什么都没有）。
 * 所以宁可少一颗按钮，也不画一颗可能没人接的按钮。
 *
 * 「本会话免审」**不**受 `choices` 约束：它有独立的回执取值（`allow_session`），
 * 而且比 `choices` 这个字段先落地 —— 拿一个更晚出现的声明去关掉一个在用的入口，
 * 只会让老宿主上的用户凭空少一颗按钮。
 */
internal fun secondaryApprovalChoicesOf(
    approval: AgentApprovalUi,
    isExpired: Boolean,
    isReadOnly: Boolean,
    canChoose: Boolean,
): List<AgentApprovalDecision> {
    if (!canChoose || isReadOnly || isExpired || approval.status != "pending") return emptyList()
    val choices = buildList {
        add(AgentApprovalDecision.ALLOW_SESSION)
        if (choicesDeclare(approval.choices, "always")) add(AgentApprovalDecision.ALLOW_ALWAYS)
    }
    return choices
}

@Composable
internal fun RiskLevelBadge(level: String, modifier: Modifier = Modifier) {
    val (labelRes, containerColor, contentColor) = when (level) {
        AgentRiskLevels.HIGH -> Triple(
            Res.string.agent_approval_risk_l3,
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
        AgentRiskLevels.MODIFYING -> Triple(
            Res.string.agent_approval_risk_l2,
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
        RISK_UNRATED -> Triple(
            Res.string.agent_approval_risk_unrated,
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> Triple(
            Res.string.agent_approval_risk_l1,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = containerColor,
        modifier = modifier,
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = LocalAppSpacing.current.sm6, vertical = LocalAppSpacing.current.xxs),
        )
    }
}

/**
 * 待处理 Agent 审批请求列表（PPE-1 §6.5）。
 *
 * **版面照原型**：整段**一张卡**、一行一条、行间发丝线，按钮并到首行右端
 * （`prototype/relay.html:431-449` 的 `.lst` / `.appr` / `.appr .rt`，以及
 * `apprSectionHTML`（`:2206`）：「整段一张卡、行间发丝线」）。
 *
 * 原型在那一段里**逐字写明这是它刻意偏离源码的一处**，理由是可读性：
 * 「源码每条是独立一张 Card（自己的阴影 + 14dp 内边距 + 按钮另起一行），单条 112px、
 * 三条 336px —— 手机上「待处理审批」这一块自己就吃掉整屏，而那三条里真正要读的字只有
 * 两行。改成一张 `.lst` 卡 + 行间发丝线，按钮并到首行右端：单条 61px，且**按钮跨条目
 * 竖直对齐**，连点几下批准不会点飞。」（`prototype/relay.html:420-430`；偏离记在原型
 * README §12）
 * 样式按原型收敛为单卡列表行结构以提升空间利用率。
 *
 * ## 输入是「这一范围的全部审批」，不再是「过滤好的 pending」
 *
 * 已决（approved / denied / allow_session）的卡片**不再当场消失**：处置后先保留
 * [DECISION_LINGER_MS] 并显示徽标，再退场。否则「我批掉的那条」与「它被谁删了」
 * 在屏幕上无法区分 —— 用户刚点完批准，卡没了，第一反应是「是不是没点上」。
 * 磁盘恢复回来的历史已决项**不进这条窗口**（从未见过它是 pending 就不该替它计时），
 * 在会话详情里它们照旧以徽标常驻。
 *
 * 过期（[isApprovalExpired]）的 pending 不画也不计数 —— 它已经不可能被处置了。
 */
@Composable
public fun AgentApprovalsSection(
    approvals: List<AgentApprovalUi>,
    onRespond: ((approvalId: String, allow: Boolean) -> Unit)? = null,
    onRespondChoice: ((approvalId: String, decision: AgentApprovalDecision) -> Unit)? = null,
    isReadOnly: ((AgentApprovalUi) -> Boolean)? = null,
    modifier: Modifier = Modifier,
    /**
     * 段标题；null = 「待处理审批」。
     *
     * 会话详情把这段拆成两段：待批仍在「待处理审批」，历史（已批准/已拒绝/已撤销 +
     * 已过期的 pending）进「审批记录」。修复前两类混在同一标题下，计数也是两类之和 ——
     * 用户看到「待处理审批 7 项」而只有 1 条能点。
     */
    title: StringResource? = null,
    /** 见 [visibleApprovalRows] 的同名参数（会话详情的「审批记录」段会传 true）。 */
    includeExpiredPending: Boolean = false,
    /**
     * 这一条**属于谁**（"设备 / 工作区"一句话）；null = 不画。
     *
     * 看板的审批段是跨设备、跨工作区的一整张平铺卡：不写归属时，两台设备同时报审批，
     * 用户只能靠 summary 猜是谁在要权限（原型是逐工作区一段，归属由分段表达）。
     */
    attribution: ((AgentApprovalUi) -> String?)? = null,
) {
    val spacing = LocalAppSpacing.current
    val now = nowMillis()

    // 处置留痕：见过 pending 才有资格进「已决 2 秒退场」的窗口。
    // lingerDismissed 必须是 SnapshotStateSet —— 在 LaunchedEffect 里改它要能触发重组；
    // seenPending 只在组合期写读，普通 remember 即可。两笔都是纯 UI 记账，不进领域状态、不落盘。
    val seenPending = remember { mutableSetOf<String>() }
    val lingerDismissed = remember { mutableStateSetOf<String>() }
    approvals.forEach { approval ->
        if (approval.status == "pending") {
            seenPending.add(approval.approvalId)
        } else if (approval.approvalId in seenPending && approval.approvalId !in lingerDismissed) {
            val id = approval.approvalId
            LaunchedEffect(id) {
                delay(DECISION_LINGER_MS)
                lingerDismissed.add(id)
            }
        }
    }

    val visible = visibleApprovalRows(approvals, now, lingerDismissed, includeExpiredPending)
    if (visible.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        SectionHeader(
            text = title?.let { stringResource(it) } ?: stringResource(Res.string.agent_approval_title),
            action = {
                Text(
                    text = stringResource(Res.string.agent_count_items, formatCount(visible.size.toLong())),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )

        SectionListCard {
            visible.forEachIndexed { index, approval ->
                if (index > 0) SectionListDivider()
                AgentApprovalRow(
                    approval = approval,
                    now = now,
                    onRespond = onRespond,
                    onRespondChoice = onRespondChoice,
                    isReadOnly = isReadOnly?.invoke(approval) == true,
                    attribution = attribution?.invoke(approval),
                )
            }
        }
    }
}

/** 审批**一条**（行）：风险等级 + `工具: {tool}` + 摘要 + 有效期 + Diff查看 + 首行右端的按钮 / 状态胶囊。 */
@Composable
internal fun AgentApprovalRow(
    approval: AgentApprovalUi,
    now: Long,
    onRespond: ((approvalId: String, allow: Boolean) -> Unit)?,
    onRespondChoice: ((approvalId: String, decision: AgentApprovalDecision) -> Unit)? = null,
    isReadOnly: Boolean = false,
    /** 归属（"设备 · 工作区"）：只有看板这种跨来源清单会传，见 AgentApprovalsSection 的同名参数。 */
    attribution: String? = null,
) {
    val spacing = LocalAppSpacing.current
    val isExpired = isApprovalExpired(approval, now)
    var showDiffDialog by remember { mutableStateOf(false) }
    // L3 的高危批准先过一道确认卡；确认目标记下（批准 / 本次免审 / 始终允许），卡里只做放行这一件事。
    var confirmDecision by remember { mutableStateOf<AgentApprovalDecision?>(null) }

    val statusStyle = approvalStatusStyleOf(approval.status, isExpired)

    val riskLevel = resolveRiskLevel(approval.riskLevel, approval.tool)

    // 第二行能画哪几档免审：由 choices（宿主的声明）与状态一起决定，见 [secondaryApprovalChoicesOf]。
    val secondary = secondaryApprovalChoicesOf(
        approval = approval,
        isExpired = isExpired,
        isReadOnly = isReadOnly,
        canChoose = onRespondChoice != null,
    )

    /** 把用户点的那一档原样交上去（本层不做任何本地推断）。 */
    val dispatch: (AgentApprovalDecision) -> Unit = { decision ->
        if (onRespondChoice != null) {
            onRespondChoice(approval.approvalId, decision)
        } else {
            // 只接了旧的两参回调：那种壳上压根画不出免审档（见 [secondaryApprovalChoicesOf]），
            // 走到这里只可能是批准/拒绝。
            onRespond?.invoke(approval.approvalId, decision != AgentApprovalDecision.DENY)
        }
    }

    // 放行类处置（批准 / 本次免审 / 始终允许）都从这里走：高危先确认，低危直接放行。
    // 拒绝不确认 —— 它不会让任何东西被执行，在这一步加闸只会拖慢「该停手的时候」。
    val requestDecision: (AgentApprovalDecision) -> Unit = { decision ->
        if (decision != AgentApprovalDecision.DENY && shouldConfirmApproval(riskLevel)) {
            confirmDecision = decision
        } else {
            dispatch(decision)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.lg14, vertical = spacing.md10),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.md10),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    RiskLevelBadge(riskLevel)
                    Text(
                        text = "${stringResource(Res.string.agent_approval_tool_label)}: ${approval.tool ?: "—"}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                approval.summary?.let { note ->
                    val isCmd = (approval.tool?.lowercase() in listOf("bash", "shell", "exec", "terminal", "command", "sh")) ||
                        note.startsWith("$ ") || note.startsWith("./")
                    if (isCmd) {
                        CardCmdBox(
                            cmd = note,
                            modifier = Modifier.padding(top = spacing.xxs),
                        )
                    } else {
                        CardContextBox(
                            context = note,
                            modifier = Modifier.padding(top = spacing.xxs),
                        )
                    }
                }
                // 归属：跨设备/跨工作区的清单必须有这一行，否则"谁在要权限"只能靠猜。
                attribution?.takeIf { it.isNotBlank() }?.let { owner ->
                    Text(
                        text = owner,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = spacing.xxs),
                    )
                }
                // 有效期只对还没处置的请求有意义（已决的不再存在"来不来得及"）。
                // 时刻用 formatClock：审批的生命周期是分钟级，日期没有信息量。
                if (approval.status == "pending" && !isExpired && approval.expiresAt > 0L) {
                    Text(
                        text = stringResource(Res.string.agent_approval_expires_label) + " " + formatClock(approval.expiresAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = spacing.xxs),
                    )
                }
            }

            // 首行右端：操作按钮或状态胶囊
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.sm6),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (approval.status == "pending" && !isExpired && isReadOnly) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            text = stringResource(Res.string.agent_approval_readonly),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xxs),
                        )
                    }
                } else if (approval.status == "pending" && !isExpired && (onRespond != null || onRespondChoice != null)) {
                    // 首行只放**决定做不做**那两颗：原型的 `.appr` 就是 `拒绝`(描边) + `批准`(实心)
                    // 两颗，别的都另起一行。393dp 上塞第四颗会把 `工具: {tool}` 压到读不出名字。
                    // 小档的视觉高度是原型值（6×10 / 12sp），但**触达**不许低于 48dp：
                    // M3 的 Button 默认最小高 40dp，在审批行这种"每颗都不可撤销"的位置
                    // 差 8dp 就是误触一次写操作。视觉仍交给 AppButtonSize.Small，只兜底高度。
                    AppOutlinedButton(
                        text = stringResource(Res.string.agent_approval_deny),
                        onClick = { dispatch(AgentApprovalDecision.DENY) },
                        size = AppButtonSize.Small,
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                    AppButton(
                        text = stringResource(Res.string.agent_approval_allow),
                        onClick = { requestDecision(AgentApprovalDecision.ALLOW) },
                        size = AppButtonSize.Small,
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                } else if (statusStyle != null) {
                    val capsule = statusStyle
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = capsule.badge,
                    ) {
                        Text(
                            text = capsule.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = capsule.text,
                            modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xxs),
                        )
                    }
                }
            }
        }

        // 第二行：两档免审。它们都属放行（所以高危同样先过确认卡，免审不能成为绕过确认的旁路），
        // 但语义都比「批准」低一档，而且**范围必须说清** —— 一个词说不清它覆盖到哪儿：
        // 「本会话免审」只管当前会话，「此工具始终允许」跨会话、只管这一个工具名。
        // 首行塞不下第四颗：393dp 上会把 `工具: {tool}` 压到读不出名字（见上面那段注释）。
        if (secondary.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(top = spacing.xxs),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm6),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                secondary.forEach { decision ->
                    AppTextButton(
                        text = stringResource(
                            when (decision) {
                                AgentApprovalDecision.ALLOW_ALWAYS -> Res.string.agent_approval_allow_always
                                else -> Res.string.agent_approval_allow_session
                            },
                        ),
                        onClick = { requestDecision(decision) },
                        size = AppButtonSize.Small,
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
            Text(
                text = stringResource(
                    if (AgentApprovalDecision.ALLOW_ALWAYS in secondary) {
                        Res.string.agent_approval_scope_hint
                    } else {
                        Res.string.agent_approval_allow_session_hint
                    },
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.xxs),
            )
        }

        // 如果包含 diff / 详细参数变更，提供点击查看入口。
        // 触达：文字本身只有 ~16dp 高，加一层 48dp 的最小交互尺寸 —— 这颗按钮打开的是
        // 「这条命令到底要干什么」，点不到它等于让人盲批。
        if (!approval.diff.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .padding(top = spacing.xs)
                    .heightIn(min = 48.dp)
                    .clickable { showDiffDialog = true },
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = stringResource(Res.string.agent_approval_view_diff),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }

    // 局部 val：`confirmDecision` 是委托属性，直接读会被拆成 getter，智能转换用不上。
    val confirming = confirmDecision
    if (confirming != null) {
        // L3 的最后一道闸：标题说明「高危」，正文说明「批准即放行」，
        // 卡里复读工具名 —— 用户确认的必须是这一条，不是任何一个名字相同的请求。
        // 「始终允许」的正文必须换：它放行的不只是这一次，范围写错就等于替人批了一整类操作。
        val confirmAlways = confirming == AgentApprovalDecision.ALLOW_ALWAYS
        RelayDialog(
            onDismissRequest = { confirmDecision = null },
            title = { Text(stringResource(Res.string.agent_approval_confirm_l3_title)) },
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Text(
                        text = stringResource(
                            if (confirmAlways) {
                                Res.string.agent_approval_confirm_always_body
                            } else {
                                Res.string.agent_approval_confirm_l3_body
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "${stringResource(Res.string.agent_approval_tool_label)}: ${approval.tool ?: "—"}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    approval.summary?.let { note ->
                        Text(
                            text = note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                RelayDialogButton(
                    text = stringResource(
                        if (confirmAlways) Res.string.agent_approval_allow_always
                        else Res.string.agent_approval_allow,
                    ),
                    primary = true,
                    onClick = {
                        confirmDecision = null
                        if (onRespondChoice != null) {
                            onRespondChoice(approval.approvalId, confirming)
                        } else {
                            onRespond?.invoke(approval.approvalId, true)
                        }
                    },
                )
            },
            dismissButton = {
                RelayDialogButton(
                    text = stringResource(Res.string.common_cancel),
                    onClick = { confirmDecision = null },
                )
            },
        )
    }

    if (showDiffDialog) {
        RelayDialog(
            onDismissRequest = { showDiffDialog = false },
            title = { Text(stringResource(Res.string.agent_approval_diff_title)) },
            content = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = approval.diff.orEmpty(),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.padding(vertical = spacing.sm),
                    )
                }
            },
            confirmButton = {
                RelayDialogButton(
                    onClick = { showDiffDialog = false },
                    text = stringResource(Res.string.common_close),
                )
            },
        )
    }
}

/**
 * Agent 任务队列列表（PPE-1 §6.3）。
 *
 * 与审批同一张形状 —— 原型把两者写在同一个注释里：「审批与队列：整段**一张卡**，一行一条」
 * （`prototype/relay.html:420-423`）：一张 `.lst` 卡 + 行间发丝线。
 *
 * **一处保留差异**：原型的队列行只画 `{op}: {taskId 前 12 位}` + 行尾时刻 + `prompt`
 * （`.qk`，`:453-458`），没有取消按钮；源码的取消能力是真的（`AgentQueueSection` 的
 * `onCancelTask`），所以这里把它留在**行尾**而不是删掉 —— 删功能去对齐一张没画它的原型
 * 是本末倒置。
 */
@Composable
public fun AgentQueueSection(
    tasks: List<AgentQueueTaskUi>,
    onCancelTask: ((taskId: String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (tasks.isEmpty()) return

    val spacing = LocalAppSpacing.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        SectionHeader(
            text = stringResource(Res.string.agent_queue_title),
            // 同审批节：原型 `m11-agents` 在「任务队列」右上角有「3 项」。
            action = {
                Text(
                    text = stringResource(Res.string.agent_count_items, formatCount(tasks.size.toLong())),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )

        SectionListCard {
            tasks.forEachIndexed { index, task ->
                if (index > 0) SectionListDivider()
                QueueRow(task = task, onCancelTask = onCancelTask)
            }
        }
    }
}

/** 队列**一条**（行，不是卡）：`{op}: {taskId 前 12 位}` + 行尾时刻 / `prompt` / 取消。 */
@Composable
private fun QueueRow(task: AgentQueueTaskUi, onCancelTask: ((taskId: String) -> Unit)?) {
    val spacing = LocalAppSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.lg14, vertical = spacing.md10),
        horizontalArrangement = Arrangement.spacedBy(spacing.md10),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = "${task.op}: ${task.taskId.take(12)}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                if (task.queuedAt > 0L) {
                    Text(
                        text = formatClock(task.queuedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            task.prompt?.let { prompt ->
                Text(
                    text = prompt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = spacing.xs),
                )
            }
        }
        if (onCancelTask != null) {
            // 队列卡的行末动作：原型队列行没有按钮，这里按「列表行内动作」走小档，
            // 与同屏审批行的 `.btn sm` 保持同一级。
            AppOutlinedButton(
                text = stringResource(Res.string.agent_queue_cancel),
                onClick = { onCancelTask(task.taskId) },
                size = AppButtonSize.Small,
            )
        }
    }
}

/**
 * 原型的 `.lst`（`prototype/relay.html:431-432`）：**整段一张卡、行间发丝线**。
 *
 * `.card{background:var(--surface);border-radius:var(--r-card);box-shadow:var(--shadow)}`
 * （`:119`）+ `.lst > * + *{border-top:1px solid var(--hair)}`（`:432`）。
 */
@Composable
private fun SectionListCard(content: @Composable () -> Unit) {
    val shape = LocalCardShape.current
    Card(
        shape = shape,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .relayCardShadow(shape = shape),
    ) {
        Column { content() }
    }
}

/** 行间发丝线：原型 `.lst > * + *{border-top:1px solid var(--hair)}`（`prototype/relay.html:432`）。 */
@Composable
private fun SectionListDivider() {
    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
}


/**
 * Agent 执行状态列表（PPE-1 §6.4 的 `agent-run`）。
 *
 * 每一行是「某个 runId 现在到哪一步了」。`state` 只认识四档时给本地化文案，
 * **不认识就原样透传** —— 协议允许对端多报一档，界面不该因此显示空白。
 */
@Composable
public fun AgentRunsSection(
    runs: List<AgentRunUi>,
    modifier: Modifier = Modifier,
) {
    if (runs.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm)) {
        SectionHeader(
            text = stringResource(Res.string.agent_runs_title),
            action = {
                Text(
                    text = stringResource(Res.string.agent_count_items, formatCount(runs.size.toLong())),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )

        runs.forEach { run ->
            Card(
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier
                    .fillMaxWidth()
                    .relayCardShadow(shape = RoundedCornerShape(12.dp)),
            ) {
                Column(modifier = Modifier.padding(LocalAppSpacing.current.lg14)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        val runState = runStateStyleOf(run.state)
                        Text(
                            text = run.runId.take(12),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = runState.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = runState.color,
                        )
                    }
                    run.note?.takeIf { it.isNotBlank() }?.let { note ->
                        Spacer(Modifier.height(LocalAppSpacing.current.xs))
                        Text(
                            text = note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Agent 产物元数据列表（PPE-1 §6.7 的 `agent-artifact`）。
 *
 * **只列元数据**。`fileId` 为空时明说"只有元数据，没有关联文件" —— 因为这一帧按协议
 * 就是不传二进制的，界面上不能让人以为点一下能打开。
 */
@Composable
public fun AgentArtifactsSection(
    artifacts: List<AgentArtifactUi>,
    modifier: Modifier = Modifier,
    /**
     * M7-T3 2b（D-015 ②）：按需实测桥（平台读下载目录）。null = 本设备无法实测
     * （桥没装配）—— 有 declared 的产物如实展示「无法实测」，不装成已校验。
     */
    artifactDigestOf: (suspend (fileName: String) -> String?)? = null,
) {
    if (artifacts.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm)) {
        SectionHeader(
            text = stringResource(Res.string.agent_artifacts_title),
            action = {
                Text(
                    text = stringResource(Res.string.agent_count_items, formatCount(artifacts.size.toLong())),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )

        artifacts.forEach { artifact ->
            Card(
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier
                    .fillMaxWidth()
                    .relayCardShadow(shape = RoundedCornerShape(12.dp)),
            ) {
                Column(modifier = Modifier.padding(LocalAppSpacing.current.lg14)) {
                    Text(
                        text = artifact.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(LocalAppSpacing.current.xs))
                    Text(
                        text = artifactSubtitle(artifact),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // M7-T3 2b：校验状态（端到端那道门 —— agent declared vs 落盘实测）。
                    // 纯展示，无自动重拉（D-015 裁决）；判定在 core 的 ArtifactVerification。
                    // 桥缺失（平台装不了实测）⇒ 如实说「无法实测」，不装成已校验。
                    var digest by remember(artifact.artifactId) { mutableStateOf<String?>(null) }
                    var probed by remember(artifact.artifactId) { mutableStateOf(false) }
                    LaunchedEffect(artifact.artifactId, artifact.name) {
                        digest = artifactDigestOf?.invoke(artifact.name)
                        probed = true
                    }
                    val verifyLabel = when {
                        artifactDigestOf == null -> Res.string.agent_artifact_verify_unavailable
                        !probed -> null // 桥在、首帧未测完：先不画（下一帧就有结果）。
                        else -> when (val status = ArtifactVerification.verify(artifact.sha256, digest)) {
                            ArtifactVerification.Status.OK -> Res.string.agent_artifact_verify_ok
                            ArtifactVerification.Status.MISMATCH -> Res.string.agent_artifact_verify_mismatch
                            ArtifactVerification.Status.UNDECLARED -> Res.string.agent_artifact_verify_undeclared
                            ArtifactVerification.Status.FILE_MISSING -> Res.string.agent_artifact_verify_missing
                        }
                    }
                    verifyLabel?.let { label ->
                        Spacer(Modifier.height(LocalAppSpacing.current.xs))
                        Text(
                            text = stringResource(label),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (label == Res.string.agent_artifact_verify_mismatch) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 审批状态胶囊的文案、底色、字色：原先分散的三张表合一，分支顺序与取值逐字沿用
 * （denied、过期（非四态）与未知值都落 error 色系）。返回 null = 不画状态胶囊。
 */
private data class ApprovalStatusStyle(
    val label: String,
    val badge: Color,
    val text: Color,
)

@Composable
private fun approvalStatusStyleOf(status: String, isExpired: Boolean): ApprovalStatusStyle? = when {
    status == "approved" -> ApprovalStatusStyle(
        stringResource(Res.string.agent_approval_status_approved),
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.onPrimaryContainer,
    )
    status == "allow_session" -> ApprovalStatusStyle(
        stringResource(Res.string.agent_approval_status_allowed_session),
        MaterialTheme.colorScheme.secondaryContainer,
        MaterialTheme.colorScheme.onSecondaryContainer,
    )
    status == "denied" -> ApprovalStatusStyle(
        stringResource(Res.string.agent_approval_status_denied),
        MaterialTheme.colorScheme.errorContainer,
        MaterialTheme.colorScheme.onErrorContainer,
    )
    // 「免审已撤销」不是错误：按下「恢复逐条审批」后的正常状态，用中性色。
    status == "revoked" -> ApprovalStatusStyle(
        stringResource(Res.string.agent_approval_status_revoked),
        MaterialTheme.colorScheme.surfaceVariant,
        MaterialTheme.colorScheme.onSurfaceVariant,
    )
    isExpired -> ApprovalStatusStyle(
        stringResource(Res.string.agent_approval_expired),
        MaterialTheme.colorScheme.errorContainer,
        MaterialTheme.colorScheme.onErrorContainer,
    )
    else -> null
}

/** `agent-run.state` 的本地化标签与颜色：同一判据只写一遍（未知取值原样透传）。 */
private data class RunStateStyle(val label: String, val color: Color)

@Composable
private fun runStateStyleOf(state: String): RunStateStyle {
    val label = when (state) {
        "running" -> stringResource(Res.string.agent_run_state_running)
        "succeeded" -> stringResource(Res.string.agent_run_state_succeeded)
        "failed" -> stringResource(Res.string.agent_run_state_failed)
        "cancelled" -> stringResource(Res.string.agent_run_state_cancelled)
        else -> state
    }
    val color = when (state) {
        "failed" -> MaterialTheme.colorScheme.error
        "cancelled" -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    return RunStateStyle(label, color)
}

/** 产物副行：`mime · 大小`，没有 `fileId` 时明说只有元数据。 */
@Composable
private fun artifactSubtitle(artifact: AgentArtifactUi): String {
    val size = artifact.bytes?.let { formatCount(it) }
    val parts = listOfNotNull(artifact.mime, size)
    return if (artifact.fileId == null) {
        stringResource(Res.string.agent_artifact_no_file)
    } else {
        parts.joinToString(" · ")
    }
}

/**
 * 待审批**置顶聚焦卡**（升级轮 task-20；原型 screenAgents 置顶条 relay.html:4692-4701）：
 * error 微晕底 + 20% error 细边 + r12 + 脉冲点（8px 实心 + 3px 同色 20% 环）+ 主标
 * 13/600 error + 副标 11 on-2 + 实心 error「立即审批」。**同一数据源**：消费
 * state.agentApprovals 的聚合（与 AgentApprovalsSection 队列同源不两套）—— 这里只画
 * 「聚合事实 + 直达」，逐条处置仍在队列（不画第二套批准/拒绝按钮）。
 */
@Composable
public fun PinnedApprovalFocusCard(
    pendingCount: Int,
    subtitle: String,
    onApproveClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pendingCount <= 0) return
    // r12 走 MaterialTheme.shapes.medium（token 档内的 12dp）；1dp 描边走
    // BorderStroke 参数（AppButtons / ThinkingBlock 的既定惯用法），不写
    // Modifier.border(1.dp, …) 字面量 —— designTokenUsageGate 的棘轮只许降不许升。
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.08f),
        border = BorderStroke(width = 1.dp, color = MaterialTheme.colorScheme.error.copy(alpha = 0.2f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md10),
            modifier = Modifier.padding(
                horizontal = LocalAppSpacing.current.lg14,
                vertical = LocalAppSpacing.current.md10,
            ),
        ) {
            Box(
                modifier = Modifier
                    .size(IconSizes.Inline)
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.2f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(LocalAppSpacing.current.sm)
                        .background(MaterialTheme.colorScheme.error, CircleShape),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.approval_focus_title, formatCount(pendingCount.toLong())),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AppButton(
                text = stringResource(Res.string.approval_focus_action),
                onClick = onApproveClick,
                size = AppButtonSize.Small,
                tone = AppButtonTone.Danger,
            )
        }
    }
}
