// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.platform

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun PlatformLazyListScrollbar(
    state: LazyListState,
    modifier: Modifier,
) {
    // 移动端系统原生提供触摸滚动瞬态指示，无需渲染桌面型滚动条
}

@Composable
actual fun PlatformScrollbar(
    state: ScrollState,
    modifier: Modifier,
) {
    // 移动端系统原生提供触摸滚动瞬态指示，无需渲染桌面型滚动条
}
