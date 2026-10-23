// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.protocol

import kotlinx.serialization.Serializable

/**
 * 网段同步条目元数据（对齐网段同步规范 `ScanRangeSummary`）。
 */
@Serializable
data class ScanRangeSummary(
    val cidr: String,
    val addedAt: Long,
)

/**
 * `scan-ranges` 报文载荷（对齐网段报文规范 `ScanRangesPayload`）。
 */
@Serializable
data class ScanRangesPayload(
    val ranges: List<ScanRangeSummary>,
)
