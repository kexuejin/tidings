// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.domain.ExecutionMatrixRowUi
import com.kapp.tenon.domain.TeamExecutionPort
import com.kapp.tenon.domain.executionMatrixByGroup
import com.kapp.tenon.domain.executionMatrixRows
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.exec_config_catalog_hint
import com.kapp.tenon.generated.resources.exec_config_catalog_none
import com.kapp.tenon.generated.resources.exec_config_catalog_title
import com.kapp.tenon.generated.resources.exec_config_driver_unassigned
import com.kapp.tenon.generated.resources.exec_config_edit_hint
import com.kapp.tenon.generated.resources.exec_config_empty
import com.kapp.tenon.generated.resources.exec_config_installed
import com.kapp.tenon.generated.resources.exec_config_member_unnamed
import com.kapp.tenon.generated.resources.exec_config_model_format
import com.kapp.tenon.generated.resources.exec_config_model_inherit
import com.kapp.tenon.generated.resources.exec_config_not_installed
import com.kapp.tenon.generated.resources.exec_config_overridden
import com.kapp.tenon.generated.resources.exec_config_readonly_no_dir
import com.kapp.tenon.generated.resources.exec_config_subtitle_format
import com.kapp.tenon.generated.resources.exec_config_title
import com.kapp.tenon.ui.components.AvatarPalette
import com.kapp.tenon.ui.components.MutedTag
import com.kapp.tenon.ui.components.RelayDialog
import com.kapp.tenon.ui.components.formatDriverName
import com.kapp.tenon.ui.theme.LocalAppSpacing
import com.kapp.tenon.ui.theme.LocalCardShape
import com.kapp.tenon.ui.theme.accents
import com.kapp.tenon.ui.theme.relayCardShadow
import org.jetbrains.compose.resources.stringResource

/**
 * 执行配置（规范 §4.0）：一张「团队 × 员工」的矩阵，回答「每名员工由哪个底座执行、
 * 用哪个模型、这一行生效的是哪份配置」。
 *
 * ## 为什么这页**只读**
 *
 * 写执行档的入口只有一处 —— 群信息 → 团队编制（`TeamRosterDialog`）。在那之外再开
 * 一套下拉，等于把「校验 → 拒绝理由 → 落盘」这三件事写两遍；本仓为「同一规则两份
 * 实现」付过一次代价（两处各写回退链必不一致）。所以这一页只把**生效值**摊开，
 * 并在底部指路。手机端本来就只读（执行档是宿主本地事实），形态因此也统一。
 *
 * ## 为什么没有「支持模型 ✓/✗」列
 *
 * 那份词表只有 `DriverModelFlags` 一份，它在 `:agent-contract`，而这一层（composeApp）
 * 看不到它。在这里抄一份必然漂 —— 缺省由宿主侧保存校验拒绝并给原因，这一页不猜。
 */
@Composable
internal fun AgentExecutionConfigDialog(
    groups: List<GroupMeta>,
    selfNodeId: String,
    port: TeamExecutionPort,
    onDismiss: () -> Unit,
) {
    val sp = LocalAppSpacing.current
    val rows = remember(groups, selfNodeId, port) {
        executionMatrixRows(groups, selfNodeId, port)
    }
                val catalog = remember(port) { port.driverCatalog().toList().sorted() }
                val byGroup = remember(rows) { executionMatrixByGroup(rows) }

    RelayDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.exec_config_title)) },
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(sp.sm),
            ) {
                Text(
                    text = stringResource(Res.string.exec_config_subtitle_format, catalog.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 本机底座清单（仿 multica Runtimes）：只答「装没装」，不摆登录态表。
                // 登录是各家 CLI 自己的事，失败时才在会话里内联提示——这座页不预检、不代替。
                // 手机壳 driverCatalog() 为空 ⇒ 整段不画，与桌面执行面区分。
                DriverCatalogSection(catalog = catalog)
                if (rows.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.exec_config_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    byGroup.forEach { (_, groupRows) ->
                        Text(
                            text = groupRows.first().groupName,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = sp.xs),
                        )
                        groupRows.forEach { row -> ExecutionRow(row = row) }
                    }
                }
                Text(
                    text = stringResource(Res.string.exec_config_edit_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = sp.sm),
                )
            }
        },
    )
}

