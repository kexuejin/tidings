// SPDX-License-Identifier: GPL-3.0-only

package app.relay.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 宽屏收列的**唯一真相源** —— 供手机壳、平板覆盖层、桌面设置窗三条路共用。
 *
 * ## 为什么要抽这一层
 *
 * 原型 `prototype/relay.html` 把「收成一列」的口径写在两处 CSS 上：
 * `:1045 .pad.set{max-width:640px;margin:0 auto}` 与 `:395 .topbar.set .tbinner{
 * max-width:608px;margin:0 auto}`。此前代码侧只有散落的 `fillMaxWidth()`（`640`/`608`
 * 在 `commonMain` 与 `desktopApp` 里 grep 零命中），而这一轮有六个包要收列 ——
 * 各写一次必然漂，数字一漂就再也看不出它是「收列」还是「随手取的宽度」。
 *
 * ## 这一层只解决「横向收列」这一件事
 *
 * 它不回答「这个窗口该挂哪个壳」（`App.kt` 的 `resolveAppLayoutMode`），
 * 也不回答「壳内该分几栏」（`ui/desktop/DesktopLayout.kt` 的 `desktopWindowSizeClass`）。
 * 一个 Composable 拿到的可用宽度是多少，就按这里的口径收一次 —— 与它是手机、
 * 平板还是桌面窗无关，所以它住在 `app.relay.ui`（共享层）而不是 `app.relay.ui.desktop`。
 *
 * ## 640 / 608 的关系（照抄原型，不要"优化"）
 *
 * 640 是**外框**（含它自己的 16dp 横向 padding），608 是**内容**宽：
 * 原型 `.pad{padding:14px 16px 18px}`（`:441`），`:394` 的注释写明
 * 「608 = 640（.pad.set 外框）− 2×16 …… 要对齐的是卡片**内容**左边缘」。
 *
 * 原型把顶栏写成 608 是因为 `.tbinner` **自身没有**横向 padding（16px 在外层 `.topbar` 上）；
 * 而 Compose 的 `LargeTitleBar`（`ui/components/TopBars.kt:56`）自带 `padding(horizontal = 16.dp)`。
 * 所以两侧在 Compose 里**统一按 [Settings] = 640 收列**：顶栏外框 640 − 2×16 = 608 内容，
 * 与内容列 640 − 2×16 = 608 内容左边缘重合 —— 这正是原型 `:394` 那条注释要的效果。
 * （若把顶栏按 608 收，内容会再被它自己的 16dp 吃掉 32dp，反而对不齐。）
 */
public object ContentWidths {
    /** 设置类界面内容列**外框**宽（原型 `.pad.set` 的 640，含自身 16dp 横向 padding）。 */
    public val Settings: Dp = 640.dp

    /**
     * 收列后实际可得的**内容**宽 = 640 − 2×16（原型 `.topbar.set .tbinner` 的 608）。
     *
     * 只用于注释与断言：Compose 侧顶栏与内容列都按 [Settings] 收列（理由见本对象 KDoc），
     * 不要拿它去当 `widthIn` 的上限，那样会窄 32dp。
     */
    public val SettingsInner: Dp = 608.dp

    /**
     * 列表型界面（节点 / Agent / 文件柜）的内容列上限（原型 `:631 .entrygrid{max-width:720px}`）。
     *
     * 这类「一排卡片」的屏在 1440 下拉满整幅时，一行文字会超过舒适阅读宽度，
     * 卡片也被拉成扁条。720 直接取原型的 entrygrid 上限。
     */
    public val Pane: Dp = 720.dp

    /** 侧栏常规宽（原型 `.win` 侧栏 300，与 `DesktopMetrics.SIDEBAR_PX` 同值）。 */
    public val Sidebar: Dp = 300.dp

    /** 侧栏收窄档：Medium 档（664–999）下侧栏让出 60dp 给详情。 */
    public val SidebarNarrow: Dp = 240.dp

