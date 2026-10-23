// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.navigation

import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.ic_chat_bubble
import com.kapp.tidings.generated.resources.ic_hdd
import com.kapp.tidings.generated.resources.ic_lan_search
import com.kapp.tidings.generated.resources.ic_settings
import com.kapp.tidings.generated.resources.ic_terminal
import com.kapp.tidings.generated.resources.screen_agents
import com.kapp.tidings.generated.resources.screen_cabinet
import com.kapp.tidings.generated.resources.screen_conversations
import com.kapp.tidings.generated.resources.screen_discovery
import com.kapp.tidings.generated.resources.screen_settings
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource

// [AppRoutes] 已切到独立文件（同 package）：它零 Compose 依赖，而本文件其余部分
// 带 StringResource / DrawableResource。混在一起会让「路由 id」被资源类型拖住。

/**
 * 一个导航目的地。
 *
 * `label` 与 `icon` 都是资源引用而不是字面量：这个应用要在窄屏上放页签，
 * 而标签截断（`Conversations` → `Conversati`）正是脚手架版本在真机上暴露的第一个缺陷。
 * 把文案放进资源，既满足「用户可见字符串只在 strings.xml」的约定，也让窄屏适配
 * 只需要处理一个 `StringResource`。
 */
data class AppNavItem(
    val id: String,
    val label: StringResource,
    val icon: DrawableResource,
)

/**
 * 底栏 / 侧栏的四个目的地。
 *
 * 聊天**不在这里**。设计契约 `design/design-spec.json` 把
 * `screens.chat.adaptive.compact.navigation` 标成 `top_bar_back`（detail 为 none），
 * 其余才是 `bottom_bar`；原型 `prototype/relay.html:2441` 的 `NAV` 同样只有四项，
 * 聊天屏另外标了 `full:true`（`:1365`），`device()` 据此**整屏不画导航**（`:1491-1501`）。
 *
 * 曾经这里把 chat 当成第六个平级页签，代价有两个：窄屏上标签被截断，
 * 以及系统返回键在聊天页上会**直接退出应用**而不是回到会话列表。
 *
 * ## 为什么从五项变成四项
 *
 * 原先的「发现」和「加入」是两个平级页签，但它们讲的是同一件事的两半：
 * 发现是"谁在附近"，加入是"怎么把不在附近的那个弄进来"。用户在这两件事之间
 * 并不需要切换身份，而要回答"我现在附近有谁 / 我怎么加一个"却得来回切两屏。
 * 合并成 [com.kapp.tidings.feature.nodes.NodesScreen] 一屏之后，`AppRoutes.PAIRING`
 * 和 `feature/pairing/` 一起消失，加入动作改由页面里的底部弹层承载。
 *
 * 页签从 5 变 4 还有个副作用是好的：393dp 宽的手机上标签不再被挤到截断。
 *
 * ## 图标按 design-spec.json 的 `icons` 语义取用
 *
 * 会话页签用 `ic_chat_bubble`（语义 `conversation`），Agent 页签用
 * `ic_terminal`（语义 `agent_console`）。先前这两个位置分别错用了
 * `ic_groups`（语义 `group`）和 `ic_bot`（语义 `agent_peer`）：
 * `ic_bot` 已经在 [com.kapp.tidings.ui.components.PeerAvatar] 里表示"这个对端是
 * Agent"，再拿它当 Agent 控制台的页签图标会把"对端身份"和"功能入口"混成一个词。
 * `ic_groups` 的落点在 [com.kapp.tidings.ui.components.PeerAvatar]：用于绘制群头像。
 *
 * ## 文件柜页签：`ic_hdd`，不是 `ic_cabinet`
 *
 * 原型 `prototype/relay.html:2441` 的 `NAV[1] = {id:'cabinet', label:'文件柜', icon:'hdd'}`
 * —— 底栏这一格画的是 `i-hdd`。桌面左栏 `DVIEWS` 的 `cabinet` 同样是 `icon:'hdd'`（`:2457`）。
 *
 * 这里原先用 `ic_cabinet`：那颗 vector 是照着原型 sprite 里的 `i-box` 画的，
 * 而 `box` 在整份原型里**三种引用形式都查不到**（无 `ic('box')`、无 `icon:'box'`、
 * 无 `#i-box"`）—— sprite 里定义了、一次都没用。把 `ic_cabinet` 重绘成 stroke
 * 只会把一个**取错的图标**固化成"看起来对"。正确顺序是先改这一行：
 * `ic_hdd` 已经存在、已经是 stroke、几何与 `i-hdd` 一致。
 */
val AppNavItems = listOf(
    AppNavItem(
        id = AppRoutes.CONVERSATIONS,
        label = Res.string.screen_conversations,
        icon = Res.drawable.ic_chat_bubble,
    ),
    AppNavItem(
        id = AppRoutes.CABINET,
        label = Res.string.screen_cabinet,
        icon = Res.drawable.ic_hdd,
    ),
    AppNavItem(
        id = AppRoutes.NODES,
        label = Res.string.screen_discovery,
        icon = Res.drawable.ic_lan_search,
    ),
    AppNavItem(
        id = AppRoutes.AGENTS,
        label = Res.string.screen_agents,
        icon = Res.drawable.ic_terminal,
    ),
    AppNavItem(
        id = AppRoutes.SETTINGS,
        label = Res.string.screen_settings,
        icon = Res.drawable.ic_settings,
    ),
)
