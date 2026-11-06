// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kapp.tenon.core.nowMillis
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.tool_approvals_agent
import com.kapp.tenon.generated.resources.tool_approvals_all_agents
import com.kapp.tenon.generated.resources.tool_approvals_desc
import com.kapp.tenon.generated.resources.tool_approvals_empty
import com.kapp.tenon.generated.resources.tool_approvals_gone
import com.kapp.tenon.generated.resources.tool_approvals_revoke
import com.kapp.tenon.generated.resources.tool_approvals_revoked
import com.kapp.tenon.generated.resources.tool_approvals_row_meta
import com.kapp.tenon.generated.resources.tool_approvals_title
import com.kapp.tenon.generated.resources.tool_approvals_unavailable
import com.kapp.tenon.ui.components.AppButtonSize
import com.kapp.tenon.ui.components.AppButtonTone
import com.kapp.tenon.ui.components.AppOutlinedButton
import com.kapp.tenon.ui.components.relativeTime
import com.kapp.tenon.ui.theme.LocalAppSpacing
import org.jetbrains.compose.resources.stringResource

/**
 * 面板上的一行免批规则 —— 与宿主免批表的一行同形，只去掉 `grantedBy`。
 *
 * 为什么不让 UI 直接吃宿主的 `Rule`：`grantedBy` 是排查用的标记（谁批的），界面上
 * 没有位置也没有意义（列表本来就是「本机这一侧的授权」）。留一个界面自己的形状，
 * 也让「撤销哪一件事」在单测里可以说清楚 —— 撤销靠的是这三个字段的**逐字相等**。
 */
data class ToolApprovalRow(
    val nodeId: String,
    val agentKey: String?,
    val tool: String,
    val grantedAt: Long,
)

/**
 * 免批表的**读与撤**（界面那一侧的窄接口）—— ARP-13② 的适配器缝。
 *
 * 宿主免批表（`AgentToolApprovalStore`，:agentNode，JVM-only）长在跑 agent 的那台
 * 机器上；这个接口把它收窄成 UI 需要的三件事：读行、逐条撤、外部写入时通知刷新。
 * 桌面宿主用一个薄适配器实现它（`DesktopToolApprovalsSection.kt`），于是：
 * - 这份 UI 进了 composeApp commonMain ⇒ android 截图门**够得着**（ARP-13② 判据）；
 * - 撤销纪律（走 `revokeExactly` 而不是按 `(nodeId, agentKey, tool)` 盲撤）随纯逻辑
 *   [ToolApprovalAdmin] 一起搬进 commonMain，两端共享同一条规则。
 */
interface ToolApprovalsSource {
    /** 当前规则行的快照（读侧）。 */
    fun snapshotRows(): List<ToolApprovalRow>

    /**
     * 撤掉**这一条**（nodeId + agentKey + tool 逐字匹配），返回真正删掉的条数
     * （0 = 它已经不在了）。不许有「按 nodeId 全撤」的语义 —— 界面上那一行说的是一条。
     */
    fun revokeExactly(nodeId: String, agentKey: String?, tool: String): Int

    /** 外部写入通知（对端按「始终允许」时控制面线程写表），返回注销函数。 */
    fun addChangeListener(listener: () -> Unit): () -> Unit
}

/**
 * 免批表的读与撤（界面那一侧的纯逻辑）。
 *
 * 单测只需要它：`rows()` 读出来的顺序、`revoke()` 到底删了几条、撤销后同一工具是不是
 * 重新开始问 —— 这三件事都能在不渲染 Compose 的前提下钉住。
 */
class ToolApprovalAdmin(private val source: ToolApprovalsSource) {

    fun rows(): List<ToolApprovalRow> = source.snapshotRows()

    /**
     * 撤销界面上这一条，返回真正删掉的条数（0 = 它已经不在了）。
     *
     * 走 [ToolApprovalsSource.revokeExactly]（宿主侧落到 `AgentToolApprovalStore
     * .revokeExactly`）而不是 `revoke(nodeId, agentKey, tool)`：后者的 `agentKey = null`
     * 意思是「不按 agent 过滤」，会把同设备同工具的**其它 agent 规则**一起删掉 ——
     * 而界面上那一行说的是「这一条」。
     */
    fun revoke(row: ToolApprovalRow): Int =
        source.revokeExactly(nodeId = row.nodeId, agentKey = row.agentKey, tool = row.tool)
}

