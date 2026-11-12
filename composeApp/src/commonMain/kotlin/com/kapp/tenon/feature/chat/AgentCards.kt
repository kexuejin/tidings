// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.chat

import com.kapp.tenon.domain.SYSTEM_ACTION_SESSION_EXEMPT
import com.kapp.tenon.domain.SenderIdentityContext
import com.kapp.tenon.domain.resolveNodeIdentity
import com.kapp.tenon.domain.sessionExemptActionIdOf
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import org.jetbrains.compose.resources.painterResource
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Spacer
import com.kapp.tenon.ui.ContentWidths
import com.kapp.tenon.ui.components.AppButtonSize
import com.kapp.tenon.ui.components.AppTextButton
import com.kapp.tenon.domain.model.MessageUi
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.ic_terminal
import com.kapp.tenon.generated.resources.ic_bot
import com.kapp.tenon.generated.resources.ic_shield
import com.kapp.tenon.generated.resources.ic_list
import com.kapp.tenon.generated.resources.ic_check_double
import com.kapp.tenon.generated.resources.ic_info
import com.kapp.tenon.generated.resources.ic_warn
import com.kapp.tenon.generated.resources.ic_close
import com.kapp.tenon.generated.resources.ic_chev
import com.kapp.tenon.generated.resources.ic_chevd
import com.kapp.tenon.generated.resources.card_checks_summary_failed
import com.kapp.tenon.generated.resources.card_checks_summary_incomplete
import com.kapp.tenon.generated.resources.card_checks_summary_passed
import com.kapp.tenon.generated.resources.card_check_drawer_close
import com.kapp.tenon.generated.resources.card_check_drawer_open
import com.kapp.tenon.generated.resources.card_check_time_format
import com.kapp.tenon.generated.resources.card_details_hide
import com.kapp.tenon.generated.resources.card_details_show
import com.kapp.tenon.generated.resources.card_session_exempt
import com.kapp.tenon.generated.resources.card_session_exempt_active
import com.kapp.tenon.generated.resources.card_decided_by
import com.kapp.tenon.generated.resources.card_expired
import com.kapp.tenon.generated.resources.card_progress_label
import com.kapp.tenon.generated.resources.card_status_aborted
import com.kapp.tenon.generated.resources.card_status_blocked
import com.kapp.tenon.generated.resources.card_status_done
import com.kapp.tenon.generated.resources.card_status_failed
import com.kapp.tenon.generated.resources.card_status_merged
import com.kapp.tenon.generated.resources.card_status_pending
import com.kapp.tenon.generated.resources.card_status_prototype
import com.kapp.tenon.generated.resources.card_status_running
import com.kapp.tenon.generated.resources.card_status_review_ready
import com.kapp.tenon.generated.resources.card_type_task_board
import com.kapp.tenon.generated.resources.card_type_diff_review
import com.kapp.tenon.generated.resources.card_type_prototype
import com.kapp.tenon.generated.resources.card_type_approval
import com.kapp.tenon.generated.resources.card_type_ask_question
import com.kapp.tenon.generated.resources.card_type_assignment
import com.kapp.tenon.generated.resources.card_type_direction_ask
import com.kapp.tenon.generated.resources.card_type_result
import com.kapp.tenon.generated.resources.card_type_status
import com.kapp.tenon.generated.resources.card_type_team_task_board
import com.kapp.tenon.generated.resources.card_type_timeout
import com.kapp.tenon.generated.resources.card_waiting_host
import com.kapp.tenon.generated.resources.agent_state_banner_failed
import com.kapp.tenon.ui.theme.LocalAppSpacing
import com.kapp.tenon.ui.theme.RelayElevations
import com.kapp.tenon.ui.theme.SurfaceTiers
import com.kapp.tenon.ui.theme.accents
import org.jetbrains.compose.resources.stringResource

/**
 * **声明式卡片渲染引擎**（方案 §4.3 / §5.3 / §7.3，M3 交付 1）。
 *
 * 数据源是**消息库**（`MessageUi.card`，M1-T8 落库列），不是 agentNode 直连 ——
 * 与其它消息同一条读回路径，掉线重连、翻历史、换设备看到的卡片完全一致。
 *
 * ## 5 类受限原子组件（§7.3-3）
 *
 * Banner / Progress / StepList / DiffViewer / ButtonGroup。只有这五类，多一个
 * 组件类型就多一分恶意排版的攻击面（内存/布局滥用）；不认识的 `type` 键被忽略，
 * 渲染引擎永远不因卡片体里多了一个字段而崩 —— 卡片体是**插件定义**的，
 * 协议层只守字节预算（[com.kapp.tenon.core.protocol.Limits.CARD_BYTES]）。
 *
 * ## 原位刷新 = 按 cardId 折叠只渲染最新 rev（§5.3）
 *
 * 落库是**追加式新行**（同 cardId、rev+1 的新消息，M1 定死，绕开
 * `INSERT OR REPLACE` / FTS 那套不变量）；「不刷屏」是 UI 语义：
 * [latestRevCardMessageIds] 按 cardId 折叠出**该渲染**的那些消息 id，
 * 旧 rev 整行收起（不渲染）——「UI 不新增可见气泡」由此成立。
 *
 * ## 审批防重的 UI 侧（§5.3 / §7.3-2）
 *
 * 点击 = 本机乐观置灰（[CardActionLock.PendingConfirm]）+ 交给壳的动作槽；
 * 宿主判定以 **rev+1 的新帧**回放全群，`decidedBy` 到达后全员进
 * [CardActionLock.Decided] 并显示批准人。跨端 ack 广播 M4 才接，
 * 三态语义已由 `MultiDeviceApprovalLockTest`（仿真判据）钉死。
 *
 * ## 文件切分（ARP-4）
 *
 * 外壳与主干在本文件；模型在 `AgentCardModel.kt`、解析在 `AgentCardParse.kt`、
 * 纯函数策略在 `AgentCardLogic.kt`、重区块在 `AgentCardParts.kt`。
 * 本次只是搬移，行为零变化。
 */

