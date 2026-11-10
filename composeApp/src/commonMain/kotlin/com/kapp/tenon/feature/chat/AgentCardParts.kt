// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Surface
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Spacer
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.ic_file
import com.kapp.tenon.generated.resources.ic_terminal
import com.kapp.tenon.generated.resources.ic_check_double
import com.kapp.tenon.generated.resources.ic_refresh
import com.kapp.tenon.generated.resources.ic_close
import com.kapp.tenon.generated.resources.ic_link
import com.kapp.tenon.generated.resources.card_diff_collapse_code
import com.kapp.tenon.generated.resources.card_diff_expand_code
import com.kapp.tenon.generated.resources.card_revision_chip_compat
import com.kapp.tenon.generated.resources.card_revision_chip_contrast
import com.kapp.tenon.generated.resources.card_revision_chip_perf
import com.kapp.tenon.generated.resources.card_revision_chip_test
import com.kapp.tenon.generated.resources.card_revision_hint
import com.kapp.tenon.generated.resources.card_revision_submit
import com.kapp.tenon.generated.resources.card_details_hide
import com.kapp.tenon.generated.resources.card_details_show
import com.kapp.tenon.generated.resources.card_option_recommended
import com.kapp.tenon.generated.resources.card_action_abort
import com.kapp.tenon.generated.resources.card_action_merge_patch
import com.kapp.tenon.generated.resources.card_pending_confirm
import com.kapp.tenon.generated.resources.card_artifact_intent_patch_full
import com.kapp.tenon.generated.resources.card_artifact_intent_prototype
import com.kapp.tenon.generated.resources.card_artifact_intent_run_log
import com.kapp.tenon.generated.resources.card_artifact_intent_test_report
import com.kapp.tenon.ui.components.AppButton
import com.kapp.tenon.ui.components.AppOutlinedButton
import com.kapp.tenon.ui.components.AppButtonSize
import com.kapp.tenon.ui.components.AppButtonTone
import com.kapp.tenon.ui.theme.LocalAppSpacing
import com.kapp.tenon.ui.theme.SurfaceTiers
import com.kapp.tenon.ui.theme.accents
import org.jetbrains.compose.resources.stringResource

/**
 * 卡内的**重区块**：diff 查看器、修订抽屉、产物行、动作按钮组、选项列表。
 *
 * 这些块的共同点是「自带一份小状态与排版」且只在气泡的某个插槽里出现；它们从
 * `AgentCards.kt` 搬出来之后，气泡那条主线的阅读长度回到了「一屏能看完」。
 *
 * ## 可见性
 *
 * 这里 6 个块是 `internal`：它们的唯一调用方是 `AgentCards.kt` 里的 `AgentCardBubble`，
 * 而 Kotlin 的 `private` 只到文件为止。升到 `internal` 是拆文件的代价，不是把接口放大 ——
 * 模块外依然看不见它们。块内部的私有实现（hunk 行、修订 chip、选项圆点…）仍是 `private`。
 */

/**
 * Diff Hunk 代码视窗（升级计划 Phase 2 要点 3，原型 `.df-hunk`）：等宽行号右对齐、
 * 新增绿微底 12%、删除红微底 12%；默认只露 [CARD_HUNK_COLLAPSED_LINES] 行，
 * 超出给「展开/收起」。
 *
 * 行号与极性全部来自 [agentCardHunkLinesOf] 的推演 —— 渲染层不再各写一遍规则。
 */
@Composable
internal fun CardDiffHunkViewer(diff: AgentCardDiff) {
    val sp = LocalAppSpacing.current
    var expanded by remember(diff.hunk) { mutableStateOf(diff.open) }
    val lines = remember(diff.hunk) { agentCardHunkLinesOf(diff.hunk) }
    val expandable = lines.size > CARD_HUNK_COLLAPSED_LINES
    val visible = if (expandable && !expanded) lines.take(CARD_HUNK_COLLAPSED_LINES) else lines
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 原型 .df-hunk 8px 圆角：shapes.small 逐字同值。
            .background(SurfaceTiers.Inset, MaterialTheme.shapes.small)
            .padding(vertical = sp.xs),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        visible.forEach { line ->
            CardHunkLineRow(line)
        }
        if (expandable) {
            // .dftoggle：文字开关（primary 11px 600），不用实心按钮 —— 代码视窗的
            // 主角是代码，展开入口退后一档。
            Text(
                text = stringResource(
                    if (expanded) Res.string.card_diff_collapse_code else Res.string.card_diff_expand_code,
                ),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { expanded = !expanded }
                    .padding(horizontal = sp.sm, vertical = sp.xs),
            )
        }
    }
}

