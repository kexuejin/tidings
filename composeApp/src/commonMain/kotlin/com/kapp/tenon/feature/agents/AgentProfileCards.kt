// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.agents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kapp.tenon.core.protocol.AVATAR_LEGACY_INITIAL_VALUE
import com.kapp.tenon.domain.AgentEmployeeCandidateUi
import com.kapp.tenon.domain.AgentEmployeeProfileUi
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.agent_count_items
import com.kapp.tenon.generated.resources.agent_profile_empty
import com.kapp.tenon.generated.resources.agent_profile_forget
import com.kapp.tenon.generated.resources.agent_profile_forget_body
import com.kapp.tenon.generated.resources.agent_profile_forget_hint
import com.kapp.tenon.generated.resources.agent_profile_hint
import com.kapp.tenon.generated.resources.agent_profile_meta_alone_format
import com.kapp.tenon.generated.resources.agent_profile_meta_groups_format
import com.kapp.tenon.generated.resources.agent_profile_new
import com.kapp.tenon.generated.resources.agent_profile_new_empty
import com.kapp.tenon.generated.resources.agent_profile_new_hint
import com.kapp.tenon.generated.resources.agent_profile_new_meta_format
import com.kapp.tenon.generated.resources.agent_profile_section_title
import com.kapp.tenon.generated.resources.common_cancel
import com.kapp.tenon.generated.resources.ic_trash
import com.kapp.tenon.ui.components.AppButtonSize
import com.kapp.tenon.ui.components.AppIconButton
import com.kapp.tenon.ui.components.AppTextButton
import com.kapp.tenon.ui.components.PeerAvatar
import com.kapp.tenon.ui.components.RelayDialog
import com.kapp.tenon.ui.components.RelayDialogButton
import com.kapp.tenon.ui.components.RelayDialogContentHeight
import com.kapp.tenon.ui.components.SectionHeader
import com.kapp.tenon.ui.components.formatCount
import com.kapp.tenon.ui.theme.LocalAppSpacing
import org.jetbrains.compose.resources.stringResource

/**
 * 员工**档案**段（登记簿视角）：智能体页的第三档。
 *
 * ## 为什么它是独立一档，而不是名录下面再挂一段
 *
 * 名录与团队是**同一批声明的两个方向**（员工看「在哪些团队」、团队看「这队有谁」），
 * 所以它们用视图切换。档案是第三种问题：**谁登记过**。它不依赖任何运行时事实 ——
 * 一个人可以先建档、一个群都不进；也可以席位全撤、agent 下线之后档案还在。
 * 把这种「只在设置里存在」的数据混进名录，每一行都得解释「这一行为什么没有状态」。
 *
 * ## 为什么行可点（编辑）、尾格是垃圾桶（注销）
 *
 * 这一档存在的直接原因，是注销此前**没有任何界面入口**（`Forget` 命令早就有了）。
 * 所以注销必须是行上可见的一颗，而不是藏进二级页。编辑是它的日常动作，走整行点击 ——
 * 行尾只留一颗，与团队名录的行尾动作纪律同一条（两颗图标会把名字挤成省略号）。
 *
 * ## 为什么「新建档案」也在这一档
 *
 * 建档（`Create`）此前只挂在**员工详情页**上，而详情页要先点一张卡才进得去：一个人都还没
 * 登记过、或者某个 agent 还没进任何群时，第一份档案其实建不出来（没有卡可点）。
 * 登记簿自己就是「谁登记过」的管理面，新建是它该有的那颗动词 —— 所以这一档的可见性
 * 也从「有档案」改成「有档案 **或** 能建档」。
 *
 * ## 手机端整段不出现
 *
 * `employeeProfiles` 只住宿主机本机（不进协议帧），手机读到的恒为空列表 ⇒
 * 调用方连这一档的切换器都不画。与团队档案同一条纪律，不是特例。
 */