/**
 * 聊天流里的 agent 卡片（整幅宽，与屏幕协助卡同一条归属规矩：它是独立消息不是气泡附件）。
 *
 * 手机与桌面**共用这一份**（AGENTS §2.6：内容共享、外壳参数化）—— 两条壳都从
 * [com.kapp.tenon.feature.chat.ChatScreen] 的消息流里走到这里，没有第二份实现。
 *
 * [onAction] 是可空槽：null = 这个壳还没有动作通路，按钮组**整块不画** ——
 * 一个按下去没反应的按钮比没有更糟（DeadButtonGuard 同一条规矩）。
 * [eligibility] 是「这个人有没有资格驱动这个 agent」（§4.8 的结论，判定在宿主侧）：
 * [CardActionEligibility.ReadOnly] 同样整块不画按钮，而不是画一颗灰按钮。
 * [now] 由调用方给（会话里已经有一份时钟），组件自己不读 —— 过期子态因此可测、可截帧。
 *
 * 画不画、能不能点，全部由 [cardRenderPlanOf] 一个纯函数决定；这里只负责把它画出来。
 * `pendingActions` 由调用方持有（会话切换即重建），本组件不存状态。
 */
@Composable
fun AgentCardBubble(
    card: AgentCardUi,
    pendingActions: Set<String>,
    onAction: ((card: AgentCardUi, actionId: String) -> Unit)?,
    eligibility: CardActionEligibility = CardActionEligibility.Actionable,
    now: Long = 0L,
    modifier: Modifier = Modifier,
    /** 产物条目 → 消息流里同卡关联的已落盘文件路径；null = 本壳解析不出（预览不画）。 */
    artifactSavedPathFor: ((AgentCardArtifact) -> String?)? = null,
    /**
     * 端内渲染不了、要交给系统打开的产物（网页原型 `.html`）—— 落盘路径交给壳。
     * null = 本壳没有平台出口（Android `ACTION_VIEW` / 桌面 `Desktop.open` /
     * iOS Quick Look），该类入口整块不画。Markdown / Diff / 文本不走这里，
     * 它们由 `ArtifactPreviewDialog` 端内渲染。
     */
    onPreviewArtifact: ((AgentCardArtifact, String) -> Unit)? = null,
    /**
     * 本地动作的可见性判据：返回 false 的动作**整颗不画**（null = 全画）。
     * 用于「文件还没落到本机时，别画一颗点了没反应的『在浏览器打开原型』」。
     */
    isActionVisible: ((AgentCardAction) -> Boolean)? = null,
    /**
     * 「已由 %s 处理」知情行的名字解析上下文（D7）。null = 壳没给，退回 nodeId 原样。
     *
     * decidedBy 是**拍板节点的 nodeId**，不是名字 —— 直接印出来的形态是真机实锤过的：
     * 用户在自己手机上点的合并，卡面却写「已由 25222a45-4b84-… 处理」—— 自己不认得
     * 自己，还像一台陌生设备（AGENTS §2.4 的用户观感）。解析与消息署名同源
     * （[resolveNodeIdentity]）：self → 整句「你已处理」，其余走 昵称/群昵称/档案名，
     * 都没有才退 8 位短 id。给 null 就是没有这个壳的口径，不给假名字。
     */
    decidedByCtx: SenderIdentityContext? = null,
    /**
     * 该卡绑定的 Agent 会话 ID；点击工牌穿透进入会话详情。
     */
    sessionId: String? = null,
    /**
     * 工牌点击回调（点击头像/名称进入会话详情）。
     */
    onAgentBadgeClick: ((sessionId: String) -> Unit)? = null,
) {
    val sp = LocalAppSpacing.current
    val plan = cardRenderPlanOf(
        card = card,
        eligibility = eligibility,
        hasActionChannel = onAction != null,
        now = now,
    )
    Surface(
        // 升级稿 .acard（prototype/relay.html:515-518）：surface 底 + 8% 黑细边 + 16 圆角
        // + 双层柔和投影 + 壳自带 16×18 内边距与 12 的块间 gap（flex column）。
        // 旧稿（fdc4e1d6）的「块自带 padding + 发丝线」结构随升级稿整段作废。
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)),
        shadowElevation = RelayElevations.Card,
        modifier = modifier.fillMaxWidth().widthIn(max = ContentWidths.Settings),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = sp.lg),
            verticalArrangement = Arrangement.spacedBy(sp.md),
        ) {
            // 数字员工工牌（升级计划 Phase 2 要点 1）：avatar + 角色名 + worktree 分支
            // 芯片 + 呼吸微动效点。bot 缺席 = 老插件没给，整块不画（不是每个卡都有承办人）。
            card.bot?.let { CardHeaderRow(bot = it, sessionId = sessionId, onAgentBadgeClick = onAgentBadgeClick) }
            // 头行：上栏元信息（type 徽标 + 状态胶囊 + rev 游标）+ 独立主标题。
            if (card.type != null || card.title != null || card.rev > 0 || plan.showExpiredTag) {
                Column(verticalArrangement = Arrangement.spacedBy(sp.xs)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(sp.sm),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        card.type?.let { CardTypeTag(it) }
                        Spacer(Modifier.weight(1f))
                        if (plan.showExpiredTag) {
                            CardNeutralTag(text = stringResource(Res.string.card_expired))
                        }
                        CardStateTag(card)
                        // 这里曾经显示「第 N 版」。删掉的理由不是省地方，是它没有意义：
                        // rev 每广播一次就 +1（受理、结论、处理人落定各一次），真机上同一
                        // 张刚跑完的卡写着「第 4 版」—— 用户会以为前面还有三次任务。
                        // 卡的「变了没有」由状态胶囊和横幅表达；版本号是协议内部的事，
                        // 不该占用户的注意力。
                    }
                    if (card.title != null) {
                        Text(
                            text = card.title,
                            // 设计契约（componentSpecs.AgentCards.requiredTokens）要的就是
                            // titleSmall。先前这一行被改成 titleMedium，`designSpecDrift`
                            // 当天就红了 —— 规格里的人写明是这一档，改大要连规格一起改。
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = CARD_TITLE_MAX_LINES,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            // 状态胶囊行（原型稿 .mchips）：在头行与横幅之间 —— 机器事实的快照
            // （进度/门禁/受阻），宿主 cardOf 从既有状态派生。
            if (card.chips.isNotEmpty()) {
                CardChipsRow(card.chips)
            }
            // 业务上下文与意图说明框（原型稿 .ctx-box）
            card.context?.let { CardContextBox(it) }
            // 高危命令展示框（原型稿 .cmd-box）
            card.cmd?.let { CardCmdBox(it) }
            card.banner?.let { CardBannerRow(it) }
            card.progress?.let { CardProgressRow(it, card.elapsedSec) }
            // Acceptance 检查清单（升级计划 Phase 2 要点 2）：新式卡走 checks（带单项耗时 /
            // 观察结论 / 日志抽屉），老卡继续走 StepList。两者都画会把同一批步骤说两遍 ——
            // 解析侧 checks 本就由 items 回退而来，有 checks 时 steps 是它的影子。
            if (card.checks.isNotEmpty() && card.steps.isEmpty()) {
                CardChecksColumn(card.checks, card.cardId)
            } else if (card.steps.isNotEmpty()) {
                CardStepListColumn(card.steps)
            }
            card.diff?.let { CardDiffViewerRow(it) }
            card.diff?.takeIf { it.hunk.isNotEmpty() }?.let { CardDiffHunkViewer(it) }
            if (card.artifacts.isNotEmpty()) {
                CardArtifactRows(
                    artifacts = card.artifacts,
                    savedPathFor = { artifact -> artifactSavedPathFor?.invoke(artifact) },
                    onPreviewArtifact = onPreviewArtifact,
                )
            }
            if (card.options.isNotEmpty()) {
                CardOptionListRows(card, pendingActions, plan.actionsEnabled, onAction)
            }
            // 宿主判定的知情行（.decided{padding:6px 14px 10px}，11px on-2）：
            // 它不是按钮，是「这件事已被谁定了」的事实 —— 就算这个壳没有动作通路
            // （onAction == null），它也照画。
            card.decidedBy?.let { decided ->
                // D7：decidedBy 是拍板节点的 nodeId，先过一遍与消息署名同源的解析再上屏。
                // self ⇒ 本机昵称（SenderIdentityContext.selfNick，真机上即设备名称）；
                // 别人 ⇒ 昵称/群昵称/档案名；解析不出 ⇒ 8 位短 id（resolveNodeIdentity
                // 的最后一档），仍比 36 位裸 id 可读。ctx 为 null 的壳保持老形态。
                //
                // self 整句资源（card_decided_by_self「你已处理」），但本机编译
                // 环境对**新增**资源键一律 Unresolved（生成物在盘上含该键、编译仍报错，
                // 冷建/清守护/清 .gradle 均复现；老键同文件同形状可解析）——如实记账，
                // 先落 displayName 方案，「你已处理」待环境恢复再上（证据 §十三）。
                val decidedText = if (decidedByCtx != null) {
                    stringResource(Res.string.card_decided_by, resolveNodeIdentity(decided, decidedByCtx).displayName)
                } else {
                    stringResource(Res.string.card_decided_by, decided)
                }
                // 升级稿 .decided（relay.html:580-581）：surface-2 底 + r8 + 12 on-2，块化知情行。
                Text(
                    text = decidedText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceTiers.Raised, MaterialTheme.shapes.small)
                        .padding(horizontal = sp.md, vertical = sp.sm),
                )
            }
            // 只读态必须说清「为什么没有按钮」（.wait 同 .decided：580 段的另一形态，
            // text-align:center —— relay.html:584-585）。
            if (plan.showWaitingForHost) {
                Text(
                    text = stringResource(Res.string.card_waiting_host),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceTiers.Raised, MaterialTheme.shapes.small)
                        .padding(horizontal = sp.md, vertical = sp.sm),
                )
            }
            if (plan.showActions) {
                // 打回动作的就地展开（升级计划 Phase 2 要点 4）：reject 按钮被点时
                // **不直接派发**，先开反馈抽屉 —— 打回是要带话的，空手打回等于只说了
                // 「不行」。抽屉里提交才真正派发（带 feedbackText 编码的 actionId）。
                var revisionDrawerOpen by remember(card.cardId, card.rev) { mutableStateOf(false) }
                val hasReject = card.actions.any { it.actionId == SYSTEM_ACTION_REJECT_REVISION }
                Column(verticalArrangement = Arrangement.spacedBy(sp.sm)) {
                    CardButtonGroupRow(
                        card = card,
                        pendingActions = pendingActions,
                        actionsEnabled = plan.actionsEnabled,
                        onAction = { c, actionId ->
                            if (actionId == SYSTEM_ACTION_REJECT_REVISION) {
                                revisionDrawerOpen = !revisionDrawerOpen
                            } else {
                                onAction!!(c, actionId)
                            }
                        },
                        isActionVisible = isActionVisible,
                    )
                    if (hasReject && revisionDrawerOpen) {
                        CardRevisionDrawer(card, pendingActions, plan.actionsEnabled, onAction!!)
                    }
                }
            }
            // 会话免审行：宿主在 subActions 里给了这条语义才画（数据到不了的字段不画）。
            // 点击把 SYSTEM_ACTION_SESSION_EXEMPT|<cardId> 发进同一个动作通路；
            // 过期/无资格/无通路都不给点（与按钮组同一套纪律）。
            CardSessionExemptRow(card, pendingActions, plan.actionsEnabled, onAction)
        }
    }
}

