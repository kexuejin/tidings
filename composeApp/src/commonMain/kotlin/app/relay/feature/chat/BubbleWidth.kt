// SPDX-License-Identifier: GPL-3.0-only

package app.relay.feature.chat

import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 气泡宽度上限：消息区**可用宽度**的一个比例，不是一个写死的像素值。
 *
 * 原型里这条上限就是比例 —— `prototype/relay.html:1083 .msg{max-width:var(--msg-max)}`，
 * `--msg-max` 在 `:56` 是 `82%`，桌面壳在 `:464 .win{--msg-max:62%}` 把它覆盖成 `62%`。
 * 百分比的包含块是 `.msgs` 的内容盒：`:920 .msgs{padding:12px 14px 6px}` 与
 * `:921 .win .msgs{padding:14px 18px 6px}` 说明这个内容盒已经把消息区自己的横向内边距
 * 扣掉了。所以 [availableWidth] 要传**消息区内容宽**，不是整幅窗口宽 —— 在 `ChatScreen`
 * 里它就是列表项宽 = LazyColumn 宽 − 2×16dp 的 `contentPadding`，
 * 由气泡外面那层 `BoxWithConstraints` 实测得到（见 `ChatScreen` 里气泡外那层
 * `BoxWithConstraints` —— 具体函数名是 `MessageBubble` 之外那一层的调用点）。
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
 * ## 量纲：这里的 [availableWidth] 是**气泡那一侧的可用宽**，不是整行
 *
 * 调用点是 `ChatBubbles` 里 `Row` 内部的 `BoxWithConstraints`（气泡与头像并排，头像在
 * `Row` 里、`BoxWithConstraints` 只包气泡那一侧），所以 `maxWidth` **已经不含头像与间距**。
 *
 * 这一点踩过一次：我曾按「原型 `.msg` 的 82% 含头像」在这里再扣一次 26+8dp，结果
 * 393dp 手机上气泡只有约 262dp（占屏 67%），agent 的长回复被挤成一条窄柱 ——
 * 用户原话「看的消息有点窄」。**同一个扣除做两次**，比不扣更糟。
 */
internal fun Modifier.bubbleMaxWidth(availableWidth: Dp, fraction: Float): Modifier =
    widthIn(max = availableWidth * fraction)

/**
 * **长文本**的气泡上限：按比例算出来的宽度放不下这段字时，放开到接近满宽。
 *
 * ## 为什么不是直接把 82% 调大
 *
 * 算过一遍：原型 `.msg{max-width:82%}` 是**整行**口径，扣掉 26px 头像 + 8px gap 之后，
 * 气泡本体在 393dp 屏上约 265dp = **67.5% 屏宽**；本仓同一口径算出来是 271dp = 69.1%。
 * 也就是说**代码一直与原型一致** —— 用户觉得"窄"，是这条比例在长文本上本身就窄。
 *
 * 所以不调那个数字（调了就是偏离原型，且短消息也会跟着变胖、失去右对齐的层次），
 * 而是按**内容**分档：短消息维持原型比例，长消息才放开。判据用字数 —— 一段 80 字以上的
 * 中文在 271dp 里要折 6~7 行，读起来确实费劲；此时多给宽度不会破坏"谁在说话"的层次。
 *
 * @param text 这条消息的正文（已含 reasoning 时用 answer 部分）。
 */
internal fun Modifier.bubbleMaxWidthForText(
    availableWidth: Dp,
    fraction: Float,
    text: String,
): Modifier {
    val base = availableWidth * fraction
    if (text.length < LONG_TEXT_CHARS) return widthIn(max = base)
    // 长文本：最多放到「气泡侧可用宽」的 96%（留一点，不然会贴住列表右边缘、
    // 和滚动条/内边距挤在一起）。
    return widthIn(max = maxOf(base, availableWidth * 0.96f))
}

/** 超过这个字数就按「长文本」处理（中文 80 字 ≈ 在 271dp 里折 6~7 行）。 */
private const val LONG_TEXT_CHARS: Int = 80
