// SPDX-License-Identifier: GPL-3.0-only

package app.relay.ui

/**
 * 给 `ErrorState` 用的一行错误。
 *
 * 为什么只有两个字段：早先这里还有 `retryable: Boolean` 与 `cause: String?`，
 * 但全仓没有任何一处读它们（唯一的构造点传了 `retryable = true` 就再没人看）。
 * 「能不能重试」这件事**已经由调用方显式给出出口**（`ErrorState(actionLabel, onAction)`），
 * 再多一个旗标就是第二个事实来源 —— 界面照着旗标画、调用方照着出口画，两边迟早不一致。
 * `cause` 同理：`message` 已经是要给人看的那句话。
 */
data class AppError(
    val code: String,
    val message: String,
)