/**
 * 数字员工工牌头行（升级计划 Phase 2 要点 1，原型 `.acard .hd`）。
 *
 * avatar 用**字母座**而不是位图：`avatar` 字段在载荷里是 emoji/短代号（原型里是
 * 🤖/⚡），按文本画进圆角座即可，图片解码与远端拉取都不属于卡片渲染的职责。
 * 呼吸微动效点（`.bdot`）只在 `run` 档动：done/fail/warn 是终态事实，动效属于
 * 「正在发生」，不属于「已经发生」。
 */
@Composable
internal fun CardHeaderRow(
    bot: AgentCardBot,
    sessionId: String? = null,
    onAgentBadgeClick: ((sessionId: String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val sp = LocalAppSpacing.current
    val canClick = onAgentBadgeClick != null && !sessionId.isNullOrBlank()
    val clickModifier = if (canClick) {
        Modifier.clickable { onAgentBadgeClick(sessionId) }
    } else {
        Modifier
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(sp.sm),
        modifier = modifier.fillMaxWidth(),
    ) {
        // .hd .bot-badge 的字母座：agent 紫微底 + 角色名首字（图形级 3:1 用 agentAccent）。
        // 圆角 6px 属档外值（原型 .bot-badge 6px）：按 ShapeRoles 表头裁决就近归并 extraSmall。
        Box(
            modifier = Modifier
                .size(com.kapp.tenon.ui.theme.IconSizes.Title)
                .background(MaterialTheme.accents.agentAccentContainer, MaterialTheme.shapes.extraSmall)
                .then(clickModifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = bot.avatar?.takeIf { it.isNotBlank() }
                    ?: bot.name.take(1).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.accents.agentAccentText,
            )
        }
        Text(
            text = bot.name,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).then(clickModifier),
        )
        CardBreathDot(bdot = bot.bdot)
        bot.branch?.let { branch ->
            // .hd .wk-chip：等宽 11px surface-2 底 —— worktree 分支是机器事实，不做语义着色。
            Text(
                text = branch,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .background(SurfaceTiers.Raised, MaterialTheme.shapes.extraSmall)
                    .padding(horizontal = sp.sm6, vertical = sp.xxs)
                    .semantics { contentDescription = branch },
            )
        }
    }
}

/**
 * 呼吸微动效点（原型 `.bdot`：7px 圆点，run 档 1.8s 呼吸）。
 *
 * alpha 在 0.4f~1.0f 循环（升级计划 Phase 2 要点 1 给定区间）；档位色逐字对齐原型：
 * run=primary、done=online、fail=error、warn=onSecondaryContainer（`--on-queued-c`）。
 * 未知档位按中性画 —— 多一个未知值不该让工牌消失（宽容解析的同一条口径）。
 */
@Composable
private fun CardBreathDot(bdot: String?, modifier: Modifier = Modifier) {
    val sp = LocalAppSpacing.current
    val baseColor = when (bdot) {
        "run" -> MaterialTheme.colorScheme.primary
        "done" -> MaterialTheme.accents.online
        "fail" -> MaterialTheme.colorScheme.error
        "warn" -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val alpha: Float = if (bdot == "run") {
        val infinite = rememberInfiniteTransition(label = "card-bdot")
        val breath by infinite.animateFloat(
            initialValue = 0.4f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "card-bdot-alpha",
        )
        breath
    } else {
        1f
    }
    // 点径 7px 是档外值：落 6dp 间距档（sm6），与单选圆点 .size(sp.sm) 同一条
    // 「小元素吃间距档」的既有先例。
    Box(
        modifier = modifier
            .size(sp.sm6)
            .background(baseColor.copy(alpha = alpha), CircleShape),
    )
}

/**
 * Acceptance 检查清单（升级计划 Phase 2 要点 2，原型 `.acard .checks`）：
 * 状态指示 + 等宽单项耗时 + 一行观察结论；带日志（drawer）的行可点开日志抽屉。
 *
 * 展开态是**行内本地状态**：卡片体是插件定义的只读投影，抽屉不改变任何协议事实，
 * 所以不进模型、不进 pendingActions —— 会话切换即重建，与本组件「不存状态」的
 * 既有纪律一致。
 */
@Composable
private fun CardChecksColumn(checks: List<AgentCardCheck>, cardId: String) {
    if (checks.isEmpty()) return
    val sp = LocalAppSpacing.current
    val summary = agentCardChecksSummaryOf(checks)
    val hasFailures = summary.failed + summary.blocked > 0
    val hasIncomplete = summary.incomplete > 0
    val shouldCollapseByDefault = checks.size > 2 && !hasFailures
    // key 用 cardId：rev 更新（心跳/失败追加）不会把用户刚展开的抽屉收起。
    var expanded by remember(cardId) { mutableStateOf(!shouldCollapseByDefault) }
    if (hasFailures) {
        LaunchedEffect(hasFailures) { expanded = true }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(sp.sm6),
        modifier = Modifier.padding(vertical = sp.xxs),
    ) {
        if (checks.size > 2) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = when {
                    hasFailures -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                    hasIncomplete -> SurfaceTiers.Raised
                    else -> SurfaceTiers.Raised
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = sp.md10, vertical = sp.sm6),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = when {
                            hasFailures -> stringResource(
                                Res.string.card_checks_summary_failed,
                                summary.failed + summary.blocked,
                                summary.total,
                            )
                            hasIncomplete -> stringResource(
                                Res.string.card_checks_summary_incomplete,
                                summary.incomplete,
                                summary.total,
                            )
                            else -> stringResource(Res.string.card_checks_summary_passed, summary.total)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = when {
                            hasFailures -> MaterialTheme.colorScheme.error
                            hasIncomplete -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.accents.online
                        },
                    )
                    Icon(
                        painter = painterResource(if (expanded) Res.drawable.ic_chevd else Res.drawable.ic_chev),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(sp.sm),
                    )
                }
            }
        }
        if (expanded || checks.size <= 2) {
            checks.forEach { check ->
                CardCheckRow(check)
            }
        }
    }
}

