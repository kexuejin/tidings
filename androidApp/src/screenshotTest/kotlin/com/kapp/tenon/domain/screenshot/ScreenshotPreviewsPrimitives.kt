// SPDX-License-Identifier: GPL-3.0-only

// 设计系统**原语族**自身的门禁帧（ARP-12）。
//
// ## 为什么这十个帧必须存在
//
// `design/design-spec.json` 里有五个规格的 `previews` 长期是空数组：SectionCard /
// CommonWidgets / PkPalette / AvatarPalette / PressableSurface。它们不是没用到的组件
// —— SectionCard 是设置页每张卡的骨架，PeerAvatar 是全仓每一处头像，PressableSurface
// 是「可按压表面」这一整族唯一实现。但**没有任何一帧是为它们录的**：它们只在"被某块屏
// 用到"的时候顺带出现在别人的帧里，于是
//
//   · 组合里没被屏用到的那几档（SectionCard 带 trailing、StatusDot 的离线档、
//     PressableSurface 的 transparent 档……）从来没被人看过一眼；
//   · 一旦改坏，是**别人的帧**先变红，排障从「哪个原语」退化成「哪块屏」。
//
// 规格里写着 `previews: []` 时，上面这两件事在台账上都是隐形的 —— 空数组看起来像
// "不需要帧"，而不是"没人录过"。这十个帧就是把那条空数组填成事实。
//
// ## 名称前缀 `Primitives`
//
// 这些帧**不渲染任何屏**（它们是原语目录页，不是 settings/chat 的某一态）。按屏归属
// 只会得到五条假缺口，所以 `Primitives` 进 `stateSlots.unattributablePrefixes`，
// 与 `Components` / `Adaptive` 同一档处理。
//
// ## 手机帧与桌面帧差在哪
//
// 不是把同一张图缩一缩：桌面帧按 **1100dp** 渲染（`DesktopLayout` 的 Expanded 档），
// 宽度一变，`InfoRow` 的 1 : 2.5 标签/值比例、`SectionCard` 的卡宽、`PkCardView` 的
// `widthIn(min = 160dp)` 后面还剩多少空间都会变 —— 这些正是只有宽屏才看得见的形态。
package com.kapp.tenon.domain.screenshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.kapp.tenon.domain.BannerMessage
import com.kapp.tenon.domain.BannerTone
import com.kapp.tenon.domain.BannerUi
import com.kapp.tenon.domain.model.PkCardUi
import com.kapp.tenon.ui.SectionCard
import com.kapp.tenon.ui.components.AppBanner
import com.kapp.tenon.ui.components.AvatarPicker
import com.kapp.tenon.ui.components.DiceTile
import com.kapp.tenon.ui.components.InfoRow
import com.kapp.tenon.ui.components.KindBadge
import com.kapp.tenon.ui.components.LabeledField
import com.kapp.tenon.ui.components.NavCountBadge
import com.kapp.tenon.ui.components.PeerAvatar
import com.kapp.tenon.ui.components.PkCardView
import com.kapp.tenon.ui.components.PressableSurface
import com.kapp.tenon.ui.components.RpsTile
import com.kapp.tenon.ui.components.SectionHeader
import com.kapp.tenon.ui.components.StatusDot
import com.kapp.tenon.feature.settings.ToolApprovalRow
import com.kapp.tenon.feature.settings.ToolApprovalsSection
import com.kapp.tenon.feature.settings.ToolApprovalsSource
import com.kapp.tenon.ui.theme.AppTheme
import com.kapp.tenon.ui.theme.LocalAppSpacing

// —— 帧内所有文案都是**固定的假数据**：帧要逐像素可复现，任何随环境变的东西都不许进来 ——

private const val SECTION_TITLE = "身份与连接"
private const val SECTION_TRAILING = "对端可见"
private const val LONG_SECTION_TITLE = "能力声明与传输通道（这一行故意写得很长，用来看卡头怎么折行）"
private const val LONG_INFO_LABEL = "状态栏 inset"
private const val LONG_INFO_VALUE = "C:/Users/developer/AppData/Roaming/Tenon/data/relay-2026-11-04.log"

