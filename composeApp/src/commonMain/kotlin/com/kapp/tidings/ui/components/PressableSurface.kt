// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.background

/**
 * 可按压表面 —— 本仓**唯一的按压反馈原语**。
 *
 * ## 为什么要有它
 *
 * 在它之前，本仓**没有任何按压反馈**：`graphicsLayer` / `animateFloatAsState` 全仓 0 处，
 * `indication =` 只有 4 处（其中 2 处还是 `indication = null`）。也就是说，
 * 用户按下去除了 Material3 的水波纹之外没有任何形变 —— 而在触屏上，
 * **"按了没反应"与"按了没生效"是分不出来的**。
 *
 * ## 为什么用缩放而不是水波纹
 *
 * 参考实现（茶话间）用的是 `transform: scale(0.96)` 一类按下形变，
 * 不是涟漪：涟漪是"点了一下"的一次性动画，而缩放表达的是"**现在**按着"这个持续状态。
 * 所以这里 `indication = null`（关掉水波纹），只用缩放。
 *
 * ## 默认值与它的来历
 *
 * [PRESS_SCALE] = 0.97 落在 0.96–0.988 这一档：低于 0.96 在大卡片上会"缩得太狠"，
 * 高于 0.988 在深色主题下几乎看不出来。
 *
 * ## 用法
 *
 * ```kotlin
 * PressableSurface(onClick = { open() }, shape = MaterialTheme.shapes.medium) {
 *     Row { ... }
 * }
 * ```
 *
 * 不要在调用点自己写 `Modifier.clickable` —— 那样按压反馈又没了。
 * 这条纪律由 `:uxFeedbackGate` 守（待建，见主计划 G-0.3）。
 */
object PressFeedback {
    /** 按下时的缩放比。 */
    const val PRESS_SCALE: Float = 0.97f

    /** 按下与回弹的动画时长（毫秒）。够快才跟手，够慢才看得见。 */
    const val DURATION_MS: Int = 90
}

/**
 * 可按压表面。
 *
 * @param onClick 点击动作。
 * @param modifier 外部修饰。
 * @param enabled 关掉之后既不缩放也不响应点击。
 * @param shape 裁剪形状与底色形状，默认 [MaterialTheme.shapes] 的 medium（控件档 12dp）。
 * @param color 底色；[Color.Unspecified] 表示**不画底色**（内容自己带）。
 * @param pressScale 按下缩放比，默认 [PressFeedback.PRESS_SCALE]。
 * @param haptic 是否在按下时给一次轻触反馈。默认 false —— 触感是**稀缺资源**，
 *   处处都震等于没有反馈；只在"结果不可逆"或"离开当前屏"的动作上打开。
 */
@Composable
fun PressableSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.medium,
    color: Color = Color.Unspecified,
    pressScale: Float = PressFeedback.PRESS_SCALE,
    haptic: Boolean = false,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val active = pressed && enabled

    val scale by animateFloatAsState(
        targetValue = if (active) pressScale else 1f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = PressFeedback.DURATION_MS),
        label = "pressScale",
    )

    if (haptic) {
        val feedback = LocalHapticFeedback.current
        LaunchedEffect(active) {
            if (active) feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .then(if (color == Color.Unspecified) Modifier else Modifier.background(color, shape))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
    ) {
        content()
    }
}