/** 步骤/检查项状态 → 本地化文案：两处文案表逐字同源。 */
@Composable
private fun stepStatusLabel(status: AgentCardStepStatus): String = when (status) {
    AgentCardStepStatus.Done -> stringResource(Res.string.card_status_done)
    AgentCardStepStatus.Running -> stringResource(Res.string.card_status_running)
    AgentCardStepStatus.Pending -> stringResource(Res.string.card_status_pending)
    AgentCardStepStatus.Failed -> stringResource(Res.string.card_status_failed)
    AgentCardStepStatus.Blocked -> stringResource(Res.string.card_status_blocked)
}

@Composable
private fun CardCheckRow(check: AgentCardCheck) {
    val sp = LocalAppSpacing.current
    var drawerOpen by remember(check.id) { mutableStateOf(check.open) }
    val statusLabel = stepStatusLabel(check.status)
    val clickable = check.drawer != null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 原型 .chk 10px：档外值就近归并 shapes.small（CardNeutralTag 同一裁决）。
            .background(SurfaceTiers.Raised, MaterialTheme.shapes.small)
            .let { m ->
                if (clickable) {
                    m.clickable { drawerOpen = !drawerOpen }
                } else {
                    m
                }
            }
            .padding(horizontal = sp.md10, vertical = sp.sm6)
            .semantics { contentDescription = statusLabel },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(sp.sm),
        ) {
            StepStatusIcon(check.status)
            Text(
                text = check.label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (check.status == AgentCardStepStatus.Running) FontWeight.SemiBold else FontWeight.Normal,
                color = if (check.status == AgentCardStepStatus.Running) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = CARD_STEP_LABEL_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            check.time?.let { time ->
                // .chk-meta：等宽耗时标签 —— 机器事实用等宽字，与 hunk/分支芯片同一条口径。
                Text(
                    text = stringResource(Res.string.card_check_time_format, time),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (clickable) {
                Icon(
                    painter = painterResource(if (drawerOpen) Res.drawable.ic_chevd else Res.drawable.ic_chev),
                    contentDescription = stringResource(
                        if (drawerOpen) Res.string.card_check_drawer_close else Res.string.card_check_drawer_open,
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(com.kapp.tenon.ui.theme.IconSizes.Inline),
                )
            }
        }
        check.observation?.let { observation ->
            // 双层文本契约的第一层：一行人类可读的观察结论（第二层是下面的日志抽屉）。
            Text(
                text = observation,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = sp.xxs),
            )
        }
        if (clickable && drawerOpen) {
            // .chk-drawer：等宽日志堆栈，pre-wrap 语义 —— 行数不设限的是插件给的现场，
            // 超长在载荷侧就该被截断（帧预算归协议管，渲染层不再猜一遍）。
            Text(
                text = check.drawer.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = sp.sm6),
            )
        }
    }
}