@Composable
private fun CardHunkLineRow(line: AgentCardHunkLine) {
    val sp = LocalAppSpacing.current
    val (bg, fg) = when (line.kind) {
        AgentCardHunkKind.Add ->
            MaterialTheme.accents.online.copy(alpha = 0.12f) to MaterialTheme.accents.online
        AgentCardHunkKind.Del ->
            MaterialTheme.colorScheme.error.copy(alpha = 0.12f) to MaterialTheme.colorScheme.error
        AgentCardHunkKind.HunkHead ->
            Color.Transparent to MaterialTheme.colorScheme.onSurfaceVariant
        AgentCardHunkKind.Context ->
            Color.Transparent to MaterialTheme.colorScheme.onSurface
    }
    val gutterSepColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = sp.sm),
    ) {
        Text(
            text = line.number?.toString() ?: "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
            // gutter 24px（原型 .ln）：两枚在档间距拼出（md10 + lg14），右侧绘制 1px 发丝隔断线
            modifier = Modifier
                .width(sp.md10 + sp.lg14)
                .drawBehind {
                    drawLine(
                        color = gutterSepColor,
                        start = Offset(size.width, 0f),
                        end = Offset(size.width, size.height),
                        strokeWidth = 1f,
                    )
                }
                .padding(end = sp.sm6),
        )
        Text(
            text = line.text,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .weight(1f)
                .padding(start = sp.sm),
        )
    }
}

/**
 * 就地打回反馈抽屉（升级计划 Phase 2 要点 4，原型 `.rev-drawer`）：点「打回」就地
 * 展开，4 枚高频芯片 + 输入框 + 提交。
 *
 * 芯片是**快捷短语**不是互斥选项：点一下把短语追加进输入框（可继续编辑、可多选），
 * 与原型 `.qchip` 的行为一致。提交走 [rejectRevisionActionIdOf] 把反馈编码进
 * actionId（动作通路只有一个字符串槽，见那个函数的 KDoc），乐观置灰与防重
 * 沿用 [cardActionUiOf] 的三态 —— 打回也是一次决策，不该享受豁免。
 *
 * [expanded] 由调用方持有：抽屉的开合跟随按钮的锁态，本组件不自持。
 */