/** SectionCard：卡头 + 内容；带/不带 trailing、超长标题三态。 */
@PreviewTest
@Preview(name = "primitives-section-card-phone", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun PrimitivesSectionCardPhonePreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().padding(LocalAppSpacing.current.lg),
                verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md),
            ) {
                SectionCard(title = SECTION_TITLE, trailing = { TrailingHint() }) {
                    Column {
                        InfoRow(label = "昵称", value = "茶博士")
                        InfoRow(label = "节点 ID", value = "9f3a2c1d")
                    }
                }
                SectionCard(title = "通知") {
                    Column { InfoRow(label = "新消息提醒", value = "开") }
                }
                // 超长卡头：卡头必须自己折行，不能把 trailing 顶出卡外。
                SectionCard(title = LONG_SECTION_TITLE, trailing = { TrailingHint() }) {
                    Column { InfoRow(label = "能力", value = "tw1 · mrec1") }
                }
            }
        }
    }
}

/** 桌面档：两列并排（1100dp 下卡宽约 500dp），卡头与 trailing 的间距是宽屏才看得见的形态。 */
@PreviewTest
@Preview(name = "primitives-section-card-desktop", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun PrimitivesSectionCardDesktopPreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Row(
                modifier = Modifier.fillMaxSize().padding(LocalAppSpacing.current.xl),
                horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md),
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md),
                ) {
                    SectionCard(title = SECTION_TITLE, trailing = { TrailingHint() }) {
                        Column {
                            InfoRow(label = "昵称", value = "茶博士")
                            InfoRow(label = "节点 ID", value = "9f3a2c1d")
                        }
                    }
                    SectionCard(title = "通知") {
                        Column { InfoRow(label = "新消息提醒", value = "开") }
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    SectionCard(title = LONG_SECTION_TITLE, trailing = { TrailingHint() }) {
                        Column {
                            InfoRow(label = LONG_INFO_LABEL, value = LONG_INFO_VALUE)
                            InfoRow(label = "能力", value = "tw1 · mrec1")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrailingHint() {
    Text(
        text = SECTION_TRAILING,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** CommonWidgets：在场点 / 头像四态 / 徽标 / 分组标题 / 键值行 / 计数 / 横幅 / 输入框。 */
@PreviewTest
@Preview(name = "primitives-common-phone", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun PrimitivesCommonPhonePreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().padding(LocalAppSpacing.current.lg),
                verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm),
            ) {
                // 在场点：在线 / 离线 / 自定义色（同步条那种"广播中"）。
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm),
                ) {
                    Text("StatusDot", style = MaterialTheme.typography.labelSmall)
                    StatusDot(online = true)
                    StatusDot(online = false)
                    StatusDot(online = true, color = MaterialTheme.colorScheme.tertiary)
                }
                // 头像四态：首字 / 动物 / Agent / 群。形状按上下文变是内容的一部分。
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm),
                ) {
                    PeerAvatar(name = "茶博士")
                    PeerAvatar(name = "阿茶", avatar = 4)
                    PeerAvatar(name = "Codex", isAgent = true)
                    PeerAvatar(name = "周末茶会", isGroup = true)
                    PeerAvatar(name = "圆角方块档", shape = RoundedCornerShape(12.dp))
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm),
                ) {
                    KindBadge(isAgent = true)
                    KindBadge(isAgent = false)
                    NavCountBadge(count = 3)
                    NavCountBadge(count = 128)
                }
                SectionHeader(text = "分组标题")
                SectionHeader(text = "带动作的分组标题", action = { KindBadge(isAgent = false) })
                InfoRow(label = "端口", value = "17878 / 17879")
                // 长值档：标签不许被挤成一字一行（`settings-more-long-text` 那次事故）。
                InfoRow(label = LONG_INFO_LABEL, value = LONG_INFO_VALUE)
                AppBanner(
                    banner = BannerUi(message = BannerMessage.BindFailed(17878, "address already in use"), tone = BannerTone.Error),
                    onDismiss = {},
                )
                AppBanner(
                    banner = BannerUi(message = BannerMessage.Handshaking("zhou-pc"), tone = BannerTone.Info),
                    onDismiss = {},
                )
                val nick = remember { mutableStateOf("茶博士") }
                LabeledField(label = "昵称", value = nick.value, onValueChange = { nick.value = it })
            }
        }
    }
}

