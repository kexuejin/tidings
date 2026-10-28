// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.platform

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 跨平台自适应 LazyList 垂直滚动条。
 * - 桌面 (JVM): 渲染带有微灰/微蓝圆角滑块的垂直滚动条，支持拖拽与滚轮位置反馈；
 * - 移动端 (Android/iOS): 空实现（依赖移动端系统原生的滑动指示器）。
 */
@Composable
expect fun PlatformLazyListScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
)

/**
 * 跨平台自适应普通可滚动容器 (ScrollState) 垂直滚动条。
 * - 桌面 (JVM): 渲染带有微灰/微蓝圆角滑块的垂直滚动条，支持拖拽与滚轮位置反馈；
 * - 移动端 (Android/iOS): 空实现。
 */
@Composable
expect fun PlatformScrollbar(
    state: ScrollState,
    modifier: Modifier = Modifier,
)