@Composable
internal fun CardRevisionDrawer(
    card: AgentCardUi,
    pendingActions: Set<String>,
    actionsEnabled: Boolean,
    onAction: (card: AgentCardUi, actionId: String) -> Unit,
) {
    val sp = LocalAppSpacing.current
    var feedback by remember(card.cardId) { mutableStateOf("") }
    val ui = cardActionUiOf(card, pendingActions, SYSTEM_ACTION_REJECT_REVISION)
    val enabled = actionsEnabled && !ui.disabled
    val submitted = ui.lock != CardActionLock.Editable
    Column(
        verticalArrangement = Arrangement.spacedBy(sp.sm),
        modifier = Modifier
            .fillMaxWidth()
            // 原型 .rev-drawer 10px 圆角 + 红 18% 细边：圆角就近归并 shapes.small。
            .background(SurfaceTiers.Raised, MaterialTheme.shapes.small)
            .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.18f), MaterialTheme.shapes.small)
            .padding(horizontal = sp.md10, vertical = sp.md10),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(sp.sm6),
            modifier = Modifier.fillMaxWidth(),
        ) {
            RevisionChip(Res.string.card_revision_chip_test, enabled) { feedback = feedback.appendChip(it) }
            RevisionChip(Res.string.card_revision_chip_contrast, enabled) { feedback = feedback.appendChip(it) }
            RevisionChip(Res.string.card_revision_chip_compat, enabled) { feedback = feedback.appendChip(it) }
            RevisionChip(Res.string.card_revision_chip_perf, enabled) { feedback = feedback.appendChip(it) }
        }
        // 输入框：无边框药丸（.rev-inp 的 surface 底 + 细边），与 MessageComposer 的
        // 「输入框自带容器」分工一致 —— 这里画的是抽屉自己的行内输入。
        androidx.compose.foundation.text.BasicTextField(
            value = feedback,
            onValueChange = { feedback = it },
            enabled = enabled,
            textStyle = MaterialTheme.typography.bodySmall.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            decorationBox = { inner ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small)
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f), MaterialTheme.shapes.small)
                        .padding(horizontal = sp.md10, vertical = sp.sm6),
                ) {
                    if (feedback.isEmpty()) {
                        Text(
                            text = stringResource(Res.string.card_revision_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        AppButton(
            text = stringResource(
                if (submitted) Res.string.card_pending_confirm else Res.string.card_revision_submit,
            ),
            onClick = {
                onAction(card, rejectRevisionActionIdOf(feedback))
            },
            enabled = enabled,
            size = AppButtonSize.Small,
            tone = AppButtonTone.Danger,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 芯片追加的接缝：第一条前不留；接缝用 ASCII 而不是任何一语的标点（双语共用一份）。 */
private fun String.appendChip(chip: String): String =
    if (isBlank()) chip else "$this + $chip"

@Composable
private fun RevisionChip(labelRes: StringResource, enabled: Boolean, onClick: (label: String) -> Unit) {
    val sp = LocalAppSpacing.current
    val label = stringResource(labelRes)
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.extraSmall)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.08f), MaterialTheme.shapes.extraSmall)
            .clickable(enabled = enabled) { onClick(label) }
            .padding(horizontal = sp.sm, vertical = sp.xxs),
    )
}

/**
 * 原子 4/5 · DiffViewer（§7.3）：升级稿「现代代码视窗小卡」（relay.html:557-563）——
 * surface-2 底 + 5% 黑细边 + 圆角 10 + mono；.dfhead 两端分推路径与 .diff-stat，
 * note 11px on-2。摘要收 2 行，>48 字符才给「展开」（短摘要配展开是骗点击）。
 */
@Composable
internal fun CardDiffViewerRow(diff: AgentCardDiff) {
    val sp = LocalAppSpacing.current
    var expanded by remember(diff.summary) { mutableStateOf(false) }
    val summary = diff.summary
    val expandable = summary != null && summary.length > CARD_DIFF_SUMMARY_EXPAND_OVER
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
            .background(SurfaceTiers.Raised, RoundedCornerShape(10.dp))
            .padding(horizontal = sp.md, vertical = sp.md10),
        verticalArrangement = Arrangement.spacedBy(sp.sm6),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(sp.sm6),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            androidx.compose.material3.Icon(
                painter = painterResource(Res.drawable.ic_terminal),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(com.kapp.tenon.ui.theme.IconSizes.Inline),
            )
            if (diff.path != null) {
                Text(
                    text = diff.path,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (diff.additions != null || diff.deletions != null) {
                // **`+N / −N` 是纯彩色等宽文字，不是药丸**。
                //
                // 原型 `.acard .df .diff-stat{display:inline-flex;gap:4px;font-size:11px}` +
                // `.add{color:var(--online);font-weight:600}` / `.del{color:var(--error);font-weight:600}`
                // （prototype/relay.html:561-562）：只有颜色与字重，没有底色、没有圆角、没有内边距。
                // 代码这里曾经自己加了一层「淡色药丸」（0.12 透明底色 + 4dp 圆角 + 5/1dp 内边距），
                // 于是逐屏取值差集在 5 屏（m-histdate / d-chat / d-histsearch / d-groupcreate /
                // 及其共享切片）同时长出「只代码 4」的圆角与「只代码 1」的间距 —— 那不是原型有的档，
                // 是这一处自造的。字重也照原型收成 600（原来是 700）。
                Row(horizontalArrangement = Arrangement.spacedBy(sp.xs)) {
                    Text(
                        text = "+${diff.additions ?: 0}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.accents.online,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        text = "−${diff.deletions ?: 0}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
        if (summary != null) {
            // 只放摘要文本（宿主侧本就只给「N 个文件」）：`+N −N` 已由上面头部行的
            // 彩色等宽文字承担（D-NEW-3）—— `card_diff_summary` 格式串曾把计数再追加
            // 一遍，同一张卡上出现两组增删数，用户会当成两个不同的统计。
            Text(
                text = summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else CARD_DIFF_SUMMARY_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (expandable) {
            AppButton(
                text = stringResource(
                    if (expanded) Res.string.card_details_hide else Res.string.card_details_show,
                ),
                onClick = { expanded = !expanded },
                size = AppButtonSize.Small,
                tone = AppButtonTone.Neutral,
            )
        }
    }
}

/**
 * 原子 6/6 · ArtifactList：升级稿「文件小胶囊」（relay.html:565-572）—— surface-2 底
 * + 圆角 10 + 细边框 + 28×28 图标座（agent 紫 10% 底、ic_file 16px）+ nm 13/600 + mt mono。
 *
 * 自描述契约的落点（升级计划 §7.4）：条目可带**意图行**（稳定键 → 本地词条）与
 * **预览入口**。入口只画「真的能打开」的：渲染种类可渲染 + 发出方声明 delivered +
 * 消息流里找到同 id 关联的已落盘文件（savedPathFor）—— 三个条件缺一个就
 * 退回纯元数据行，暂态（未投递）如实给一句解释而不是一颗死按钮。
 *
 * 两条通路在**这里一次性分流**（`Kind.opensExternally`）：
 * - Markdown / Diff / 文本 → 本组件弹 [ArtifactPreviewDialog]（端内渲染）；
 * - 网页原型（Html）→ 调 [onPreviewArtifact] 把落盘路径交给壳的平台出口
 *   （Android `ACTION_VIEW` + FileProvider / 桌面 `Desktop.open` / iOS Quick Look）。
 *   端内没有浏览器引擎，按文本渲染源码等于给用户看一堆标签，所以不做。
 *
 * onPreviewArtifact 是可空槽：null = 这个壳没有「交给系统打开」的通路（网页原型
 * 就不画入口），与动作槽的同名纪律一致 —— 按下去没反应的按钮比没有更糟。
 */
@Composable
internal fun CardArtifactRows(
    artifacts: List<AgentCardArtifact>,
    savedPathFor: (AgentCardArtifact) -> String?,
    onPreviewArtifact: ((AgentCardArtifact, String) -> Unit)?,
) {
    val sp = LocalAppSpacing.current
    Column(verticalArrangement = Arrangement.spacedBy(sp.sm)) {
        artifacts.forEach { artifact ->
            val kind = ArtifactPreview.kindOf(artifact.name, artifact.mime)
            val savedPath = savedPathFor(artifact)
            // 种类可渲染 + 已投递 + 已落盘；外部打开的那一类还要求壳真有平台出口。
            val previewable = ArtifactPreview.canPreview(artifact, kind, savedPath) &&
                (!kind.opensExternally || onPreviewArtifact != null)
            val blockedReason = ArtifactPreview.blockedReasonOf(artifact, kind, savedPath)
            var previewOpen by remember(artifact.name) { mutableStateOf(false) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(sp.md10),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
                    .background(SurfaceTiers.Raised, RoundedCornerShape(10.dp))
                    .then(
                        if (previewable) {
                            Modifier.clickable {
                                if (kind.opensExternally) {
                                    // 外部打开：路径由 canPreview 保证非空，这里只做一次窄化。
                                    savedPath?.let { onPreviewArtifact?.invoke(artifact, it) }
                                } else {
                                    previewOpen = true
                                }
                            }
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = sp.md, vertical = sp.md10),
            ) {
                // .art .art-ic：28×28 图标座（agent 紫 10% 底 + primary 图标）。
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(MaterialTheme.accents.agentAccent.copy(alpha = 0.1f), RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_file),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(com.kapp.tenon.ui.theme.IconSizes.Label),
                    )
                }
                Column(Modifier.weight(1f)) {
                    // .art .nm：13/600 on 单行省略（relay.html:570-571）。
                    Text(
                        text = artifact.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (previewable) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = CARD_ARTIFACT_NAME_MAX_LINES,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // .art .mt：11px on-2 mono（meta 与名字两行 —— 新稿布局）。
                    artifactMetaLine(artifact)?.let { meta ->
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                        )
                    }
                    // 意图行（§7.4 人类可读意图）：键在词条里查，查不到不画 ——
                    // 编一个通用词等于给不知道是什么的文件编故事。
                    artifact.intent?.let { key ->
                        artifactIntentLabel(key)?.let { label ->
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    // 暂态解释：文件还没投到本机时，一句「为什么看不了」胜过沉默。
                    if (!previewable && blockedReason != null) {
                        ArtifactPreviewBlockedReason(blockedReason)
                    }
                }
            }
            if (previewOpen && savedPath != null) {
                ArtifactPreviewDialog(
                    artifact = artifact,
                    savedPath = savedPath,
                    onDismiss = { previewOpen = false },
                )
            }
        }
    }
}

/**
 * 意图键 → 本地词条（双语进 strings，键跨语言稳定）。未登记的键返回 null：
 * 发出方给了一个本端不认识的键，不画比猜一个更诚实。
 */
@Composable
private fun artifactIntentLabel(intentKey: String): String? = when (intentKey) {
    "patch-full" -> stringResource(Res.string.card_artifact_intent_patch_full)
    "test-report" -> stringResource(Res.string.card_artifact_intent_test_report)
    "run-log" -> stringResource(Res.string.card_artifact_intent_run_log)
    "prototype" -> stringResource(Res.string.card_artifact_intent_prototype)
    else -> null
}

/**
 * 原子 5/5 · ButtonGroup：升级稿「按钮与说明融为一体」（relay.html:574-579）——
 * .acts 竖排：.pri-row（btn sm 等分 r9 7×12）+ .sub-row 居中次级文字按钮（11 on-2、
 * 无底无边框）。本仓动作模型暂无 subActions 数据键（载荷 Schema 无此键，
 * detailed-design §2.1）—— sub-row 的样式接缝先不落（数据到不了的字段不画）,
 * 只落 pri-row 的等分布列。审批防重三态判据不变。
 *
 * [isActionVisible] 是**本地动作的就地判据**（null = 全部画）：像「在浏览器打开原型」
 * 这种由客户端自己兑现的动作，文件还没落到本机时画出来就是一颗死按钮 ——
 * 由调用方按「这份产物此刻能不能打开」把整颗按钮摘掉，而不是画一颗灰的。
 */
@Composable
internal fun CardButtonGroupRow(
    card: AgentCardUi,
    pendingActions: Set<String>,
    actionsEnabled: Boolean,
    onAction: (card: AgentCardUi, actionId: String) -> Unit,
    isActionVisible: ((AgentCardAction) -> Boolean)? = null,
) {
    val sp = LocalAppSpacing.current
    Column(verticalArrangement = Arrangement.spacedBy(sp.sm)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(sp.md10),
            modifier = Modifier.fillMaxWidth(),
        ) {
            card.actions.filter { isActionVisible?.invoke(it) ?: true }.forEach { action ->
                val ui = cardActionUiOf(card, pendingActions, action.actionId)
                // 文案优先序（跨端约定）：本端认识的 labelKey → 已知系统动作的端上词条 →
                // payload 的 label → actionId。旧 payload（只有 label）与旧端（只读 label）
                // 都被这三条兜住，永远有字。
                val keyedLabel = localizedCardActionLabel(action)
                val text = when {
                    ui.lock == CardActionLock.PendingConfirm -> stringResource(Res.string.card_pending_confirm)
                    keyedLabel != null -> keyedLabel
                    action.actionId == SYSTEM_ACTION_MERGE_PATCH -> stringResource(Res.string.card_action_merge_patch)
                    action.actionId == SYSTEM_ACTION_ABORT_TASK -> stringResource(Res.string.card_action_abort)
                    else -> cardActionLabel(action, null)
                }

                val isDanger = action.style == "danger" ||
                    action.actionId == SYSTEM_ACTION_ABORT_TASK ||
                    action.actionId == SYSTEM_ACTION_REJECT_REVISION ||
                    action.actionId.startsWith("$SYSTEM_ACTION_REJECT_REVISION|")

                val iconRes = when {
                    action.actionId == SYSTEM_ACTION_MERGE_PATCH -> Res.drawable.ic_check_double
                    action.actionId == SYSTEM_ACTION_ABORT_TASK -> Res.drawable.ic_close
                    action.actionId == SYSTEM_ACTION_REJECT_REVISION ||
                        action.actionId.startsWith("$SYSTEM_ACTION_REJECT_REVISION|") -> Res.drawable.ic_close
                    action.actionId == "approve-prototype" -> Res.drawable.ic_check_double
                    action.actionId == SYSTEM_ACTION_OPEN_PROTOTYPE -> Res.drawable.ic_link
                    action.actionId == "retry" -> Res.drawable.ic_refresh
                    else -> null
                }
                if (action.approval) {
                    AppButton(
                        text = text,
                        onClick = { onAction(card, action.actionId) },
                        enabled = actionsEnabled && !ui.disabled,
                        size = AppButtonSize.Medium,
                        tone = if (isDanger) AppButtonTone.Danger else AppButtonTone.Primary,
                        leadingIcon = iconRes,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    AppOutlinedButton(
                        text = text,
                        onClick = { onAction(card, action.actionId) },
                        enabled = actionsEnabled && !ui.disabled,
                        size = AppButtonSize.Medium,
                        tone = if (isDanger) AppButtonTone.Danger else AppButtonTone.Neutral,
                        leadingIcon = iconRes,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * 预设选项块（升级稿 ask_question，detailed-design §7.9）→ **单选行**，不是按钮。
 *
 * 为什么不再画成按钮（真机真实用户反馈）：原型待选卡曾经是「推荐项 = 实心主按钮
 * + 另两项描边按钮 + Approve & Develop 实心主按钮」，四个同一种视觉语言 ——
 * 用户看到的是四个平级按钮，真相却是「三选一 + 一个确认」。
 * 选型是**选择**不是**动作**：radio 行表达「从这几个里挑一个」，推荐项用角标说
 * 「推荐」而不是用实心填充抢「主动作」的地位；实心主按钮只留给卡上唯一的确认
 * 动作（如 Approve & Develop）。
 * 动作路由不变：actionId = 选项文本（§7.9 第 3 条「获得确定性参数后再开工」）。
 * 已判定/已提交的选择行照实亮起（与按钮组同一套 [cardActionUiOf] 锁态）。
 * 无动作通路时整块不画（与 ButtonGroup 同一条 DeadButtonGuard 纪律）。
 */
@Composable
internal fun CardOptionListRows(
    card: AgentCardUi,
    pendingActions: Set<String>,
    actionsEnabled: Boolean,
    onAction: ((card: AgentCardUi, actionId: String) -> Unit)?,
) {
    if (onAction == null) return  // 没有动作通路 = 点了没反应的选项比没有更糟
    val sp = LocalAppSpacing.current
    Column(verticalArrangement = Arrangement.spacedBy(sp.sm)) {
        card.options.forEach { option ->
            val ui = cardActionUiOf(card, pendingActions, option.label)
            val checked = ui.lock != CardActionLock.Editable
            val shape = MaterialTheme.shapes.small
            val borderStroke = BorderStroke(
                1.dp,
                if (checked) MaterialTheme.accents.agentAccent.copy(alpha = 0.45f)
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.16f),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(sp.md10),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(
                        if (checked) MaterialTheme.accents.agentAccent.copy(alpha = 0.10f)
                        else SurfaceTiers.Raised,
                        shape,
                    )
                    .border(borderStroke, shape)
                    .clickable(enabled = actionsEnabled && !ui.disabled) {
                        onAction(card, option.label)
                    }
                    .padding(horizontal = sp.lg14, vertical = sp.md),
            ) {
                OptionRadioDot(checked = checked)
                Text(
                    text = option.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (option.recommended && !checked) {
                    Text(
                        text = stringResource(Res.string.card_option_recommended),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.accents.agentAccentText,
                        modifier = Modifier
                            .background(
                                MaterialTheme.accents.agentAccent.copy(alpha = 0.12f),
                                RoundedCornerShape(sp.sm6),
                            )
                            .padding(horizontal = sp.sm, vertical = sp.xxs),
                    )
                }
            }
        }
    }
}

/** 单选行的 radio 圆点：Body 外圈，选中时 sm 实芯，颜色随选中态走 agentAccent。 */
@Composable
private fun OptionRadioDot(checked: Boolean) {
    val sp = LocalAppSpacing.current
    val ringColor =
        if (checked) MaterialTheme.accents.agentAccent
        else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    val radioBorder = BorderStroke(sp.xxs, ringColor)
    Box(
        modifier = Modifier
            .size(com.kapp.tenon.ui.theme.IconSizes.Body)
            .border(radioBorder, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Box(
                modifier = Modifier
                    .size(sp.sm)
                    .background(MaterialTheme.accents.agentAccent, CircleShape),
            )
        }
    }
}

/**
 * 原型稿 `.mchips`（relay.html:541-546）：卡片头行下方的**状态胶囊行** ——
 * 11px 小胶囊、6dp 圆角、surface-2 底，tone 上色（pri=agent 淡紫加粗 / ok=绿 /
 * warn=琥珀 / err=红），与 bannerColorsOf 同一条色板公式（agentAccent/online/warn/error）。
 * 宿主只发**机器事实**（进度/门禁/受阻），措辞在宿主侧（agentNode 文案口径）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CardChipsRow(chips: List<AgentCardChip>) {
    val sp = LocalAppSpacing.current
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(sp.sm6),
        modifier = Modifier.padding(vertical = sp.xxs),
    ) {
        chips.forEach { chip ->
            val (bg, fg) = chipColorsOf(chip.tone)
            Text(
                text = chip.text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (chip.tone == "pri") FontWeight.SemiBold else FontWeight.Medium,
                color = fg,
                modifier = Modifier
                    .background(bg, RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun chipColorsOf(tone: String?): Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color> =
    when (tone) {
        "pri" -> MaterialTheme.accents.agentAccent.copy(alpha = 0.08f) to MaterialTheme.accents.agentAccentText
        "ok" -> MaterialTheme.accents.online.copy(alpha = 0.1f) to MaterialTheme.accents.online
        "warn" -> MaterialTheme.accents.warn.copy(alpha = 0.1f) to MaterialTheme.colorScheme.onSecondaryContainer
        "err" -> MaterialTheme.colorScheme.error.copy(alpha = 0.1f) to MaterialTheme.colorScheme.error
        else -> SurfaceTiers.Raised to MaterialTheme.colorScheme.onSurfaceVariant
    }

/**
 * 原型稿 `.ctx-box`（relay.html:548-550）：业务上下文与执行意图说明框 ——
 * surface-2 底 + 8dp 圆角 + 3dp 主色左侧坚条 + 11px bodySmall 文本，
 * 支持简易粗体 (<b>) 与代码标签 (<code>)。
 */
@Composable
internal fun CardContextBox(
    context: String,
    modifier: Modifier = Modifier,
) {
    val sp = LocalAppSpacing.current
    Surface(
        color = SurfaceTiers.Raised,
        shape = MaterialTheme.shapes.small,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = sp.md, vertical = sp.sm),
            horizontalArrangement = Arrangement.spacedBy(sp.sm),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .width(sp.oxs3)
                    .heightIn(min = sp.lg)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
            Text(
                text = formatContextAnnotated(context),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 16.sp,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 原型稿 `.cmd-box`（relay.html:551-552）：高危或执行命令展示框 ——
 * surface-3 底 + 8dp 圆角 + 等宽字体 + 11px labelSmall + 横向可滚。
 */
@Composable
internal fun CardCmdBox(
    cmd: String,
    modifier: Modifier = Modifier,
) {
    val sp = LocalAppSpacing.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.small,
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = sp.md, vertical = sp.sm),
        ) {
            Text(
                text = cmd,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

internal fun formatContextAnnotated(raw: String): AnnotatedString {
    val clean = raw.replace("<br/>", "\n").replace("<br>", "\n")
    val builder = AnnotatedString.Builder()
    var i = 0
    while (i < clean.length) {
        if (clean.startsWith("<b>", i)) {
            val end = clean.indexOf("</b>", i + 3)
            if (end != -1) {
                builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                builder.append(clean.substring(i + 3, end))
                builder.pop()
                i = end + 4
                continue
            }
        }
        if (clean.startsWith("<code>", i)) {
            val end = clean.indexOf("</code>", i + 6)
            if (end != -1) {
                builder.pushStyle(SpanStyle(fontFamily = FontFamily.Monospace))
                builder.append(clean.substring(i + 6, end))
                builder.pop()
                i = end + 7
                continue
            }
        }
        builder.append(clean[i])
        i++
    }
    return builder.toAnnotatedString()
}

