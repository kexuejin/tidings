// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.cabinet

import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kapp.tenon.core.nowMillis
import com.kapp.tenon.ui.theme.IconSizes
import com.kapp.tenon.ui.theme.LocalAppSpacing
import com.kapp.tenon.domain.TransferSlice
import com.kapp.tenon.domain.NodeSlice
import com.kapp.tenon.domain.SettingsSlice
import com.kapp.tenon.domain.ChromeSlice
import com.kapp.tenon.domain.humanPeers
import com.kapp.tenon.domain.peer
import com.kapp.tenon.ui.ContentWidths
import com.kapp.tenon.ui.centerCapped
import com.kapp.tenon.ui.components.AppButton
import com.kapp.tenon.ui.components.AppBanner
import com.kapp.tenon.ui.components.AppButtonSize
import com.kapp.tenon.ui.components.AppButtonTone
import com.kapp.tenon.ui.components.AppIconButton
import com.kapp.tenon.ui.components.AppOutlinedButton
import com.kapp.tenon.ui.components.LargeTitleBar
import com.kapp.tenon.ui.components.TitleBarDivider
import com.kapp.tenon.ui.components.RelayDialog
import com.kapp.tenon.ui.components.RelayDialogButton
import com.kapp.tenon.ui.components.formatBytes
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.desktop_action_list
import com.kapp.tenon.generated.resources.ic_list
import com.kapp.tenon.generated.resources.cabinet_change
import com.kapp.tenon.generated.resources.cabinet_confirm_remove_grant_desc
import com.kapp.tenon.generated.resources.cabinet_confirm_remove_grant_title
import com.kapp.tenon.generated.resources.cabinet_confirm_stop_share_desc
import com.kapp.tenon.generated.resources.cabinet_confirm_stop_share_title
import com.kapp.tenon.generated.resources.cabinet_dir_hint
import com.kapp.tenon.generated.resources.cabinet_grants_count
import com.kapp.tenon.generated.resources.cabinet_grants_default_note
import com.kapp.tenon.generated.resources.cabinet_grants_remove
import com.kapp.tenon.generated.resources.cabinet_grants_title
import com.kapp.tenon.generated.resources.cabinet_hint_off
import com.kapp.tenon.generated.resources.cabinet_hint_read
import com.kapp.tenon.generated.resources.cabinet_hint_write
import com.kapp.tenon.generated.resources.cabinet_mine_subtitle_active
import com.kapp.tenon.generated.resources.cabinet_mine_subtitle_inactive
import com.kapp.tenon.generated.resources.cabinet_mine_title
import com.kapp.tenon.generated.resources.cabinet_mode_off
import com.kapp.tenon.generated.resources.cabinet_mode_read
import com.kapp.tenon.generated.resources.cabinet_mode_write
import com.kapp.tenon.generated.resources.cabinet_not_configured_desc
import com.kapp.tenon.generated.resources.cabinet_not_configured_title
import com.kapp.tenon.generated.resources.cabinet_open
import com.kapp.tenon.generated.resources.cabinet_peer_title
import com.kapp.tenon.generated.resources.cabinet_peers_title
import com.kapp.tenon.generated.resources.cabinet_perm_default_title
import com.kapp.tenon.generated.resources.cabinet_pick_dir
import com.kapp.tenon.generated.resources.cabinet_recent_empty
import com.kapp.tenon.generated.resources.cabinet_recent_hint
import com.kapp.tenon.generated.resources.cabinet_recent_row_desc
import com.kapp.tenon.generated.resources.cabinet_recent_title
import com.kapp.tenon.generated.resources.cabinet_share_dir_title
import com.kapp.tenon.generated.resources.cabinet_stop_share
import com.kapp.tenon.generated.resources.common_cancel
import com.kapp.tenon.generated.resources.common_profile_unavailable
import com.kapp.tenon.generated.resources.ic_close
import com.kapp.tenon.generated.resources.ic_external
import com.kapp.tenon.generated.resources.ic_folder
import com.kapp.tenon.generated.resources.ic_refresh
import com.kapp.tenon.ui.components.PeerAvatar
import com.kapp.tenon.ui.components.relativeTime
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.stringResource
import com.kapp.tenon.ui.theme.relayCardShadow

