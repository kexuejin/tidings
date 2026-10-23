// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.platform

/**
 * "把已经落盘的文件交给别的应用"这个能力。
 *
 * 存在的理由是一次实打实的缺口：收到的文件落在 `filesDir/relay/downloads`（应用内部目录），
 * 文件管理器看不见，而应用里除了那句"已存到 <路径>"之外**没有任何出口** —— 用户拿到了
 * 路径却打不开、也分享不出去。所以这里只回答两个问题：能不能打开它、能不能把它分享出去。
 *
 * **契约**：
 * - [path] 是绝对文件系统路径，来自 `FileCardUi.savedPaths`（见 `RelayUiState.kt`）。
 * - 实现**不许抛异常**：用户点一下按钮不该把应用掀掉。做不到就什么都没发生，并自己留痕
 *   （Android 侧写 `Log.w`）。
 * - 平台给不出这个能力时，`AppPlatform.receivedFileActions` 为 `null` —— 界面据此**不画**
 *   这两个按钮（`null` 表示"这台设备没有这条出口"，而不是"暂时失败"）。
 */
public interface ReceivedFileActions {

    /** 用系统里能读懂这个类型的应用打开 [path]。 */
    public fun open(path: String)

    /** 把 [path] 交给系统的分享面板，由用户挑接收方。 */
    public fun share(path: String)
}