/**
 * 会话免审的一行开关（升级计划 §4.2 分级免审的 UI 侧；L1 会话免审）。
 *
 * 它是 subAction 语义的落点：宿主没给这条 subAction 的卡不画（数据到不了的字段
 * 不画，与 ButtonGroup 的 sub-row 同一条纪律）。未生效时点击把免审动作发进同一个
 * 动作通路（单槽编码见 [sessionExemptActionIdOf]）；已生效后换文案并摘掉点击 ——
 * 「已免审」是事实不是动作，再画可点开关就是骗点击。
 */
@Composable
private fun CardSessionExemptRow(
    card: AgentCardUi,
    pendingActions: Set<String>,
    enabled: Boolean,
    onAction: ((card: AgentCardUi, actionId: String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    // 判定走纯函数（[isSessionExemptActive]）：宿主回放 style=active 与本机动作在途都算
    // 已生效 —— 开启态是事实不是动作，可点开关就此消失，只留一行状态。
    val exemptActive = isSessionExemptActive(card, pendingActions)
    val exemptOffered = card.subActions.any { it.actionId == SYSTEM_ACTION_SESSION_EXEMPT }
    if (!exemptOffered) return
    // 动作通路与资格缺一不可：没有通路（onAction == null）或当前无资格（ReadOnly，
    // renderPlan 已把按钮组整块收掉）时只画状态行 —— 按下去没反应的开关比没有更糟。
    val dispatchable = onAction != null && enabled && !exemptActive
    val sp = LocalAppSpacing.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(sp.xs),
        modifier = modifier
            .background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.small)
            .clickable(enabled = dispatchable) { onAction!!(card, sessionExemptActionIdOf(card.cardId)) }
            .padding(horizontal = sp.md10, vertical = sp.sm6),
    ) {
        Icon(
            painter = painterResource(Res.drawable.ic_shield),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(com.kapp.tenon.ui.theme.IconSizes.Inline),
        )
        Text(
            text = stringResource(
                if (exemptActive) Res.string.card_session_exempt_active else Res.string.card_session_exempt,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/**
 * 头行的 type 徽标（原型 `.hd` 里的 `.tag.agent`：agent-c/agent-t 对、11px 600、
 * 胶囊圆角、带图标与本地化文案）。
 */
@Composable
private fun CardTypeTag(type: String) {
    val sp = LocalAppSpacing.current
    val (label, iconRes) = when (type) {
        "task-board" -> stringResource(Res.string.card_type_task_board) to Res.drawable.ic_terminal
        "diff-review" -> stringResource(Res.string.card_type_diff_review) to Res.drawable.ic_terminal
        "prototype" -> stringResource(Res.string.card_type_prototype) to Res.drawable.ic_bot
        "approval" -> stringResource(Res.string.card_type_approval) to Res.drawable.ic_shield
        "ask_question" -> stringResource(Res.string.card_type_ask_question) to Res.drawable.ic_list
        // 下面四档在发射侧都被折叠进了别的形态（`result`/`timeout` → task-board 的终态、
        // `status` → agentState 横幅、`assignment` → 尚无形态），但**解析侧是宽容的** ——
        // 插件真发这个 type 时卡会照画。缺这一档的后果是徽标印英文原文，所以照补。
        "result" -> stringResource(Res.string.card_type_result) to Res.drawable.ic_list
        "status" -> stringResource(Res.string.card_type_status) to Res.drawable.ic_bot
        "timeout" -> stringResource(Res.string.card_type_timeout) to Res.drawable.ic_terminal
        "assignment" -> stringResource(Res.string.card_type_assignment) to Res.drawable.ic_list
        // 手机侧 invoke 卡：`type` 是 `team-task-board`（`AgentInvokeBoardCard`），不是
        // `task-board` —— 走到 else 的话徽标会印出英文原文，所以单独一档。
        "team-task-board" -> stringResource(Res.string.card_type_team_task_board) to Res.drawable.ic_terminal
        // 求方向卡：问「这是调整还是新任务」，语义同「要你定一件事」⇒ 与 ask_question 同图标。
        "direction-ask" -> stringResource(Res.string.card_type_direction_ask) to Res.drawable.ic_list
        else -> type to Res.drawable.ic_bot
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(sp.xs),
        modifier = Modifier
            .background(MaterialTheme.accents.agentAccent.copy(alpha = 0.09f), RoundedCornerShape(6.dp))
            .padding(horizontal = sp.sm, vertical = sp.xxs),
    ) {
        androidx.compose.material3.Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.accents.agentAccentText,
            // 12dp 与间距档 md 同值：改吃 token（视觉零变化）。
            modifier = Modifier.size(sp.md),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.accents.agentAccentText,
        )
    }
}

/** 任务状态胶囊（待合并/进行中/已完成/受阻/已取消）。 */
@Composable
private fun CardStateTag(card: AgentCardUi) {
    val sp = LocalAppSpacing.current
    // 发出方声明的 state 优先。反推那套（下面）留着给不带 state 的老卡：
    // 它认不出「已中止」，会把一张停掉的卡继续标成「待合并」。
    val declared = when (card.state) {
        "aborted" -> Triple(
            stringResource(Res.string.card_status_aborted),
            MaterialTheme.accents.warn.copy(alpha = 0.12f),
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        "blocked" -> Triple(
            stringResource(Res.string.card_status_blocked),
            MaterialTheme.accents.warn.copy(alpha = 0.12f),
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        // 阶段 A 的待选卡：它既不是「受阻」也不是「待合并」—— 它在等人选方案。
        // 从前没有这一档，于是落到下面的「有 approve-prototype 动作 ⇒ 待合并」兜底，
        // 真机上「原型待选」被标成「待合并」，用户以为可以合并一个还不存在的改动。
        "prototype" -> Triple(
            stringResource(Res.string.card_status_prototype),
            MaterialTheme.accents.agentAccent.copy(alpha = 0.12f),
            MaterialTheme.accents.agentAccentText,
        )
        // 合流成功的终态（测试方案 §4.3 的判据）：以前这个 state 不在映射里，卡上
        // 干脆没有胶囊 —— 「合并成功了没有」只能靠横幅那句话去猜。
        "merged" -> Triple(
            stringResource(Res.string.card_status_merged),
            MaterialTheme.accents.online.copy(alpha = 0.12f),
            MaterialTheme.accents.online,
        )
        "review_ready" -> Triple(
            stringResource(Res.string.card_status_review_ready),
            MaterialTheme.accents.online.copy(alpha = 0.12f),
            MaterialTheme.accents.online,
        )
        "working" -> Triple(
            stringResource(Res.string.card_status_running),
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            MaterialTheme.colorScheme.primary,
        )
        else -> null
    }
    val (label, bg, fg) = declared ?: when {
        card.actions.any { it.actionId == SYSTEM_ACTION_MERGE_PATCH } ->
            Triple(
                stringResource(Res.string.card_status_review_ready),
                MaterialTheme.accents.online.copy(alpha = 0.12f),
                MaterialTheme.accents.online,
            )
        card.actions.any { it.actionId == "approve-prototype" } ->
            Triple(
                // 老卡（发出方还没声明 state）的兜底：有「Approve & Develop」就是在等选方案，
                // 不是「待合并」—— 待合并说的是「有补丁可以合」，这里连补丁都还没有。
                stringResource(Res.string.card_status_prototype),
                MaterialTheme.accents.agentAccent.copy(alpha = 0.12f),
                MaterialTheme.accents.agentAccentText,
            )
        card.actions.any { it.actionId == "retry" } ->
            Triple(
                stringResource(Res.string.card_status_blocked),
                MaterialTheme.accents.warn.copy(alpha = 0.12f),
                MaterialTheme.colorScheme.onSecondaryContainer,
            )
        card.progress == 100 ->
            Triple(
                stringResource(Res.string.card_status_done),
                MaterialTheme.accents.online.copy(alpha = 0.12f),
                MaterialTheme.accents.online,
            )
        card.progress != null ->
            Triple(
                stringResource(Res.string.card_status_running),
                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                MaterialTheme.colorScheme.primary,
            )
        else -> null
    } ?: return
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = fg,
        modifier = Modifier
            .background(bg, RoundedCornerShape(6.dp))
            .padding(horizontal = sp.sm, vertical = sp.xxs),
    )
}

/**
 * 中性状态小标签（原型 `.apst.exp`：`--surface-2 / --on-2` —— 原型注释原话
 * 「过期不是错误，是『不再可用』」）。圆角取 M3 shapes.small（8）：
 * `.apst` 的 6px 属低频档外值，按 ShapeRoles 表头的裁决留给并排截图就近归并。
 */
@Composable
private fun CardNeutralTag(text: String) {
    val sp = LocalAppSpacing.current
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .background(SurfaceTiers.Raised, MaterialTheme.shapes.small)
            .padding(horizontal = sp.sm, vertical = sp.xxs),
    )
}

/** 原子 1/5 · Banner（§7.3）：一行带色的说明。 */
@Composable
private fun CardBannerRow(banner: AgentCardBanner) {
    val sp = LocalAppSpacing.current
    val (_, contentColor) = bannerColorsOf(banner.tone)
    val iconRes = when (banner.tone) {
        AgentCardBannerTone.Info -> Res.drawable.ic_info
        AgentCardBannerTone.Success -> Res.drawable.ic_check_double
        AgentCardBannerTone.Warn -> Res.drawable.ic_warn
        AgentCardBannerTone.Error -> Res.drawable.ic_close
    }
    BannerSurface(tone = banner.tone) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(sp.sm),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = sp.md, vertical = sp.sm),
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(com.kapp.tenon.ui.theme.IconSizes.Label),
            )
            Text(
                text = banner.text,
                style = MaterialTheme.typography.bodySmall,
                color = contentColor,
                maxLines = CARD_BANNER_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 横幅的**外壳**（圆角 + 底色 + 内边距）：卡内 Banner 与群流执行状态横幅共用。
 *
 * 抽出来的理由与 [bannerColorsOf] 相同，只是更进一步：只共用颜色、不共用外壳，
 * 两处的圆角/内边距迟早会各走各的 —— 那时同一个「失败」不但颜色对不上，
 * 连块的大小都不一样，看起来像两个不同组件在说两件事。
 *
 * 形状 = 控件圆角 12（原型 `.banner{border-radius:var(--r-ctl)}`，`--r-ctl:12`）。
 * 以前挂 [ShapeRoles.DayCell]（7dp）说不出出处——横幅不是历史搜索日期格。
 * 内边距由 [BannerText] 自带（原型 `.bn{padding:8px 14px}`），壳不再叠加。
 */
@Composable
private fun BannerSurface(
    tone: AgentCardBannerTone,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val (container, contentColor) = bannerColorsOf(tone)
    val sp = LocalAppSpacing.current
    // 升级稿 .bn（relay.html:527-528）：圆角 10 + 柔和微底 + 左侧 3px 贯穿状态线（对齐引用气泡视觉语言）
    Surface(shape = MaterialTheme.shapes.small, color = container, contentColor = contentColor, modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(sp.oxs3)
                    .background(contentColor),
            )
            Box(modifier = Modifier.weight(1f)) {
                content()
            }
        }
    }
}

/** 横幅的**正文**（字阶 / 行数上限 / 省略）。与 [BannerSurface] 成对，一处定义。 */
@Composable
private fun BannerText(text: String) {
    val sp = LocalAppSpacing.current
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        maxLines = CARD_BANNER_MAX_LINES,
        overflow = TextOverflow.Ellipsis,
        // 升级稿 .bn{padding:10px 14px}（relay.html:527）。
        modifier = Modifier.fillMaxWidth().padding(horizontal = sp.lg14, vertical = sp.md10),
    )
}

