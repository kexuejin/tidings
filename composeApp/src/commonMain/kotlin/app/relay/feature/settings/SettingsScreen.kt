// SPDX-License-Identifier: GPL-3.0-only

package app.relay.feature.settings

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import app.relay.app.RelaySettings
import app.relay.app.RelayUiState

/**
 * 协议字段名（不是文案，不进 strings.xml）。
 *
 * `defaultPort` 是**真的**：它落到 `RelaySettings.udpPort`。这里以前还有
 * `baseUrl` / `timeoutMs` / `inboxLimit` 三个键和它们的原型示例值 —— 那三个框属于
 * 桌面端 agent 节点的控制面（RPC），手机端从不调控制面，填完没有去处，
 * 已按审计 P0-5 连同示例值一起撤下。
 */
// 原型这一格的等宽字段名就是 `udpPort`（`prototype/relay.html:3820` 的 `<span class="fk">udpPort</span>`），
// 不是 `defaultPort` —— 那是第一轮自己起的名，量具与图都对不上。
internal const val KEY_DEFAULT_PORT = "udpPort"

/**
 * 设置 · 第一屏：设置首页。
 *
 * 设计真相源是 `prototype/relay.html:3632` 的 `screenSettingsHome()`：
 * 大标题「设置」+ 副标题展示概览，采用首页当前值概览 + 子页面下钻架构。
 *
 * 凭据卡已按审计 P0-5 / P0-6 撤下：手机端不跑 agent 节点，也不调控制面。
 * 资料与连接、通知、外观、能力、数据与诊断均通过首页当前值与对应子页承接。
 *
 * **凭据卡已撤下（审计 P0-5 / P0-6）**，所以现在只剩两张卡。原因写在原来那张卡的位置上：
 * 手机端不跑 agent 节点、也不调控制面，那张卡的状态值永远是默认的「未配置」，
 * 输入框和保存/清除按钮都没有落盘的去处；而令牌的真身是**另一台机器**上 agentNode
 * 进程的环境变量，不是这个界面能改的东西。同理，「连接」卡里控制面地址 / 请求超时 /
 * 收件箱上限三个框也属于那个控制面，一并撤下；那张卡只剩下一个真字段：默认 UDP 端口，
 * 它落到 `RelaySettings.udpPort`。
 *
 * 所以这一屏仍然是「配置必须有录入界面，不能让用户手写配置文件」那条硬约束的落点：
 * 昵称、公司、部门、团队、头像、UDP 端口都能在这里直接改，保存即生效。
 *
 * 脚手架的 Settings 屏在真机上**一个输入框都没有**，只有
 * `Set Profile / ProfileEditor / max_length_32` 这样的占位串 —— 那是脚手架的问题。
 *
 * @param dataDirectory 已不在本屏展示（搬到了 [SettingsDataSubpage]）；为了保持旧调用点
 *   能编译而保留，不要当成本屏的数据来源读。
 * @param cacheDirectory 同上。
 * @param onBack 非 null 时顶栏左侧画返回键。
 */
private const val SUBPAGE_PROFILE = "profile"
private const val SUBPAGE_CONN = "conn"

/**
 * 设置 · 双端自适应架构。
 *
 * 移动端：一级首页带当前值（资料、连接与通知、外观与能力、数据与诊断）+ 二级子页（资料编辑、连接）。
 * 桌面端：由 DesktopSettingsPane 承接独立窗口与统一左侧列表。
 */
@OptIn(
    ExperimentalLayoutApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)
@Composable
fun SettingsScreen(
    state: RelayUiState,
    dataDirectory: String,
    cacheDirectory: String,
    onSaveProfile: (String, String, String, String, Int) -> Unit,
    onSavePorts: (String, String) -> Unit,
    onRestart: () -> Unit,
    onAddScanRange: (String) -> Unit = {},
    onRemoveScanRange: (String) -> Unit = {},
    onTriggerCidrScan: (String?) -> Unit = {},
    /** 「后台保持连接」：本平台有没有这条能力（Android 有）、当前开关值、以及改动出口。 */
    keepAliveSupported: Boolean = false,
    keepAliveEnabled: Boolean = true,
    onKeepAliveChange: (Boolean) -> Unit = {},
    onDismissBanner: () -> Unit,
    onClearNote: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    /** null = 用当前 `settings.udpPort`，这样旧调用点也能看到真实值。 */
    defaultPort: String? = null,
    /** 进入第二屏；参数是页键（`SETTINGS_PAGE_*`）。null 时底部不画入口。 */
    onOpenPage: ((String) -> Unit)? = null,
    /** 资料页头像分段控件第三档「上传图片」的出口。null = 该档置灰。 */
    onOpenAvatarPage: (() -> Unit)? = null,
    onRemovePickedAvatar: (() -> Unit)? = null,
    initialSubpage: String? = if (state.restarting) SUBPAGE_CONN else null,
) {
    var subpage by rememberSaveable(initialSubpage) { mutableStateOf(initialSubpage) }

    BackHandler(enabled = subpage != null) {
        subpage = null
    }

    when (subpage) {
        SUBPAGE_PROFILE -> {
            SettingsProfileSubpage(
                state = state,
                onSaveProfile = { n, c, d, t, a ->
                    onSaveProfile(n, c, d, t, a)
                    subpage = null
                },
                onBack = { subpage = null },
                onOpenAvatarPage = onOpenAvatarPage,
                onRemovePickedAvatar = onRemovePickedAvatar,
                modifier = modifier,
            )
        }
        SUBPAGE_CONN -> {
            SettingsConnectionSubpage(
                state = state,
                defaultPort = defaultPort,
                onSavePorts = onSavePorts,
                onRestart = onRestart,
                onAddScanRange = onAddScanRange,
                onRemoveScanRange = onRemoveScanRange,
                onTriggerCidrScan = onTriggerCidrScan,
                keepAliveSupported = keepAliveSupported,
                keepAliveEnabled = keepAliveEnabled,
                onKeepAliveChange = onKeepAliveChange,
                onBack = { subpage = null },
                modifier = modifier,
            )
        }
        else -> {
            SettingsHomeScreen(
                state = state,
                onNavigateProfile = { subpage = SUBPAGE_PROFILE },
                onNavigateConnection = { subpage = SUBPAGE_CONN },
                onOpenPage = onOpenPage,
                onDismissBanner = onDismissBanner,
                onClearNote = onClearNote,
                onBack = onBack,
                modifier = modifier,
            )
        }
    }
}