/**
 * 移动端共享文件柜主屏（对应原型 m-cabinet 与 m-cabinet-peer）。
 *
 * ## 宽屏收列（审计 §5.2，工作包 V3）
 *
 * 两个视图的内容列都按 [ContentWidths.Pane]（720dp）收列并居中，用的是共享原语
 * [centerCapped]。它**对外仍报告满宽**，所以「应该撑满整幅」的东西照旧撑满 ——
 * 头部与底栏的底色、头部与面包屑之下那两条分隔线都还是整幅宽 —— 只有被夹进 720
 * 的那部分内容居中。这正是原型把 `.topbar`（整幅底色 + `border-bottom`）与
 * `.tbinner`（收列内容）分成两层的原因；四段链的顺序为什么不能换，见
 * `AdaptiveContent.kt` 里 `centerCapped` 的 KDoc。
 *
 * - [MineCabinetView]：卡片流，直接收**内容列**（与 `SettingsHomeScreen` 同一写法：
 *   收列写在 `padding` 之外，于是 720 是外框、688 是内容）。
 * - [PeerCabinetView]：头部 / 面包屑 / 底栏是**通栏 chrome**，只收它们里面的内容
 *   （底色先铺满、再收内容，顺序反了底色就只盖住那 720）；中间那张表整体收成一列，
 *   于是表头底带、选中行高亮与行分隔线都止于 720。
 *
 * 为什么是 720：全仓列表型界面（节点 / Agent / 文件柜）共用这一个上限，出处是
 * 原型 `:631 .entrygrid{max-width:720px}`。**但这个出处本身不是文件柜的形状** ——
 * `.entrygrid` 在原型里属于 `screenDEmpty()`（`:4141` 的空态三卡），
 * 文件柜 `screenDCabinet()`（`:3235-3256`）没有任何宽度上限。所以 720 是代码侧按
 * 「1440 下一行文字会超出舒适阅读宽度、卡片被拉成扁条」推断的目标，不是原型要求。
 *
 * ## 诚实边界（不要照着原型的空气做工作）
 *
 * `d-cabinet` 在原型 `prototype/relay.html` 的 GALLERY（`:4519-4554`）里
 * **只有 expanded 一格**，compact / medium 档一格都没有（审计 §8.1 逐格核实过）。
 * 也就是说本文件在窄屏下该怎么变形、宽屏下该收多宽，原型都没画过；这里的取舍只能
 * 按代码事实给，将来要按原型对账时这一屏没有可对照的基准。
 *
 * ## 未做的部分（代码欠账，不是原型要求）
 *
 * 审计 §7.2 的「列表列数自适应」（工作包 V7）在本屏**未做**：下面两处 `LazyColumn`
 * 保持单列。对「表头 + 行」的表格与卡片流而言单列本身也是对的取舍（表格列宽固定，
 * 卡片流在窄档下再分列会碎），所以本包不动它 —— 但 §7.2 那条欠账仍然记在案。
 *
 * ## 「打开共享目录」是一颗**可空**按钮
 *
 * 共享目录卡里那颗「打开」曾经挂着一个空实现：按下去什么都不发生，而它画得跟旁边
 * 「更改」一模一样 —— 这正是本仓「一个按下去没反应的入口比没有更糟」的典型。
 *
 * 现在它由 [onOpenShareRoot] 决定画不画：宿主给得出真实动作（把共享目录交给系统文件
 * 管理器）就传进来，给不出就传 null，按钮**整颗不画** —— 与 [AppPlatform.receivedFileActions]
 * / `MessageComposer` 同一条规矩（能力缺失 = 不画，不是"点了失败"）。
 * 移动端两个系统（SAF / security-scoped bookmark）都还没有这条出口，所以那边不画；
 * 将来某台宿主接上了，只改它的调用点，本文件不动。
 */
