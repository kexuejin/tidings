// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 捎话 Tidings contributors

package com.kapp.tenon.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.kapp.tenon.domain.NoUiTexts
import com.kapp.tenon.domain.RelayController

/**
 * 把「领域层要的那几句话」装进控制器 —— **产品壳的唯一入口**。
 *
 * ## 为什么必须收成一个组合函数（真机抓的缺陷）
 *
 * 修复前这件事只写在 `App.kt` 的 `RelayApp` 里。而桌面宿主**从不经 `RelayApp`**
 * （它自己组合 `DesktopApp`；`ACCEPTANCE.md` 里早有这句），于是桌面的
 * `controller.uiTexts` 一直是 [NoUiTexts]：每个方法返回空串。
 *
 * 后果不是「少一句提示」那么轻 —— `broadcastAgentState` 有明确纪律「**空正文不发帧**」
 * （协议对群文本要求 `text` 非空）。于是「@ 了一个没人执行的 agent」这件事只落在本机，
 * 手机端一条都收不到：全群静默，而这正是 S4-4 要修的那个沉默。
 *
 * 真机现场（读数见 `design/runtime-evidence/` 下的 M5 验收矩阵）：
 * 桌面库里出现 `agentState=failed` 且 `text=""`，而手机库 `agentState` 行 **0 条**。
 * 根因就是这里。
 *
 * ## 为什么用 [DisposableEffect] 而不是别的
 *
 * 它必须在 `controller.start()` 之后立刻就位（启动卫生那条路径会马上读文案），
 * 并在离开组合时摘掉 —— 留一个指向已离开组合实现的桥，比没有桥更糟。
 * 摘掉后回落到 [NoUiTexts]，不崩溃。
 *
 * 两个产品壳（手机 `RelayApp` / 桌面 `DesktopApp`）都调这一个函数：文案从哪来、
 * 什么时候装上、什么时候摘掉，只有一份答案。
 */
@Composable
public fun InstallRelayUiTexts(controller: RelayController) {
    DisposableEffect(controller) {
        controller.uiTexts = ResourceUiTexts
        onDispose { controller.uiTexts = NoUiTexts }
    }
}