/**
 * 色调 → (容器色, 内容色)。**只此一份**：卡内 Banner 与群流里的执行状态横幅共用它，
 * 否则同一个「失败」在两处会长成两种红（§2.6 一个 UI 元素只有一处定义）。
 *
 * 四对取值逐字对齐原型 `.acard` 的横幅区块（prototype/relay.html `.bn` 系列与
 * `.banner` 系列，两处共用同一组语义色对）：
 * - Info = `--surface-2 / --on-2`（代码侧即 surfaceVariant / onSurfaceVariant，值逐字相同）；
 * - Success(`done`) = `--primary-c / --on-primary-c`（= primaryContainer / onPrimaryContainer）；
 * - Warn = `--queued-c / --on-queued-c`（= secondaryContainer / onSecondaryContainer）；
 * - Error = `--error-c / --on-error-c`（= errorContainer / onErrorContainer，本就正确）。
 *
 * 以前 Info 挂在 secondaryContainer（=`--queued-c`，跟 Warn 撞对）、Warn 挂
 * tertiaryContainer（原型里不存在这个蓝调对）、Success 用 online 叠 16% alpha 合成——
 * 三者都是「说不出原型出处」的取值；合成 alpha 同时是一处二次 alpha（对比度审计 D11 族）。
 */
@Composable
private fun bannerColorsOf(tone: AgentCardBannerTone): Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color> =
    when (tone) {
        // 升级稿 .bn 四态（relay.html:529-532）：微晕底 + 语义色直印（不再是 on-container 对）。
        // 公式色归一：紫 107,78,230 = agentAccent、绿 16,185,129 = --online 族（RelaySuccess）、
        // 琥珀 245,158,11 = accents.warn（本次新增 token）、红 239,68,68 = error（#E63946 近亲）。
        AgentCardBannerTone.Info ->
            MaterialTheme.accents.agentAccent.copy(alpha = 0.06f) to MaterialTheme.accents.agentAccentText
        AgentCardBannerTone.Success ->
            MaterialTheme.accents.online.copy(alpha = 0.07f) to MaterialTheme.colorScheme.onPrimaryContainer
        AgentCardBannerTone.Warn ->
            MaterialTheme.accents.warn.copy(alpha = 0.08f) to MaterialTheme.colorScheme.onSecondaryContainer
        AgentCardBannerTone.Error ->
            MaterialTheme.colorScheme.error.copy(alpha = 0.08f) to MaterialTheme.colorScheme.error
    }