/**
 * 本机底座清单（仿 multica 的 Runtimes 卡）：**只回答"装没装"**，每家一张卡。
 *
 * 为什么不画登录态表：multica 的 daemon 注册 runtime 只探"二进制在不在 + 版本够不够"，
 * 从不检查各家 CLI 的账号登录；登录是 CLI 自己的事，失败时在会话里内联提示即可。
 * 这张页照同一口径 —— 卡上是图标 + 友好名 + "在位"圆点，没有登录状态、没有补凭证命令。
 * 之前那版 7 行"未判定 + 终端: xxx login"是本项目自造的形态，multica 源码里并不存在。
 *
 * 图标：本项目没有任何 CLI 厂商 logo（62 个 drawable 全是通用线条图标），凭空画假 logo
 * 只会画错商标。复用 [AvatarPalette] 的按名稳定配色 + 友好名首字做识别色块，
 * 每家一个固定色，这正是"按 id 稳定配色"的既有用途，不引入新色表、不写字面量。
 *
 * 空清单分两态：手机壳没有执行面 ⇒ [catalog] 为空 ⇒ 画"不适用"；桌面没检测到任何底座也
 * 是空，但那是事实上的"没装"。两者文案分开，不与"全部在位"混同（守空集假绿判据）。
 */
@Composable
private fun DriverCatalogSection(catalog: List<String>) {
    val sp = LocalAppSpacing.current
    Text(
        text = stringResource(Res.string.exec_config_catalog_title),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = sp.xs),
    )
    if (catalog.isEmpty()) {
        Text(
            text = stringResource(Res.string.exec_config_catalog_none),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    // 两张一组切分：Row 的 weight 是最稳的等宽语义，卡片宽度随父容器伸缩，不写死 dp。
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = sp.xs),
        verticalArrangement = Arrangement.spacedBy(sp.xs),
    ) {
        catalog.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(sp.xs),
            ) {
                pair.forEach { key ->
                    Box(modifier = Modifier.weight(1f)) {
                        DriverCatalogCard(driverKey = key)
                    }
                }
            }
        }
    }
    Text(
        text = stringResource(Res.string.exec_config_catalog_hint),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = sp.xs),
    )
}

/** 一张底座卡：稳定配色首字图标 + 友好名 + 绿色在位圆点。 */
@Composable
private fun DriverCatalogCard(driverKey: String) {
    val sp = LocalAppSpacing.current
    val name = formatDriverName(driverKey)
    val iconBg = AvatarPalette.resolveBg(-1, driverKey)
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = LocalCardShape.current,
        modifier = Modifier.fillMaxWidth().relayCardShadow(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = sp.sm, vertical = sp.sm6),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(sp.sm6),
        ) {
            Box(
                modifier = Modifier
                    .size(sp.xl)
                    .background(iconBg, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = name.take(1).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = AvatarPalette.foregroundOn(iconBg),
                )
            }
            Text(
                text = name,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .size(sp.sm6)
                    .background(MaterialTheme.accents.online, CircleShape),
            )
            Text(
                text = stringResource(Res.string.exec_config_installed),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * 一行：`名字 · 角色` + 底座 + 模型 + 在位 + 覆盖/继承。
 *
 * 「未指派」与「不在位」是两件事，所以是两个标签：前者是**没配**（合法一等状态，
 * 不猜默认值），后者是**配了但这台机器现在没有**（要用户去装或改）。
 */
@Composable
private fun ExecutionRow(row: ExecutionMatrixRowUi) {
    val sp = LocalAppSpacing.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = sp.xxs),
        verticalArrangement = Arrangement.spacedBy(sp.xxs),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(sp.sm6),
        ) {
            Text(
                // 名字是**本团队内**的 @ token；没写名字时显角色占位，不拿 agentId 顶替。
                text = row.memberName.ifBlank { stringResource(Res.string.exec_config_member_unnamed) },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            row.role?.takeIf { it.isNotBlank() }?.let { role ->
                Text(
                    text = role,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (row.overridden) {
                // 中性徽标，不是「待批」红药丸：覆盖与否是配置事实，不是告警。
                MutedTag(text = stringResource(Res.string.exec_config_overridden))
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(sp.sm6),
        ) {
            MutedTag(
                text = if (row.driverKey.isBlank()) {
                    stringResource(Res.string.exec_config_driver_unassigned)
                } else {
                    row.driverKey
                },
            )
            Text(
                text = row.model?.let { stringResource(Res.string.exec_config_model_format, it) }
                    ?: stringResource(Res.string.exec_config_model_inherit),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (row.driverKey.isNotBlank()) {
                Text(
                    text = if (row.installed) {
                        stringResource(Res.string.exec_config_installed)
                    } else {
                        stringResource(Res.string.exec_config_not_installed)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (row.installed) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    maxLines = 1,
                )
            }
        }
        if (!row.editable) {
            Text(
                text = stringResource(Res.string.exec_config_readonly_no_dir),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
