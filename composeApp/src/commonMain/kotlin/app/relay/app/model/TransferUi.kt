// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app.model

import kotlinx.serialization.Serializable

/**
 * 文件传输记录（对齐茶话间，保留最近 30 条）。
 */
@Serializable
data class TransferRecordUi(
    val transferId: String,
    val name: String,
    val size: Long,
    val isIncoming: Boolean,
    val at: Long,
    val success: Boolean,
    val path: String? = null,
    val isFolder: Boolean = false,
)
