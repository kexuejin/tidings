// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * 平台接缝：把「已落盘文件行 → 拖到外部应用」这个能力交给宿主提供。
 *
 * 拖出窗口依赖 java.awt.dnd，只能活在 desktopApp；而文件行本身渲染在 commonMain
 * 的 [DesktopFilesPane] 里。这里用 CompositionLocal 把实现注入进去，
 * commonMain 默认什么都不做，移动端与截图门禁都不会碰到 AWT。
 */
val LocalFileRowDrag: ProvidableCompositionLocal<@Composable (List<String>) -> Modifier> =
    staticCompositionLocalOf { { Modifier } }

/**
 * 让文件卡片成为**拖拽源**：按住卡片拖动，把 [savedPaths] 指向的文件拖到
 * Finder / 资源管理器 / 任何接受文件拖放的地方。
 *
 * 没有可拖的文件时**不挂**任何手势 —— 一个什么都拖不出来的手柄比没有手柄更糟。
 */
@Composable
fun Modifier.desktopFileDragSource(savedPaths: List<String>): Modifier =
    if (savedPaths.isEmpty()) this else LocalFileRowDrag.current(savedPaths)
