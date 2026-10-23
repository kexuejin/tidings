// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

/**
 * 详情行要显示的传输编号；内部临时键返回 null（整行不画）。
 *
 * ## 为什么它住在领域侧
 *
 * 乐观插入会在报价返回前把卡片挂在一个**内部临时键**上（见
 * `RelayControllerTransfersComponent` 的 `pendingTransferKey`）。那个键是本端实现细节，
 * 不是用户认得出的传输编号 —— 画进详情行只会让人以为出了什么怪编号。
 *
 * 「哪个是真的 transferId」这个判据与生产那个键的地方必须同侧：分到两个模块里，
 * 改前缀时必然漏改一处，而症状是**临时键泄漏到界面上**（看起来像一个怪编号），
 * 不会报错。
 *
 * 它只做字符串判断，与像素无关；UI 侧决定「这一行画不画、画多宽」。
 */
fun transferIdDetailLabel(transferId: String?): String? {
    val id = transferId?.takeIf { it.isNotBlank() } ?: return null
    if (id.startsWith(INTERNAL_TRANSFER_KEY_PREFIX)) return null
    return id.take(12)
}

/** 乐观插入用的内部临时传输键前缀（见 [transferIdDetailLabel]）。 */
const val INTERNAL_TRANSFER_KEY_PREFIX: String = "pending-"