/** 桌面档：左列原语、右列键值行 + 横幅 —— 同一批组件在 1100dp 下的实际比例。 */
@PreviewTest
@Preview(name = "primitives-common-desktop", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun PrimitivesCommonDesktopPreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Row(
                modifier = Modifier.fillMaxSize().padding(LocalAppSpacing.current.xl),
                horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.lg),
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm),
                    ) {
                        StatusDot(online = true)
                        StatusDot(online = false)
                        KindBadge(isAgent = true)
                        KindBadge(isAgent = false)
                        NavCountBadge(count = 128)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm)) {
                        PeerAvatar(name = "茶博士")
                        PeerAvatar(name = "阿茶", avatar = 4)
                        PeerAvatar(name = "Codex", isAgent = true)
                        PeerAvatar(name = "周末茶会", isGroup = true)
                    }
                    SectionHeader(text = "分组标题")
                    SectionHeader(text = "带动作的分组标题", action = { KindBadge(isAgent = false) })
                }
                Column(
                    modifier = Modifier.weight(1.4f),
                    verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm),
                ) {
                    InfoRow(label = "端口", value = "17878 / 17879")
                    InfoRow(label = LONG_INFO_LABEL, value = LONG_INFO_VALUE)
                    AppBanner(
                        banner = BannerUi(message = BannerMessage.SendFailed("no ack within 10s"), tone = BannerTone.Error),
                        onDismiss = {},
                    )
                    val nick = remember { mutableStateOf("茶博士") }
                    LabeledField(label = "昵称", value = nick.value, onValueChange = { nick.value = it })
                }
            }
        }
    }
}

/** PkPalette：骰子 1~6、猜拳三手、两种 PK 卡。 */
@PreviewTest
@Preview(name = "primitives-pk-phone", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun PrimitivesPkPhonePreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().padding(LocalAppSpacing.current.lg),
                verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm),
            ) {
                Text("DiceTile 1–6", style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm)) {
                    (1..6).forEach { dots -> DiceTile(dots = dots) }
                }
                Text("RpsTile 石头/剪刀/布", style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm)) {
                    RpsTile(value = "rock")
                    RpsTile(value = "scissors")
                    RpsTile(value = "paper")
                }
                Text("PkCardView", style = MaterialTheme.typography.labelSmall)
                PkCardView(pk = PkCardUi(game = "dice", value = "5"))
                PkCardView(pk = PkCardUi(game = "rps", value = "rock"))
            }
        }
    }
}

@PreviewTest
@Preview(name = "primitives-pk-desktop", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun PrimitivesPkDesktopPreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().padding(LocalAppSpacing.current.xl),
                verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm)) {
                    (1..6).forEach { dots -> DiceTile(dots = dots) }
                    RpsTile(value = "rock")
                    RpsTile(value = "scissors")
                    RpsTile(value = "paper")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md)) {
                    PkCardView(pk = PkCardUi(game = "dice", value = "5"))
                    PkCardView(pk = PkCardUi(game = "rps", value = "scissors"))
                }
            }
        }
    }
}

/** AvatarPalette：两档头像形态 + 底色/动物选择器（选择器只在设置页出现，帧里必须见到）。 */
@PreviewTest
@Preview(name = "primitives-avatar-phone", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun PrimitivesAvatarPhonePreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().padding(LocalAppSpacing.current.lg),
                verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm),
            ) {
                Text("PeerAvatar：首字 / 动物 / Agent / 群", style = MaterialTheme.typography.labelSmall)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm),
                ) {
                    PeerAvatar(name = "茶博士")
                    PeerAvatar(name = "阿茶", avatar = 4)
                    PeerAvatar(name = "阿茶二号", avatar = 4)
                    PeerAvatar(name = "Codex", isAgent = true)
                    PeerAvatar(name = "周末茶会", isGroup = true)
                }
                Text("AvatarPicker（图标档）", style = MaterialTheme.typography.labelSmall)
                AvatarPicker(avatar = 24, nick = "茶博士", onAvatarChange = {})
            }
        }
    }
}

/** 桌面档：选择器在 1100dp 下的网格列数会变多，这是它唯一会出问题的地方。 */
@PreviewTest
@Preview(name = "primitives-avatar-desktop", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun PrimitivesAvatarDesktopPreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Row(
                modifier = Modifier.fillMaxSize().padding(LocalAppSpacing.current.xl),
                horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.lg),
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm),
                    ) {
                        PeerAvatar(name = "茶博士")
                        PeerAvatar(name = "阿茶", avatar = 4)
                        PeerAvatar(name = "Codex", isAgent = true)
                        PeerAvatar(name = "周末茶会", isGroup = true)
                        PeerAvatar(name = "圆角方块", shape = RoundedCornerShape(12.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm)) {
                        (0..5).forEach { idx -> PeerAvatar(name = "第 $idx 位", avatar = idx) }
                    }
                }
                Column(modifier = Modifier.weight(1.2f)) {
                    AvatarPicker(avatar = 24, nick = "茶博士", onAvatarChange = {})
                }
            }
        }
    }
}

