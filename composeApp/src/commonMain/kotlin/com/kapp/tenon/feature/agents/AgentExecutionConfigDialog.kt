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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.domain.DriverAuthCheckUi
import com.kapp.tenon.domain.DriverAuthRemediationUi
import com.kapp.tenon.core.nowMillis
import com.kapp.tenon.domain.DriverAuthStateUi
import com.kapp.tenon.domain.DriverRosterEntryUi
import com.kapp.tenon.domain.ExecutionMatrixRowUi
import com.kapp.tenon.domain.TeamExecutionPort
import com.kapp.tenon.domain.executionMatrixByGroup
import com.kapp.tenon.domain.executionMatrixRows
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.driver_action_check_all
import com.kapp.tenon.generated.resources.driver_action_open_app
import com.kapp.tenon.generated.resources.driver_action_recheck
import com.kapp.tenon.generated.resources.driver_login_hint
import com.kapp.tenon.generated.resources.driver_action_copy_login
import com.kapp.tenon.generated.resources.driver_action_install
import com.kapp.tenon.generated.resources.driver_check_ago
import com.kapp.tenon.generated.resources.driver_check_stale
import com.kapp.tenon.generated.resources.driver_checking
import com.kapp.tenon.generated.resources.driver_state_available
import com.kapp.tenon.generated.resources.driver_state_login
import com.kapp.tenon.generated.resources.driver_state_missing
import com.kapp.tenon.generated.resources.driver_state_model
import com.kapp.tenon.generated.resources.driver_state_unknown
import com.kapp.tenon.generated.resources.exec_config_catalog_hint
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
import com.kapp.tenon.ui.components.LocalDriverRoster
import com.kapp.tenon.ui.components.formatDriverName
import com.kapp.tenon.ui.components.MutedTag
import com.kapp.tenon.ui.components.RelayDialog
import com.kapp.tenon.ui.components.RelayDialogButton
import com.kapp.tenon.ui.components.relativeTime
import com.kapp.tenon.ui.theme.DriverBrand
import com.kapp.tenon.ui.theme.LocalAppSpacing
import com.kapp.tenon.ui.theme.LocalCardShape
import com.kapp.tenon.ui.theme.accents
import com.kapp.tenon.ui.theme.relayCardShadow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * 执行配置（规范 §4.0）：一张「团队 × 员工」的矩阵，回答「每名员工由哪个底座执行、
 * 用哪个模型、这一行生效的是哪份配置」。
 *
 * ## 本机运行时盘面（方案 §3.2，P1b）
 *
 * 七家**全画**（全集 = `driverRoster()` 投影），差集才是「未装」；「全部检测」并发跑
 * 在位各家的只读探针，**先回先渲染**（不做整页 loading、不打乱卡片顺序）。四态 +
 * 一档沉默：可用（绿）/ 需登录（黄，卡上直接给命令原文 + 复制）/ 需选模型（琥珀）/
 * 未装（灰，安装说明）/ **检测不到**（灰字 —— 绝不写成「未登录」）。结果只在内存、
 * 带 `checkedAt` 时间戳（>24h 加「可能已过期」），**不做运行时重扫**：装了新 CLI 要
 * 重开执行配置才出现 —— 这条如实说。
 *
 * ## 为什么这页的矩阵**只读**
 *
 * 写执行档的入口只有一处 —— 群信息 → 团队编制（`TeamRosterDialog`）。在那之外再开
 * 一套下拉，等于把「校验 → 拒绝理由 → 落盘」这三件事写两遍；本仓为「同一规则两份
 * 实现」付过一次代价（两处各写回退链必不一致）。所以这一页只把**生效值**摊开，
 * 并在底部指路。手机端本来就只读（执行档是宿主本地事实），形态因此也统一。
 */