@Composable
public fun CabinetScreen(
    onSetShareRoot: (String?) -> Unit,
    onSetShareMode: (String) -> Unit,
    onRemoveShareGrant: (String) -> Unit,
    onSelectPeer: (String?) -> Unit,
    onLoadPath: (String) -> Unit,
    onToggleSelection: (String) -> Unit,
    onDownloadEntries: (List<String>) -> Unit,
    onPickShareRoot: () -> Unit = {},
    onUploadToCabinet: ((String) -> Unit)? = null,
    /** 「加载更多」：拉下一页并追加；null = 这个壳没接，按钮不画。 */
    onLoadMore: (() -> Unit)? = null,
    /** 打开共享目录的真实动作；null = 本平台没有这条出口，那颗按钮不画。 */
    onOpenShareRoot: (() -> Unit)? = null,
    /**
     * 关掉全局横幅。控制器发的「已更新共享目录 / 已停止共享 / 下载已开始」等
     * 都走 `state.banner` 这一个字段 —— 本屏过去没有宿主渲染它，用户点了按钮
     * 什么反馈都看不到。默认 `{}` 只服务截图夹具（banner 恒为 null，画不出来）。
     */
    onDismissBanner: () -> Unit = {},
    modifier: Modifier = Modifier,
    now: Long = nowMillis(),
    /** Phase 4 订阅面切片（蓝图 §4.4）：默认值由 [state] 投影，未迁移/预览路径行为不变。 */
    transferSlice: TransferSlice,
    nodeSlice: NodeSlice,
    settingsSlice: SettingsSlice,
    chromeSlice: ChromeSlice,
) {
    val cabinet = transferSlice.cabinet

    Column(modifier.fillMaxSize()) {
        // 「最近有人放进来」「下载已开始」这类反馈的唯一出口（照 ConversationsScreen 的
        // 模式）：banner 数据一直在发，过去只是没有宿主画它。
        chromeSlice.banner?.let {
            AppBanner(
                banner = it,
                onDismiss = onDismissBanner,
                modifier = Modifier.padding(
                    horizontal = LocalAppSpacing.current.lg,
                    vertical = LocalAppSpacing.current.sm,
                ),
            )
        }
        if (cabinet.activePeerId != null) {
            // 浏览同事的文件柜
            PeerCabinetView(
                transferSlice = transferSlice,
                nodeSlice = nodeSlice,
                settingsSlice = settingsSlice,
                onBack = { onSelectPeer(null) },
                onLoadPath = onLoadPath,
                onToggleSelection = onToggleSelection,
                onDownloadEntries = onDownloadEntries,
                onUploadToCabinet = onUploadToCabinet,
                onLoadMore = onLoadMore,
                modifier = Modifier.weight(1f),
            )
        } else {
            // 我的文件柜设置与动态面板
            MineCabinetView(
                nodeSlice = nodeSlice,
                settingsSlice = settingsSlice,
                onSetShareRoot = onSetShareRoot,
                onPickShareRoot = onPickShareRoot,
                onSetShareMode = onSetShareMode,
                onRemoveShareGrant = onRemoveShareGrant,
                onSelectPeer = onSelectPeer,
                onOpenShareRoot = onOpenShareRoot,
                modifier = Modifier.weight(1f),
                now = now,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 卡片外框（原型逐条取值）
//
//   .card{border-radius:var(--r-card)}                    16px（`:root`）/ 12px（`.win`）
//   .card>h3{font-size:var(--fs-ctitle)=12px;font-weight:600;color:var(--primary);
//            padding:12px 14px 0;display:flex;align-items:center;gap:6px}
//   .card>h3 svg{width:14px;height:14px}
//   .padc{padding:10px 14px 14px}                          `:root`（`.win` 覆盖 8px 13px 13px）
//
// 标题与内容是**两层**：`h3` 自带 `padding:12px 14px 0`，不在 `.padc` 里。
// 原实现四处都对不上 —— 标题 14sp/700/默认色/无图标、内容内边距一律 14、
// 圆角完全没设（走 M3 默认），所以整个屏的圆角差集是 `只原型{8,12,16} 只代码{}`。
// ---------------------------------------------------------------------------
@Composable
private fun cabinetCardPad() = PaddingValues(
    start = LocalAppSpacing.current.lg14,
    top = LocalAppSpacing.current.md10,
    end = LocalAppSpacing.current.lg14,
    bottom = LocalAppSpacing.current.lg14,
)

/** 卡片标题：`.card>h3`。图标是原型自带的（`${ic('folder')}`），14dp、跟随 primary。 */
@Composable
private fun CabinetCardTitle(
    text: String,
    icon: DrawableResource? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = LocalAppSpacing.current.lg14, top = LocalAppSpacing.current.md, end = LocalAppSpacing.current.lg14),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(IconSizes.Inline),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(LocalAppSpacing.current.sm6))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        if (trailing != null) {
            Spacer(Modifier.width(LocalAppSpacing.current.sm6))
            trailing()
        }
    }
}

/** `.cnt`：卡标题后面的计数（`单独设过的同事<span class="cnt">2 人</span>`）。 */
@Composable
private fun CabinetCardCount(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun MineCabinetView(
    onSetShareRoot: (String?) -> Unit,
    onPickShareRoot: () -> Unit,
    onSetShareMode: (String) -> Unit,
    onRemoveShareGrant: (String) -> Unit,
    onSelectPeer: (String?) -> Unit,
    onOpenShareRoot: (() -> Unit)? = null,
    /**
     * 头部最左那颗"回列表"。`null` = 不画（手机屏独占一屏，没有列表可回）。
     *
     * 这是**外壳**差异，所以要参数化：桌面 Compact 档两栏互斥，详情页要能退回左栏；
     * 宽档左栏一直在旁边，传 `null`。
     */
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    now: Long = nowMillis(),
    /** Phase 4 订阅面切片（蓝图 §4.4）：默认值由 [state] 投影，未迁移/预览路径行为不变。 */
    nodeSlice: NodeSlice,
    settingsSlice: SettingsSlice,
) {
    val settings = settingsSlice.settings
    val hasRoot = !settings.shareRoot.isNullOrBlank()
    val peers = humanPeers(nodeSlice).filter { it.online }

    // 两个不可逆动作各自挂一道确认：停止共享（同事立刻看不到任何东西）与
    // 移除某位同事的单独授权。过去都是一按就生效 —— 与「退出群聊」「移出成员」
    // 同一条纪律：破坏性动作必须有回头路。
    var confirmStopShare by remember { mutableStateOf(false) }
    var grantToRemove by remember { mutableStateOf<String?>(null) }
    if (confirmStopShare) {
        RelayDialog(
            onDismissRequest = { confirmStopShare = false },
            title = { Text(stringResource(Res.string.cabinet_confirm_stop_share_title)) },
            content = {
                Text(
                    stringResource(Res.string.cabinet_confirm_stop_share_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                RelayDialogButton(
                    text = stringResource(Res.string.cabinet_stop_share),
                    onClick = {
                        confirmStopShare = false
                        onSetShareRoot(null)
                    },
                    danger = true,
                )
            },
            dismissButton = {
                RelayDialogButton(text = stringResource(Res.string.common_cancel), onClick = { confirmStopShare = false })
            },
        )
    }
    val removingGrant = grantToRemove
    if (removingGrant != null) {
        RelayDialog(
            onDismissRequest = { grantToRemove = null },
            title = { Text(stringResource(Res.string.cabinet_confirm_remove_grant_title)) },
            content = {
                Text(
                    stringResource(Res.string.cabinet_confirm_remove_grant_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                RelayDialogButton(
                    text = stringResource(Res.string.cabinet_grants_remove),
                    onClick = {
                        grantToRemove = null
                        onRemoveShareGrant(removingGrant)
                    },
                    danger = true,
                )
            },
            dismissButton = {
                RelayDialogButton(text = stringResource(Res.string.common_cancel), onClick = { grantToRemove = null })
            },
        )
    }

    // 顶栏必须是**整幅宽的 surface 面 + 一条 hairline**，与消息 / 节点 / 设置三页共用
    // [LargeTitleBar]。此前标题画在 LazyColumn 的第一个 item 里，直接铺在页面底色
    // `background` 上：浅色只差 #FFFFFF→#FAFAF8（2/255，看着"发灰"），深色是
    // #232625→#121413（17/255，一整条色带），真机上就是「这个 tab 的背景和别的
    // tab 不一样、标题栏也不一样」。原型 m11b-cabinet 的 `.topbar` 用 `--surface`、
    // 下面的 `.pad` 才透出 `--bg`；同包的 [PeerCabinetView] 本来就是这么画的。
    // 标题留在滚动列表里还会跟着内容滚走，也是与其它四页不一致的一处。
    Column(modifier.fillMaxSize()) {
        LargeTitleBar(
            title = stringResource(Res.string.cabinet_mine_title),
            subtitle = if (hasRoot) {
                stringResource(Res.string.cabinet_mine_subtitle_active)
            } else {
                stringResource(Res.string.cabinet_mine_subtitle_inactive)
            },
            onBack = onBack,
            // 桌面 Compact 档这颗是「回列表」不是「返回上一页」，字形随语义走。
            navigationIcon = if (onBack != null) Res.drawable.ic_list else null,
            navigationIconDescription = if (onBack != null) stringResource(Res.string.desktop_action_list) else null,
            actions = {
                if (hasRoot) {
                    AppOutlinedButton(
                        text = stringResource(Res.string.cabinet_stop_share),
                        onClick = { confirmStopShare = true },
                    )
                }
            },
            contentMaxWidth = ContentWidths.Pane,
        )
        TitleBarDivider()

        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                // 宽屏收列（审计 §5.2）：1440 下这一列会拉满整幅，一行文字超过舒适阅读宽度。
                // 720 是列表型界面共用的上限（[ContentWidths.Pane]，出处原型 `:631 .entrygrid`）。
                // 收列写在 padding 之外，于是 720 是外框、688 是内容宽。顶栏内容用同一个
                // 上限收，两者才对得齐。
                .centerCapped(ContentWidths.Pane)
                // 原型 `.pad{padding:14px 16px 16px}`（`prototype/relay.html:556`）—— 顶 14、其余 16。
                .padding(start = LocalAppSpacing.current.lg, top = LocalAppSpacing.current.lg14, end = LocalAppSpacing.current.lg, bottom = LocalAppSpacing.current.lg),
            // 卡片间距：原型是内联 `style="height:10px"`（不在 CSS 里，差集工具扫不到），
            // 不是 14。四张卡各隔 10px。
            verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md10),
        ) {

        // 同事的文件柜入口栏
        if (peers.isNotEmpty()) {
            item {
                Text(
                    // 这一段是「同事的文件柜」入口栏，标题不该带名字 —— 原来把
                    // `peers.size` 塞进 `cabinet_peer_title`（`%1$s的文件柜`），
                    // 真机上渲染成「4的文件柜」（截图预览里一直是这个错字）。
                    stringResource(Res.string.cabinet_peers_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(LocalAppSpacing.current.sm))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm)) {
                    peers.take(4).forEach { p ->
                        Card(
                            onClick = { onSelectPeer(p.nodeId) },
                            modifier = Modifier.weight(1f),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            Column(Modifier.padding(LocalAppSpacing.current.md10), horizontalAlignment = Alignment.CenterHorizontally) {
                                // 40dp：手机仅有的快捷卡（桌面文件柜不走这里）。32dp 在卡片里
                                // 是小图标档，对齐工作区单元卡 UnitAvatar 的 40dp 既有档。
                                PeerAvatar(name = p.nick, isAgent = p.isAgent, size = 40.dp)
                                Spacer(Modifier.height(LocalAppSpacing.current.xs))
                                Text(p.nick, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }

        if (!hasRoot) {
            item {
                Card(
                    Modifier
                        .fillMaxWidth()
                        .relayCardShadow(shape = MaterialTheme.shapes.large),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    // 原型 `.card{box-shadow:var(--shadow)}`：海拔归零、投影自绘。
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Column(Modifier.padding(cabinetCardPad()), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(painterResource(Res.drawable.ic_folder), contentDescription = null, Modifier.size(44.dp))
                        Spacer(Modifier.height(LocalAppSpacing.current.md10))
                        Text(
                            stringResource(Res.string.cabinet_not_configured_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(LocalAppSpacing.current.sm6))
                        Text(
                            stringResource(Res.string.cabinet_not_configured_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(LocalAppSpacing.current.lg14))
                        AppButton(
                            text = stringResource(Res.string.cabinet_pick_dir),
                            onClick = { onPickShareRoot() },
                        )
                    }
                }
            }
        } else {
            // 卡片 1: 共享目录。原型 `<h3>${ic('folder')} 共享目录</h3>`，`.padc` 里是
            // `.path` 一行 + 8px + 两颗 `.btn.sm`（`ic('external')` 打开 / `ic('refresh')` 更改…）。
            // `.btn.sm{padding:6px 10px;font-size:12px;gap:5px}` —— M3 `Button` 默认 24dp 横内边距，
            // 两颗按钮会撑出卡外，所以内边距要显式压到 10/6。
            item {
                Card(
                    Modifier
                        .fillMaxWidth()
                        .relayCardShadow(shape = MaterialTheme.shapes.large),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    // 原型 `.card{box-shadow:var(--shadow)}`：海拔归零、投影自绘。
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    CabinetCardTitle(stringResource(Res.string.cabinet_share_dir_title), icon = Res.drawable.ic_folder)
                    Column(Modifier.padding(cabinetCardPad())) {
                        Text(settings.shareRoot.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(LocalAppSpacing.current.sm))
                        Row(horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm), verticalAlignment = Alignment.CenterVertically) {
                            // 原型这两颗是 `.btn.sm`，而 `.btn` 的默认形态是
                            // **白底 + 1px `--outline` 描边 + `--on` 色文字**
                            // （`prototype/relay.html:338-340`，只有 `.pri` 才是实心绿）。
                            // 上一版用 M3 `Button`（实心 primary 底 + 白字）—— 形态不符，
                            // 并排图里一眼就是两团绿块 vs 两个白描边小钮。
                            // 没有真实动作就整颗不画（见本文件顶部 KDoc）：一颗按下去什么都不发生的
                            // 「打开」比没有这个入口更糟 —— 用户会以为目录打不开，而不是这台设备
                            // 没有这条出口。
                            val openShareRoot = onOpenShareRoot.takeIf { shouldShowShareRoot(it) }
                            if (openShareRoot != null) {
                                AppOutlinedButton(
                                    text = stringResource(Res.string.cabinet_open),
                                    onClick = openShareRoot,
                                    size = AppButtonSize.Small,
                                    leadingIcon = Res.drawable.ic_external,
                                )
                            }
                            AppOutlinedButton(
                                text = stringResource(Res.string.cabinet_change),
                                onClick = { onPickShareRoot() },
                                size = AppButtonSize.Small,
                                leadingIcon = Res.drawable.ic_refresh,
                            )
                        }
                        Spacer(Modifier.height(LocalAppSpacing.current.sm6))
                        Text(stringResource(Res.string.cabinet_dir_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // 卡片 2: 默认权限。原型是一枚**分段控件**：
            //   `.cbseg{display:inline-flex;gap:2px;background:var(--surface-2);border-radius:12px;padding:3px}`
            //   `.cbchip{padding:5px 11px;border-radius:8px;font-size:12px;color:var(--on-2)}`
            //   `.cbchip.on{background:var(--surface);color:var(--on);font-weight:600}`
            // 原实现是三颗实心 M3 `Button`（选中 = 品牌绿实底）—— 控件形态就不对，
            // 顺带把 3/2/5/11 这几个间距与 8/12 两个圆角一起丢了。
            item {
                Card(
                    Modifier
                        .fillMaxWidth()
                        .relayCardShadow(shape = MaterialTheme.shapes.large),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    // 原型 `.card{box-shadow:var(--shadow)}`：海拔归零、投影自绘。
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    CabinetCardTitle(stringResource(Res.string.cabinet_perm_default_title))
                    Column(Modifier.padding(cabinetCardPad())) {
                        Row(
                            modifier = Modifier
                                .clip(MaterialTheme.shapes.medium)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(3.dp),
                            horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.xxs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val modes = listOf("off" to Res.string.cabinet_mode_off, "read" to Res.string.cabinet_mode_read, "write" to Res.string.cabinet_mode_write)
                            modes.forEach { (m, labelRes) ->
                                val selected = settings.shareMode == m
                                Text(
                                    stringResource(labelRes),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                    color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        // 手机上这是主要的权限入口，命中区必须到 48dp 下限
                                        // （视觉尺寸不变，内容在扩大的热区里居中）—— 与节点页
                                        // 小按钮、同事柜动作钮同一条无障碍纪律。
                                        .minimumInteractiveComponentSize()
                                        .clip(MaterialTheme.shapes.small)
                                        .background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent)
                                        .clickable { onSetShareMode(m) }
                                        .padding(horizontal = 11.dp, vertical = 5.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(LocalAppSpacing.current.sm))
                        val hintRes = when (settings.shareMode) {
                            "off" -> Res.string.cabinet_hint_off
                            "write" -> Res.string.cabinet_hint_write
                            else -> Res.string.cabinet_hint_read
                        }
                        Text(stringResource(hintRes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // 卡片 3: 单独设过的同事。原型行是 `.cbfpen`（**不在 `.padc` 里**，直接挂在卡上）：
            //   `.cbfpen{display:flex;align-items:center;gap:8px;padding:9px 14px;
            //            border-bottom:1px solid var(--hair);font-size:14px}`
            //   行内三件：26px 圆形首字头像 + `.who` 昵称 + `.perm` 权限中文 + `.ix` 移除键。
            // 原实现印的是 `grant.nodeId.take(8)`（一串十六进制）与**原始枚举** `grant.mode`，
            // 且没有头像；计数 `.cnt` 也没有跟标题同排。
            item {
                Card(
                    Modifier
                        .fillMaxWidth()
                        .relayCardShadow(shape = MaterialTheme.shapes.large),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    // 原型 `.card{box-shadow:var(--shadow)}`：海拔归零、投影自绘。
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    CabinetCardTitle(
                        stringResource(Res.string.cabinet_grants_title),
                        trailing = { CabinetCardCount(stringResource(Res.string.cabinet_grants_count, settings.shareGrants.size)) },
                    )
                    Spacer(Modifier.height(LocalAppSpacing.current.sm6))
                    settings.shareGrants.forEach { grant ->
                        val nick = humanPeers(nodeSlice).firstOrNull { it.nodeId == grant.nodeId }?.nick
                            ?: peer(grant.nodeId, nodeSlice, settingsSlice)?.displayName
                            ?: stringResource(Res.string.common_profile_unavailable)
                        // 原型在这一行挂了高亮底 `.cbfpen` + `style="background:var(--primary-c)"`
                        // （`prototype/relay.html:4107`，浅色 `#B7E4C7`）。原型那处是**硬编码昵称**
                        // `${g.nick==='老周'?' style="background:var(--primary-c)"':''}`，代码里
                        // 没有、也不该有「按昵称判断」这种东西，所以这里换成同一条判据的**数据形态**：
                        // 原型 `CAB_GRANTS` = 老周 `perm:2`(可读可传) / Huang `perm:1`(只读)，
                        // 而默认权限是 `shareChip(1)` = **只读**（`prototype/relay.html:4079,4101`）
                        // ⇒ 高亮的恰好是**与默认档不同的那一行**（老周 2≠1 高亮；Huang 1==1 不高亮）。
                        // 这与「单独设过的同事」这张卡的语义也自洽：与默认档相同的授权其实是个空操作，
                        // 不需要被强调。
                        val isHighlighted = grant.mode != settings.shareMode
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (isHighlighted) {
                                        Modifier.background(MaterialTheme.colorScheme.primaryContainer)
                                    } else {
                                        Modifier
                                    },
                                )
                                .padding(horizontal = LocalAppSpacing.current.lg14, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm),
                        ) {
                            // 32dp：26dp 是原型 `.cbfrow` 的 Web 密度，配 bodyMedium(14sp)
                            // 在手机上失衡（同一屏快捷卡是 40dp 的既有档）。有意偏离原型。
                            PeerAvatar(name = nick, size = 32.dp)
                            Text(nick, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            Text(
                                stringResource(
                                    when (grant.mode) {
                                        "off" -> Res.string.cabinet_mode_off
                                        "write" -> Res.string.cabinet_mode_write
                                        else -> Res.string.cabinet_mode_read
                                    }
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            AppIconButton(
                                icon = Res.drawable.ic_close,
                                contentDescription = stringResource(Res.string.cabinet_grants_remove),
                                onClick = { grantToRemove = grant.nodeId },
                                tone = AppButtonTone.Danger,
                            )
                        }
                        HorizontalDivider()
                    }
                    Column(Modifier.padding(cabinetCardPad())) {
                        Text(stringResource(Res.string.cabinet_grants_default_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // 卡片 4: 最近有人放进来。原型行是 `.cbfrow`：
            //   `.cbfrow{display:flex;align-items:center;gap:10px;padding:9px 14px;
            //            border-bottom:1px solid var(--hair)}`
            //   行内：26px 圆形首字头像 + `.nm`「{昵称}放进来 {n} 个文件 · {size}」+ `.tm` 时间。
            item {
                Card(
                    Modifier
                        .fillMaxWidth()
                        .relayCardShadow(shape = MaterialTheme.shapes.large),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    // 原型 `.card{box-shadow:var(--shadow)}`：海拔归零、投影自绘。
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    CabinetCardTitle(stringResource(Res.string.cabinet_recent_title))
                    Spacer(Modifier.height(LocalAppSpacing.current.sm6))
                    if (settings.cabinetRecentUploads.isEmpty()) {
                        Column(Modifier.padding(cabinetCardPad())) {
                            Text(stringResource(Res.string.cabinet_recent_empty), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        settings.cabinetRecentUploads.forEach { entry ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = LocalAppSpacing.current.lg14, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md10),
                            ) {
                                // 32dp：26dp 是原型 `.cbfrow` 的 Web 密度，与上面授权行同一档。
                                PeerAvatar(name = entry.uploaderNick, size = 32.dp)
                                Text(
                                    stringResource(Res.string.cabinet_recent_row_desc, entry.uploaderNick, entry.fileCount, formatBytes(entry.totalSize)),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(relativeTime(entry.ts, now), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            HorizontalDivider()
                        }
                    }
                    Column(Modifier.padding(cabinetCardPad())) {
                        Text(stringResource(Res.string.cabinet_recent_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        }
    }
}