/**
 * 执行状态四态 → 横幅色调（设计稿 v2 §3 的 `status` 口径：
 * accept→Info、blocked→Warn、completed→Done、failed→Danger）。
 *
 * **未知状态返回 null ⇒ 不画**：协议层已经只放行这四个值，走到这里的未知值只可能来自
 * 更早版本的库或手工构造的测试数据 —— 画一个没有色调的横幅比不画更让人困惑。
 */
fun agentStateBannerToneOf(state: String?): AgentCardBannerTone? = when (state) {
    "accept" -> AgentCardBannerTone.Info
    "blocked" -> AgentCardBannerTone.Warn
    "completed" -> AgentCardBannerTone.Success
    "failed" -> AgentCardBannerTone.Error
    else -> null
}

/**
 * 群流里的**执行状态轻横幅**（设计稿 v2 §5「失败如实」）。
 *
 * 数据来源是消息库里的 `MessageUi.agentState`（协议 `agentState` 键，M1 已在白名单）：
 * 宿主在「插件接不了活 / 执行没做成」时发一条 agent 署名的群文本，正文由领域侧的
 * `RelayUiTexts` 给出。这里只管画 —— 判定在领域层，措辞在 strings.xml。
 *
 * 为什么不是气泡：它不是「谁说了句话」，是「这次派单没成」的状态说明。用横幅画，
 * 位置与语气都不抢卡片，也不会被当成一条可以回复的消息。
 *
 * 正文为空时（帧由没有文案桥的壳发出）用一句本地兜底：**空横幅等于沉默**，
 * 而沉默正是这个组件存在的理由。
 */
@Composable
fun AgentStateBannerRow(state: String, text: String, modifier: Modifier = Modifier) {
    val tone = agentStateBannerToneOf(state) ?: return
    BannerSurface(tone = tone, modifier = modifier.fillMaxWidth()) {
        BannerText(
            text.ifBlank {
                if (state == "failed") stringResource(Res.string.agent_state_banner_failed) else ""
            },
        )
    }
}