@Composable
internal fun AgentProfileSection(
    profiles: List<AgentEmployeeProfileUi>,
    /** 点行 = 编辑这份档案；null = 这个壳没有档案写通路 ⇒ 行不可点。 */
    onEdit: ((AgentEmployeeProfileUi) -> Unit)? = null,
    /** 行尾垃圾桶 = 注销档案；null = 没有写通路 ⇒ 不画（不画点了没反应的删除键）。 */
    onForget: ((AgentEmployeeProfileUi) -> Unit)? = null,
    /**
     * 「新建档案」入口：给一个**还没进任何群**的本机 agent 建档。
     *
     * null = 这个壳没有档案写通路（手机 / 预览）⇒ 整颗按钮不画。非 null 时本段**空表也成立**
     * —— 「一个档案都没有」正是最需要这个入口的时候（此前它只挂在员工详情页上，而详情页
     * 要先有一张卡才进得去）。
     */
    onCreate: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    /**
     * 画不画本段自己的段头。首屏目录把标题与计数提到切换器那一行（标题跟档位走），
     * 这时再画一遍就是同一屏两个标题；独立使用时（没有切换器）仍由本段自己画。
     */
    showSectionHeader: Boolean = true,
) {
    val sp = LocalAppSpacing.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(sp.md)) {
        if (showSectionHeader) {
            SectionHeader(
                text = stringResource(Res.string.agent_profile_section_title),
                action = {
                    Text(
                        text = stringResource(
                            Res.string.agent_count_items,
                            formatCount(profiles.size.toLong()),
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }
        // 入口放在列表**之上**：档案是「先建人、再进群」，新建是这个页面唯一的动词；
        // 沉到列表底下就要先滚过所有已登记的人。样式与团队段的「新建团队」同一颗
        // （`AppTextButton` + Small），两档的入口不该长得不一样。
        if (onCreate != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(sp.sm6),
            ) {
                AppTextButton(
                    text = stringResource(Res.string.agent_profile_new),
                    onClick = onCreate,
                    size = AppButtonSize.Small,
                )
            }
        }
        if (profiles.isEmpty()) {
            // 空态给的是**出路**，而且这句话两端都能读：能建档时它说的是上面那颗按钮，
            // 读不到写通路时（手机）它只是如实说「还没有人登记」—— 一句文案不承诺这里没有的能力。
            Text(
                text = stringResource(Res.string.agent_profile_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        Text(
            text = stringResource(Res.string.agent_profile_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AgentRoster(count = profiles.size) { index ->
            val profile = profiles[index]
            AgentProfileRow(
                profile = profile,
                onOpen = onEdit?.let { edit -> { edit(profile) } },
                onForget = onForget?.let { forget -> { forget(profile) } },
            )
        }
    }
}

/**
 * 档案行：首字头像 + 员工名 + 「@token · 职责 · N 个群」。
 *
 * 与名录行同一套语言（同一个 [PeerAvatar] 首字模式、同样的两行），差别只在第三格：
 * 名录那里是在线态 / 待批 / 最近活动，这里恒为 `@` token 与所在群数 —— 档案是
 * **登记事实**，它没有在线态可报（也不该拿员工的在线态顶替：档案与那个进程是两件事）。
 */
@Composable
private fun AgentProfileRow(
    profile: AgentEmployeeProfileUi,
    onOpen: (() -> Unit)?,
    onForget: (() -> Unit)?,
) {
    val sp = LocalAppSpacing.current
    var confirming by remember { mutableStateOf(false) }
    // 两套格式而不是拼句子：「不在任何群」是一种**状态**，不是「0 个群」这种读数 ——
    // 后者会让用户以为群计数出了错。
    val meta = if (profile.groupNames.isEmpty()) {
        stringResource(Res.string.agent_profile_meta_alone_format, profile.token, profile.duty)
    } else {
        stringResource(
            Res.string.agent_profile_meta_groups_format,
            profile.token,
            profile.duty,
            profile.groupNames.size,
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
            .padding(horizontal = sp.lg, vertical = sp.md10),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(sp.md),
    ) {
        PeerAvatar(
            name = profile.name,
            avatar = AVATAR_LEGACY_INITIAL_VALUE,
            size = sp.xxl,
            // 团队行同一个圆角档位（`medium`）：`small` 会让屏幕差分多出一档 radius。
            shape = MaterialTheme.shapes.medium,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(sp.xxs),
        ) {
            Text(
                text = profile.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onForget != null) {
            AppIconButton(
                icon = Res.drawable.ic_trash,
                contentDescription = stringResource(Res.string.agent_profile_forget),
                onClick = { confirming = true },
                modifier = Modifier.size(sp.xxl),
            )
        }
    }
    if (confirming) {
        EmployeeForgetDialog(
            name = profile.name,
            groupCount = profile.groupNames.size,
            onDismiss = { confirming = false },
            onConfirm = {
                confirming = false
                onForget?.invoke()
            },
        )
    }
}

/**
 * 「新建档案」的第一步：**选谁**。
 *
 * ## 为什么建档要一个选择器，而登记不需要
 *
 * 详情页的「登记为员工」是从一张**已有的卡**上点进去的 —— 身份键就在手里，多一步选择只是
 * 多一次点击。档案页相反：这里没有卡，用户手里只有「我想给某个人起个名字」这个意图，
 * 而档案**必须**挂在一个真实存在的本机 agent 上（空表单会产出派不出去的幽灵，见
 * `EmployeeProfile` 的 KDoc）。所以这一步问的是「哪个人」，不是「填什么」。
 *
 * 选完就交给同一个表单（[EmployeeEditDialog]）—— 本对话框不收集名字/职责，两份表单必然漂。
 *
 * @param candidates [employeeProfileCandidates] 的结果：本机、还没有档案、有身份键。
 *   空表 = 这台机器上没有可登记的对象，如实说，不给一颗假按钮。
 */
@Composable
internal fun EmployeeProfileCreateDialog(
    candidates: List<AgentEmployeeCandidateUi>,
    onPick: (AgentEmployeeCandidateUi) -> Unit,
    onDismiss: () -> Unit,
) {
    val sp = LocalAppSpacing.current
    RelayDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.agent_profile_new)) },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(sp.sm)) {
                Text(
                    text = stringResource(Res.string.agent_profile_new_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (candidates.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.agent_profile_new_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    // 与「添加成员」同一个高度收口（比例上限 + 列表自己滚），
                    // 候选多时不会把对话框顶出屏幕。
                    RelayDialogContentHeight(defaultMaxHeight = 320.dp) { cap ->
                        LazyColumn(Modifier.heightIn(max = cap)) {
                            items(candidates, key = { it.agentId }) { candidate ->
                                CandidateRow(candidate = candidate, onClick = { onPick(candidate) })
                            }
                        }
                    }
                }
            }
        },
        dismissButton = {
            RelayDialogButton(
                text = stringResource(Res.string.common_cancel),
                onClick = onDismiss,
            )
        },
    )
}

/** 候选行：首字头像 + 现有显示名 + 身份键（副行）。与档案行同一套排版语言。 */
@Composable
private fun CandidateRow(candidate: AgentEmployeeCandidateUi, onClick: () -> Unit) {
    val sp = LocalAppSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = sp.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(sp.sm),
    ) {
        PeerAvatar(
            name = candidate.name,
            avatar = AVATAR_LEGACY_INITIAL_VALUE,
            size = sp.xxl,
            shape = MaterialTheme.shapes.medium,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(sp.xxs)) {
            Text(
                text = candidate.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 副行写**身份键**：显示名可能只是一个 `@` token，用户靠它认不出这是哪个进程。
            Text(
                text = candidate.hostName
                    ?.let { stringResource(Res.string.agent_profile_new_meta_format, it, candidate.agentId) }
                    ?: candidate.agentId,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 注销员工档案的二次确认。
 *
 * 二次确认的理由与解散团队相同但不完全一样：这里**不会**把任何人踢出群（席位不动），
 * 所以真正不可逆的只有「名字与 `@` 默认 token 的登记」。用户最容易误判的是后果范围，
 * 因此正文分两段：删的是什么、什么不会变。
 */
@Composable
internal fun EmployeeForgetDialog(
    name: String,
    groupCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val sp = LocalAppSpacing.current
    RelayDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.agent_profile_forget)) },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(sp.sm)) {
                Text(
                    text = stringResource(Res.string.agent_profile_forget_body, name),
                    style = MaterialTheme.typography.bodyMedium,
                )
                // 有群时点名数量：用户点的是「删档案」，得当场知道有几个群的席位会被摘引用。
                if (groupCount > 0) {
                    Text(
                        text = stringResource(Res.string.agent_profile_forget_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            RelayDialogButton(
                text = stringResource(Res.string.agent_profile_forget),
                danger = true,
                onClick = onConfirm,
            )
        },
        dismissButton = {
            RelayDialogButton(
                text = stringResource(Res.string.common_cancel),
                onClick = onDismiss,
            )
        },
    )
}