    /**
     * 气泡占**消息区可用宽度**的比例上限 —— 原型 `:927 .msg{max-width:var(--msg-max)}`，
     * 值在 `:56 --msg-max:82%`（根 token 块）与 `:464 .win{--msg-max:62%}`（桌面外壳类）。
     *
     * **手机档反而更宽**，这不是笔误：手机上气泡就是唯一内容，宽一点不碍事；
     * 桌面/平板上消息区只是右半栏，再按 82% 就会横穿整幅。
     * 与此相对的旧实现是一个写死的 `widthIn(max = 300.dp)`，两端共用 —— 1100 宽的
     * 桌面窗里聊天栏约 800dp，原型按 62% ≈ 496dp，代码却封在 300dp。
     *
     * ## 取哪一档由**宿主外壳**决定，不是按可用宽度算出来的
     *
     * 原型这两条值挂在两个**类**上：`:56` 在根 token 块（设备渲染器 `.device > .app`
     * 继承它），`:464` 在桌面外壳类 `.win` 上。`FORMS` 表（`:1851-1863`）正是按
     * `form:'mobile' | 'desktop'` 分档的，与窗口多宽无关。反证就在表里：
     * 桌面紧凑档 **560 → 62%**，平板档 **800 → 82%** —— 560 比 800 窄，档位却相反，
     * 所以任何「按可用宽度取阈值」的规则都不可能同时成立。
     *
     * 早先的实现恰好就是按可用宽度取阈值（拿 `DesktopMetrics.MEDIUM_MIN_PX` = 664 这个
     * **窗口**阈值去比**消息区可用宽**），量纲不对，还在阈值附近制造了不连续：
     * 窗口 1064 → 可用 663 → 82%，窗口 1066 → 可用 665 → 62% —— 窗口加宽 2dp，
     * 气泡反而从 543.7dp 缩到 412.3dp。
     */
    public const val BUBBLE_FRACTION_PHONE: Float = 0.82f
    public const val BUBBLE_FRACTION_WIDE: Float = 0.62f
}

/**
 * 把内容收进 [maxWidth] 一列并水平居中。
 *
 * **关键性质：节点对外报告的宽度仍是父级满宽。** 背景、hairline、`Surface` 底色这些
 * 「应该撑满整幅」的东西照旧撑满，只有 [maxWidth] 以内的内容被摆在中间 ——
 * 这正是原型 `.topbar`（整幅底色 + `border-bottom`）与 `.tbinner`（收列内容）分两层的原因。
 *
 * 用 `Box(contentAlignment = TopCenter)` 也能达到同样效果，但那会多一层布局节点，
 * 打断调用点原有的 `weight(1f)` / `fillMaxHeight()` 链，每处都要重新推一遍高度从哪来。
 *
 * 四段链各自的职责（**顺序不能换**）：
 * 1. `fillMaxWidth()` —— 先把节点撑到父级满宽，保证背景与分隔线满幅；
 * 2. `wrapContentWidth(CenterHorizontally)` —— 把**子节点**的测量下限归零，
 *    并把量出来的内容摆在满宽的中间；
 * 3. `widthIn(max = maxWidth)` —— 此刻才夹上限（下限已是 0，不会被父级的
 *    `minWidth == maxWidth` 顶回去）；容器窄于上限时自动退化成容器宽；
 * 4. `fillMaxWidth()` —— 让内容**真的**占满这个被夹过的宽度。没有第 4 步时，
 *    一段窄文字会把整列缩成文字宽，收列就变成"内容居中但列宽不确定"，
 *    卡片的左右边缘也就各自浮在不同的 x 上。
 *
 * @param maxWidth 内容列上限，取自 [ContentWidths]。
 */
public fun Modifier.centerCapped(maxWidth: Dp): Modifier =
    this.fillMaxWidth()
        .wrapContentWidth(Alignment.CenterHorizontally)
        .widthIn(max = maxWidth)
        .fillMaxWidth()