/** PressableSurface：带底色 / 透明 / 禁用 / 自定义形状四档（它自己没有视觉断言，只能看图）。 */
@PreviewTest
@Preview(name = "primitives-pressable-phone", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun PrimitivesPressablePhonePreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().padding(LocalAppSpacing.current.lg),
                verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm),
            ) {
                PressableRow(label = "带底色（surfaceVariant）", color = MaterialTheme.colorScheme.surfaceVariant)
                PressableRow(label = "透明档：底色由内容自己带", color = Color.Unspecified)
                PressableRow(label = "禁用档（圆点变灰、不可点）", color = MaterialTheme.colorScheme.surfaceVariant, enabled = false)
                PressableRow(
                    label = "自定义形状：小圆角",
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(4.dp),
                )
            }
        }
    }
}

@PreviewTest
@Preview(name = "primitives-pressable-desktop", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun PrimitivesPressableDesktopPreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().padding(LocalAppSpacing.current.xl),
                verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md)) {
                    PressableRow(
                        label = "带底色",
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    PressableRow(
                        label = "透明档",
                        color = Color.Unspecified,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md)) {
                    PressableRow(
                        label = "禁用档",
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        enabled = false,
                        modifier = Modifier.weight(1f),
                    )
                    PressableRow(
                        label = "自定义形状",
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun PressableRow(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.medium,
) {
    PressableSurface(
        onClick = {},
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        color = color,
        shape = shape,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = LocalAppSpacing.current.md, vertical = LocalAppSpacing.current.md10),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm),
        ) {
            StatusDot(online = enabled)
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

// —— ToolApprovalsSection（ARP-13②）：免批规则区块经 ToolApprovalsSource 缝入帧 ——
//
// 缝在这里的价值：帧用假 source 就能录，不需要 :agentNode 的 JVM 文件表 ——
// 「section UI 移入 composeApp 并有帧」这条判据正是靠这个接口成立的。

private const val TOOL_APPROVALS_NODE_1 = "n-zhou0000000003"
private const val TOOL_APPROVALS_NODE_2 = "n-li00000000004"

/** 帧用的假免批表：固定行 + 撤销只动内存副本，绝不读挂钟或真文件。 */
internal class FakeToolApprovalsSource(private val rows: MutableList<ToolApprovalRow>) : ToolApprovalsSource {
    override fun snapshotRows(): List<ToolApprovalRow> = rows.sortedByDescending { it.grantedAt }
    override fun revokeExactly(nodeId: String, agentKey: String?, wsId: String?, tool: String): Int {
        // wsId 只命中 wsId（null 只命中 null）：F3 的更严档，预览假件与真表同一判据。
        val idx = rows.indexOfFirst {
            it.nodeId == nodeId && it.agentKey == agentKey && it.wsId == wsId && it.tool == tool
        }
        return if (idx >= 0) { rows.removeAt(idx); 1 } else 0
    }
    override fun addChangeListener(listener: () -> Unit): () -> Unit = { }
}

internal fun fakeToolApprovalsSource(): ToolApprovalsSource = FakeToolApprovalsSource(
    mutableListOf(
        ToolApprovalRow(nodeId = TOOL_APPROVALS_NODE_1, agentKey = "dsh-cli", wsId = null, tool = "bash", grantedAt = at(-25)),
        ToolApprovalRow(nodeId = TOOL_APPROVALS_NODE_2, agentKey = null, wsId = null, tool = "git", grantedAt = at(-5)),
    ),
)

private fun previewToolApprovalsPeerLabel(nodeId: String): String =
    if (nodeId == TOOL_APPROVALS_NODE_1) "老周的茶桌" else "茶几-04"

/** 免批规则区块：两行规则（含「所有 agent」通配档）+ 设备别名；时钟固定（PreviewClock）。 */
@PreviewTest
@Preview(name = "primitives-tool-approvals-phone", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun PrimitivesToolApprovalsPhonePreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            ToolApprovalsSection(
                source = fakeToolApprovalsSource(),
                peerLabel = ::previewToolApprovalsPeerLabel,
                now = PREVIEW_NOW,
                modifier = Modifier.fillMaxWidth().padding(LocalAppSpacing.current.lg),
            )
        }
    }
}

/** 桌面帧（1100dp）：看宽屏下「设备名 · agent 档」的副行与撤销按钮的排布。 */
@PreviewTest
@Preview(name = "primitives-tool-approvals-desktop", widthDp = 1100, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun PrimitivesToolApprovalsDesktopPreview() {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            ToolApprovalsSection(
                source = fakeToolApprovalsSource(),
                peerLabel = ::previewToolApprovalsPeerLabel,
                now = PREVIEW_NOW,
                modifier = Modifier.fillMaxWidth().padding(LocalAppSpacing.current.lg),
            )
        }
    }
}
