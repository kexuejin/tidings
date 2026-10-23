// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.core.transport.relayTlsHandshakeSelfTest

/**
 * iOS 的 TLS 自检入口（task-26 ①），**只为让 Swift 调得到**。
 *
 * 为什么不直接在 Swift 里调 core 的那个函数：`composeApp` 对 core 是 `implementation`，
 * framework 里**没有** `export(project(":core"))` —— core 的公开 API 不在 `RelayShared` 的
 * 导出面上（Lead 裁决：不加 export，保持导出面最小）。所以在这里放一个**一行包装**：
 * 它随 `composeApp` 一起被导出，Swift 侧调 `RelayTlsSelfTestKt.relayTlsSelfTest()`。
 *
 * 触发方式（不动正常 UI、不加可见按钮）：
 * ```
 * xcrun simctl launch <device> <bundle-id> --relay-tls-selftest
 * ```
 * 报告会打到 stdout（配 `--console` 或 `simctl spawn log`），最后一行是 `RESULT=PASS|FAIL`。
 */
fun relayTlsSelfTest(): String = relayTlsHandshakeSelfTest()