/** 原子 2/5 · Progress（§7.3）：6px 细条 + 等宽百分比（原型 `.pg` 区块）。 */
@Composable
private fun CardProgressRow(progress: Int, elapsedSec: Long? = null) {
    val sp = LocalAppSpacing.current
    val label = stringResource(Res.string.card_progress_label, progress)
    val elapsedLabel = elapsedSec?.takeIf { it >= 0 }?.let { "${it}s" }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(sp.md10),
        // 升级稿 .pg{padding:2px 0}（relay.html:535）—— 块间 12 gap 由壳承担。
        modifier = Modifier
            .padding(vertical = sp.xxs)
            .semantics { contentDescription = label },
    ) {
        // .pg .bar（relay.html:536-537）：6px 轨、圆角 10、底 = --surface-3（Inset 档）。
        // 轨高 6dp 与间距档 sm6 同值：改吃 token（视觉零变化）。
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = SurfaceTiers.Inset,
            modifier = Modifier.weight(1f).height(sp.sm6),
        ) {
            Box(Modifier.fillMaxWidth()) {
                // .pg .bar i：填充 = primary → #8B5CF6 的 90° 渐变（升级稿给定的唯一
                // 渐变；终点紫 = accents.accentGradientEnd（#8B5CF6，升级稿 relay.html:537 原型给定的渐变终点）。
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress / 100f)
                        .height(sp.sm6)
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.accents.accentGradientEnd,
                                ),
                            ),
                        ),
                )
            }
        }
        // .pg .pc（relay.html:538-539）：12/600 mono tabular（labelSmall 11 → bodySmall 档带 600）。
        Text(
            text = if (elapsedLabel != null) "$label ($elapsedLabel)" else label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * 原子 3/5 · StepList（§7.3）：状态字形 + 标签 + 承办人徽标，行间发丝线
 * （原型 `.sts{padding:4px 14px 8px}` + `.st+.st{border-top:1px solid var(--hair)}`）。
 */
@Composable
private fun CardStepListColumn(steps: List<AgentCardStep>) {
    val sp = LocalAppSpacing.current
    if (steps.size <= 4) {
        Column(
            verticalArrangement = Arrangement.spacedBy(sp.sm6),
            modifier = Modifier.padding(vertical = sp.xs),
        ) {
            steps.forEach { step -> CardStepRow(step) }
        }
        return
    }

    var expanded by remember { mutableStateOf(false) }
    val priorityIdx = steps.indexOfFirst {
        it.status == AgentCardStepStatus.Running ||
            it.status == AgentCardStepStatus.Failed ||
            it.status == AgentCardStepStatus.Blocked
    }
    val visibleSteps = if (expanded) {
        steps
    } else if (priorityIdx >= 0) {
        val start = (priorityIdx - 1).coerceAtLeast(0)
        val end = (priorityIdx + 1).coerceAtMost(steps.lastIndex)
        steps.slice(start..end)
    } else {
        steps.takeLast(3)
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(sp.sm6),
        modifier = Modifier.padding(vertical = sp.xs),
    ) {
        visibleSteps.forEach { step -> CardStepRow(step) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            AppTextButton(
                text = stringResource(if (expanded) Res.string.card_details_hide else Res.string.card_details_show),
                onClick = { expanded = !expanded },
                size = AppButtonSize.Compact,
            )
        }
    }
}

/**
 * 步骤状态指示图标（16×16）：使用 Canvas 精准绘制，消除字体字形基准线偏心与视觉重量不均。
 */
@Composable
private fun StepStatusIcon(status: AgentCardStepStatus) {
    val onlineColor = MaterialTheme.accents.online
    val primaryColor = MaterialTheme.colorScheme.primary
    val errorColor = MaterialTheme.colorScheme.error
    val warnColor = MaterialTheme.accents.warn
    val insetColor = SurfaceTiers.Inset
    val surfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant

    Canvas(modifier = Modifier.size(com.kapp.tenon.ui.theme.IconSizes.Label)) {
        val radius = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)

        when (status) {
            AgentCardStepStatus.Done -> {
                drawCircle(
                    color = onlineColor.copy(alpha = 0.15f),
                    radius = radius,
                    center = center,
                )
                val checkPath = Path().apply {
                    moveTo(size.width * 0.28f, size.height * 0.52f)
                    lineTo(size.width * 0.44f, size.height * 0.68f)
                    lineTo(size.width * 0.72f, size.height * 0.34f)
                }
                drawPath(
                    path = checkPath,
                    color = onlineColor,
                    style = Stroke(
                        width = 1.8.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
            }
            AgentCardStepStatus.Running -> {
                drawCircle(
                    color = primaryColor.copy(alpha = 0.14f),
                    radius = radius,
                    center = center,
                )
                drawCircle(
                    color = primaryColor.copy(alpha = 0.35f),
                    radius = radius - 0.5.dp.toPx(),
                    center = center,
                    style = Stroke(width = 1.2.dp.toPx()),
                )
                val trianglePath = Path().apply {
                    val shiftX = 0.5.dp.toPx()
                    val halfH = 3.2.dp.toPx()
                    val halfW = 2.8.dp.toPx()
                    moveTo(center.x - halfW * 0.7f + shiftX, center.y - halfH)
                    lineTo(center.x + halfW * 1.3f + shiftX, center.y)
                    lineTo(center.x - halfW * 0.7f + shiftX, center.y + halfH)
                    close()
                }
                drawPath(
                    path = trianglePath,
                    color = primaryColor,
                    style = Fill,
                )
            }
            AgentCardStepStatus.Pending -> {
                drawCircle(
                    color = insetColor,
                    radius = radius,
                    center = center,
                )
                drawCircle(
                    color = surfaceVariantColor.copy(alpha = 0.4f),
                    radius = 2.2.dp.toPx(),
                    center = center,
                )
            }
            AgentCardStepStatus.Failed -> {
                drawCircle(
                    color = errorColor.copy(alpha = 0.15f),
                    radius = radius,
                    center = center,
                )
                val strokeW = 1.8.dp.toPx()
                val offset = 4.dp.toPx()
                drawLine(
                    color = errorColor,
                    start = Offset(center.x - offset, center.y - offset),
                    end = Offset(center.x + offset, center.y + offset),
                    strokeWidth = strokeW,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = errorColor,
                    start = Offset(center.x + offset, center.y - offset),
                    end = Offset(center.x - offset, center.y + offset),
                    strokeWidth = strokeW,
                    cap = StrokeCap.Round,
                )
            }
            AgentCardStepStatus.Blocked -> {
                drawCircle(
                    color = warnColor.copy(alpha = 0.15f),
                    radius = radius,
                    center = center,
                )
                val strokeW = 1.8.dp.toPx()
                val barH = 3.5.dp.toPx()
                val barGap = 2.2.dp.toPx()
                drawLine(
                    color = warnColor,
                    start = Offset(center.x - barGap, center.y - barH),
                    end = Offset(center.x - barGap, center.y + barH),
                    strokeWidth = strokeW,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = warnColor,
                    start = Offset(center.x + barGap, center.y - barH),
                    end = Offset(center.x + barGap, center.y + barH),
                    strokeWidth = strokeW,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
private fun CardStepRow(step: AgentCardStep) {
    val sp = LocalAppSpacing.current
    val labelColor = if (step.status == AgentCardStepStatus.Running) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val labelWeight = if (step.status == AgentCardStepStatus.Running) FontWeight.SemiBold else FontWeight.Normal
    val statusLabel = stepStatusLabel(step.status)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(sp.md10),
            modifier = Modifier
                .fillMaxWidth()
                // 8dp 圆角与 shapes.small 同值：改吃 token（视觉零变化，欠债 -1）。
                .clip(MaterialTheme.shapes.small)
                .padding(horizontal = sp.sm, vertical = sp.oxs5)
                .semantics { contentDescription = statusLabel },
        ) {
            StepStatusIcon(step.status)
            Text(
                text = step.label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = labelWeight,
                color = labelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            step.assignee?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.04f), RoundedCornerShape(6.dp))
                        .background(SurfaceTiers.Raised, RoundedCornerShape(6.dp))
                        .padding(horizontal = sp.osm7, vertical = sp.xxs),
                )
            }
        }
        // 「这棒干了什么」的副行（P1）：两行封顶——它是摘要不是日志，日志在 checks 抽屉里。
        step.detail?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 30.dp, end = sp.sm, bottom = sp.xs),
            )
        }
    }
}

/**
 * 原子 4/5 · DiffViewer（§7.3）：路径/摘要一行，+ − 计数靠色。
 *
 * 摘要默认收 [CARD_DIFF_SUMMARY_MAX_LINES] 行；**只有真的可能被截断时才给「展开」**
 * （设计稿 v2 §7 的「明细折叠入口」）。短摘要配一个展开按钮是骗点击 ——
 * 点开以后什么都没变，用户会以为按钮坏了。
 */
