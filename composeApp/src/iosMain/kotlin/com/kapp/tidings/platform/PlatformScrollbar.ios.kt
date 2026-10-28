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
    // iOS 原生拥有基于手势的滚动指示条，桌面滚动条在此无操作
}

@Composable
actual fun PlatformScrollbar(
    state: ScrollState,
    modifier: Modifier,
) {
    // iOS 原生拥有基于手势的滚动指示条，桌面滚动条在此无操作
}
