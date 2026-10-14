// SPDX-License-Identifier: GPL-3.0-only

package app.relay.feature.chat

import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/**
 * 气泡宽度上限：消息区**可用宽度**的一个比例，不是一个写死的像素值。
 *
 * 原型里这条上限就是比例 —— `prototype/relay.html:1083 .msg{max-width:var(--msg-max)}`，
 * `--msg-max` 在 `:56` 是 `82%`，桌面壳在 `:464 .win{--msg-max:62%}` 把它覆盖成 `62%`。
 * 百分比的包含块是 `.msgs` 的内容盒：`:920 .msgs{padding:12px 14px 6px}` 与
 * `:921 .win .msgs{padding:14px 18px 6px}` 说明这个内容盒已经把消息区自己的横向内边距
 * 扣掉了。所以 [availableWidth] 要传**消息区内容宽**，不是整幅窗口宽 —— 在 `ChatScreen`
 * 里它就是列表项宽 = LazyColumn 宽 − 2×16dp 的 `contentPadding`（`ChatScreen.kt:414`），
 * 由气泡外面那层 `BoxWithConstraints` 实测得到（`ChatScreen.kt` 里气泡外那层 `BoxWithConstraints`）。
 *
 * ## [fraction] 为什么是形参，而不是在这里按宽度算
 *
 * 原型那两条值挂在两个**类**上（根 token 块 / `.win` 桌面外壳类），`FORMS` 表
 * （`:1851-1863`）按 `form:'mobile' | 'desktop'` 分档 —— 判据是「这个界面挂哪个外壳」，
 * 与窗口多宽无关。反证在表里：桌面紧凑档 **560 → 62%**，平板档 **800 → 82%**；
 * 560 比 800 窄，档位却相反，所以「按可用宽度取阈值」的规则不可能同时成立。
 * 早先的实现正是按可用宽度选档（拿 664 这个**窗口**阈值去比**消息区可用宽**），
 * 量纲不对，还在阈值附近制造了不连续：窗口 1064 → 可用 663 → 82%，
 * 窗口 1066 → 可用 665 → 62% —— 窗口加宽 2dp，气泡反而窄 131dp。
 *
 * 所以选档这件事交给**知道自己在哪个外壳里**的调用方：手机壳传
 * [app.relay.ui.ContentWidths.BUBBLE_FRACTION_PHONE]，桌面壳（以及走同一条
 * `DesktopShell → ConversationsPane → ChatPane` 路径的平板两栏）传
 * [app.relay.ui.ContentWidths.BUBBLE_FRACTION_WIDE]。平板两栏与桌面同路径、原型里
 * `tabhost` 那一档也是 `form:'desktop'`，所以归 62% 是正确对齐而不是将就。
 *
 * 为什么不能沿用旧的 `widthIn(max = 300.dp)`：300 在两端都不对。393dp 的手机上消息区
 * 内容宽是 361dp，82% = 296dp —— 300 比它**还宽**，气泡能一直贴到列表内边距；
 * 1100dp 的桌面窗里聊天栏 735dp、消息区内容宽 703dp，原型要 62% ≈ 436dp，
 * 300 又把气泡压成一条窄柱。
 * 原型没有下限兜底，所以这里也不写 `minOf(300.dp, …)` 之类的取小：那只是把两个错误
 * 叠在一起，宽屏上仍然是 300。
 *
 * 与原型的一处已知偏差：原型的 `max-width` 挂在 `.msg`（整行）上，而那一行还含
 * `:929 .msg .mav` 的 26px 头像和 8px 的 `gap`；本仓 `MessageBubble` 目前不画头像，
 * 所以比例直接作用在气泡本体上，会比原型的气泡宽出大约一个头像。等消息行加上头像时，
 * 把头像宽从 [availableWidth] 里扣掉即可对齐。
 */
internal fun Modifier.bubbleMaxWidth(availableWidth: Dp, fraction: Float): Modifier =
    widthIn(max = availableWidth * fraction)