@Composable
internal fun AgentExecutionConfigDialog(
    groups: List<GroupMeta>,
    selfNodeId: String,
    port: TeamExecutionPort,
    /**
     * 检测台账（方案 §3.2）：**由调用屏持有**，不是本对话框的私有状态 ——
     * 「新建员工」表单要读同一份（它按这份画下拉里的可用/需登录）。
     * 两处各持一份的话，用户在执行配置里点了「全部检测」，回到新建表单还是沉默一档。
     */
    checks: Map<String, DriverAuthCheckUi>?,
    onChecks: (Map<String, DriverAuthCheckUi>) -> Unit,
    onDismiss: () -> Unit,
) {
    val sp = LocalAppSpacing.current
    val rows = remember(groups, selfNodeId, port) {
        executionMatrixRows(groups, selfNodeId, port)
    }
    val roster = LocalDriverRoster.current
    val catalog = remember(port) { port.driverCatalog().toList().sorted() }
    val byGroup = remember(rows) { executionMatrixByGroup(rows) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    // 只在「这次检测还在飞」时有意义（防重入）；结果本身住调用方。
    var checking by remember { mutableStateOf(false) }
    val checkRunner: (suspend () -> List<DriverAuthCheckUi>)? = remember(port) { port.driverAuthChecksOrNull() }
    fun runCheck() {
        val runner = checkRunner ?: return
        checking = true
        scope.launch {
            runCatching { runner() }.getOrNull()?.let { list -> onChecks(list.associateBy { it.driverKey }) }
            checking = false
        }
    }

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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(Res.string.exec_config_subtitle_format, catalog.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    // 段头「全部检测」：在位各家并发跑；roster/checker 缺一档都不画（不变量 3）。
                    if (roster != null && checkRunner != null) {
                        TextButton(onClick = { runCheck() }, enabled = !checking) {
                            Text(
                                text = stringResource(
                                    if (checking) Res.string.driver_checking else Res.string.driver_action_check_all,
                                ),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
                DriverCatalogSection(
                    roster = roster,
                    catalog = catalog,
                    checks = checks,
                    checking = checking,
                    onCheck = { runCheck() },
                    onCopy = { text -> clipboard.setText(AnnotatedString(text)) },
                )
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
 * 检测结果的**过期线**（24h）。只影响那句「结果可能已过期」——不隐藏结果本身：
 * 隐藏了用户会以为「没检过」，而事实是「检过、但可能变了」。
 */
private const val CHECKS_STALE_MS: Long = 24L * 60 * 60 * 1000

/** 端口的检测面挂成可空回调（无检测面 = 检测段不画）。 */
private fun TeamExecutionPort.driverAuthChecksOrNull(): (suspend () -> List<DriverAuthCheckUi>)? =
    if (driverRoster() == null) null else {
        { driverAuthChecks().orEmpty() }
    }

/**
 * 本机运行时盘面（方案 §3.2）：七家全画（全集），在位 = 绿点「在位」，未装 = 灰点
 * 「未安装」+ 安装说明。登录态**只在检测后**出现（先回先渲染），四态 + 一档沉默。
 */
@Composable
private fun DriverCatalogSection(
    roster: List<DriverRosterEntryUi>?,
    catalog: List<String>,
    checks: Map<String, DriverAuthCheckUi>?,
    checking: Boolean,
    onCheck: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val sp = LocalAppSpacing.current
    Text(
        text = stringResource(Res.string.exec_config_catalog_title),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = sp.xs),
    )
    // roster == null = 读不到（手机/预览）⇒ 整段不画。**没有「读到了但是空表」这一档**：
    // 名册是七家 builtin 的全集投影，它不可能为空 —— 一台都没装是七张灰卡（未安装），
    // 不是一句「没检测到可用底座」。留一个不可达的空表分支只会让这句话永远显示不出来。
    if (roster == null) return
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = sp.xs),
        verticalArrangement = Arrangement.spacedBy(sp.xs),
    ) {
        roster.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(sp.xs),
            ) {
                pair.forEach { entry ->
                    Box(modifier = Modifier.weight(1f)) {
                        DriverCatalogCard(
                            entry = entry,
                            check = checks?.get(entry.key),
                            checking = checking,
                            onCheck = onCheck,
                            onCopy = onCopy,
                        )
                    }
                }
                if (pair.size == 1) Box(modifier = Modifier.weight(1f))
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

/**
 * 一张底座卡（方案 §3.2 卡内三行封顶）：logo/色块 + 名字 / 状态词 + 一句解法 / 动作行。
 *
 * 状态四档 + 一档沉默，判据逐条对齐 §3.2：
 * - 未装（灰）—— 全集差集；主行动「安装说明」（复制安装指引）；
 * - 需要登录（黄，STRONG）—— 命令原文 + 「复制命令」；
 * - 需要选模型（琥珀）—— zcode 那档，主行动「打开应用」；
 * - 可用（绿）；
 * - **检测不到**（灰字）—— 绝不写成「未登录」（宁可不说，不许猜）；带「检测不到（超时）」
 *   与可再点；有结果才显示「N 前检测」，>24h 加「结果可能已过期」。
 */
@Composable
private fun DriverCatalogCard(
    entry: DriverRosterEntryUi,
    check: DriverAuthCheckUi?,
    checking: Boolean,
    onCheck: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val sp = LocalAppSpacing.current
    val iconBg = AvatarPalette.resolveBg(-1, entry.key)
    val tint = DriverBrand.tintOf(entry.vendor)
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = LocalCardShape.current,
        modifier = Modifier.fillMaxWidth().relayCardShadow(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = sp.sm, vertical = sp.sm6),
            verticalArrangement = Arrangement.spacedBy(sp.xxs),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(sp.sm6),
            ) {
                // vendor 图标位：官方单色资产未入库时按方案「缺失回落」画首字色块
                //（可见但不报错，不手绘近似商标）。tint 同一份（DriverBrand）。
                Box(
                    modifier = Modifier
                        .size(sp.xl)
                        .background(tint.copy(alpha = 0.14f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = entry.shortName.take(1).uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = tint,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.displayName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = entry.vendor,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                if (entry.installed) {
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
                } else {
                    Box(
                        modifier = Modifier
                            .size(sp.sm6)
                            .background(MaterialTheme.colorScheme.outlineVariant, CircleShape),
                    )
                    Text(
                        text = stringResource(Res.string.driver_state_missing),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            // —— 状态行 + 一句解法（只对在位的画登录/模型态；未装的画安装说明）——
            if (!entry.installed) {
                entry.installUrl?.let { url ->
                    RelayDialogButton(
                        text = stringResource(Res.string.driver_action_install),
                        onClick = { onCopy(url) },
                    )
                }
            } else {
                val state = check?.state ?: DriverAuthStateUi.UNKNOWN
                val hasResult = check != null
                val stale = hasResult && check!!.checkedAt > 0 &&
                    (nowMillis() - check.checkedAt) > CHECKS_STALE_MS
                when {
                    !hasResult -> Unit // 沉默一档：没检测过，状态行不画
                    state == DriverAuthStateUi.AUTHENTICATED -> Text(
                        text = stringResource(Res.string.driver_state_available),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    state == DriverAuthStateUi.NOT_AUTHENTICATED &&
                        check.remediation == DriverAuthRemediationUi.TERMINAL_LOGIN -> {
                        Text(
                            text = stringResource(Res.string.driver_state_login),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        Text(
                            text = stringResource(Res.string.driver_login_hint, check.remediationDetail),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        RelayDialogButton(
                            text = stringResource(Res.string.driver_action_copy_login),
                            onClick = { onCopy(check.remediationDetail) },
                        )
                    }
                    state == DriverAuthStateUi.NOT_AUTHENTICATED &&
                        check.remediation == DriverAuthRemediationUi.APP_MODEL -> {
                        Text(
                            text = stringResource(Res.string.driver_state_model),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                        Text(
                            text = stringResource(Res.string.driver_action_open_app),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state == DriverAuthStateUi.NOT_AUTHENTICATED -> Text(
                        text = stringResource(Res.string.driver_state_login),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    else -> Text(
                        // UNKNOWN 一律「检测不到」，绝不写「未登录」，也不写「（超时）」——
                        // 我们手上没有「为什么不知道」，编一个原因就是猜。
                        text = stringResource(Res.string.driver_state_unknown),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (hasResult && check!!.checkedAt > 0) {
                    val rel = relativeTime(check.checkedAt, nowMillis())
                    Text(
                        text = stringResource(Res.string.driver_check_ago, rel) +
                            if (stale) " · " + stringResource(Res.string.driver_check_stale) else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onCheck, enabled = !checking) {
                    Text(
                        text = stringResource(Res.string.driver_checking.takeIf { checking } ?: Res.string.driver_action_recheck),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
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
                    formatDriverName(row.driverKey)
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