/**
 * 「免批规则」区块：**用户按下「始终允许」之后，在这里收回。**
 *
 * ## 它补的是哪一段
 *
 * 免批表（`AgentToolApprovalStore`）此前只有写入口：审批行上按下「始终允许」，
 * 宿主落一条规则，从此这个工具不再询问 —— 而界面上**既没有列表也没有撤销口**，
 * 想收回只能手动删 `agent-tool-approvals.json` 整张表（连别的工具的规则一起没了）。
 * 一份自己看不见、也收不回的授权，用户没法对自己的机器负责。
 *
 * ## 为什么经 [ToolApprovalsSource] 这个缝，而不直接吃 store
 *
 * 表在**跑 agent 的那台机器**上（:agentNode，JVM-only）；手机发出的「始终允许」只是
 * 把决定送给宿主，规则本身不存在手机上。UI 住 commonMain（ARP-13②：android 截图门
 * 要够得着它），所以 store 经桌面侧薄适配器接进来 —— 帧用假 source 就能录，
 * 不需要 JVM 的文件表。
 *
 * ## 撤销的语义边界（界面上如实写出来）
 *
 * 撤销只影响**之后的**请求：正在等批准的那一条仍在等；已经执行完的当然不会回滚。
 * 所以回执说的是「下一次同工具请求会重新询问」，而不是「已撤销该操作」。
 */
@Composable
fun ToolApprovalsSection(
    source: ToolApprovalsSource?,
    /** 设备名（对端昵称，取不到时由调用方给一个可读的替代）。 */
    peerLabel: (String) -> String,
    modifier: Modifier = Modifier,
    /**
     * 「现在」：只用于 `relativeTime` 的年龄文案。默认读挂钟；截图帧必须传固定值 ——
     * 同一帧两次渲染要逐像素相同，挂钟进来今天绿、明天就红（见 PreviewClock）。
     */
    now: Long = nowMillis(),
) {
    val sp = LocalAppSpacing.current
    var epoch by remember { mutableIntStateOf(0) }
    // 上一次撤销的回执：删了几条 / 那条已经不在了。只活在这一屏（不落盘），
    // 它说的是「我刚按的那一下」。
    var lastRevoked by remember { mutableStateOf<Int?>(null) }

    // **外部写入也要刷新**：规则是对端按「始终允许」时由控制面线程写进来的，
    // 不是用户在这一页上点的。没有这一条，设置页开着也看不到刚产生的规则。
    DisposableEffect(source) {
        val off = source?.addChangeListener { epoch++ }
        onDispose { off?.invoke() }
    }

    val admin = remember(source) { source?.let { ToolApprovalAdmin(it) } }
    val rows = remember(admin, epoch) { admin?.rows().orEmpty() }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(sp.lg),
            verticalArrangement = Arrangement.spacedBy(sp.md),
        ) {
            Text(
                text = stringResource(Res.string.tool_approvals_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(Res.string.tool_approvals_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            lastRevoked?.let { removed ->
                // 两档分开说：删掉了 / 已经不在了。合并成「操作成功」会让第二种情况
                // （对端刚撤过、或文件被换掉）看起来像"我撤掉了什么"，其实什么都没撤。
                Text(
                    text = if (removed > 0) {
                        stringResource(Res.string.tool_approvals_revoked, removed)
                    } else {
                        stringResource(Res.string.tool_approvals_gone)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (removed > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when {
                // 控制面没挂上：**不许画成空表**。空表说的是"一条规则都没有"，
                // 而真实情况是"读不到"—— 用户会据此以为免批已经被清空。
                source == null -> Text(
                    text = stringResource(Res.string.tool_approvals_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                rows.isEmpty() -> Text(
                    text = stringResource(Res.string.tool_approvals_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> rows.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = sp.xs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(sp.md),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            // 工具名等宽：`rm` 与 `rmm` 在比例字体里读起来一样，
                            // 而这一行说的正是"哪个名字以后不问"。
                            Text(
                                text = row.tool,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                            )
                            val agentLabel = row.agentKey?.let {
                                stringResource(Res.string.tool_approvals_agent, it)
                            } ?: stringResource(Res.string.tool_approvals_all_agents)
                            Text(
                                text = stringResource(
                                    Res.string.tool_approvals_row_meta,
                                    peerLabel(row.nodeId),
                                    agentLabel,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = relativeTime(row.grantedAt, now),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        AppOutlinedButton(
                            text = stringResource(Res.string.tool_approvals_revoke),
                            size = AppButtonSize.Small,
                            tone = AppButtonTone.Danger,
                            onClick = {
                                lastRevoked = admin?.revoke(row)
                                // 不依赖监听回调刷新：撤销是本页发起的，这一下必须立刻可见
                                // （监听回调在锁外同步触发，但界面状态更新不该排在它后面）。
                                epoch++
                            },
                        )
                    }
                }
            }
        }
    }
}
