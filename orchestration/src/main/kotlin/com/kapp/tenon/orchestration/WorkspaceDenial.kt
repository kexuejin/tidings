// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

/**
 * 「派单落不到目录」的两种成因 —— 界面上是两条出路，所以必须分开：
 * 群里还没选项目目录 ⇒ 让用户去选；选了但本机没被授权 ⇒ 让用户去授权。
 * 合成一个 `NO_DIRECTORY` 时，第二种情况的卡会叫用户去选一个**已经选过**的目录。
 */
internal enum class WorkspaceDenial { NONE, NO_DIRECTORY, DIR_NOT_ALLOWED }

/**
 * 判据本体（纯函数，便于在没有宿主的情况下钉住）。
 *
 * @param path 解析出来的目录路径；null = 一条判据都没命中。
 * @param allowed 白名单结论：`false` = 明确不许（[WorkspaceDenial.DIR_NOT_ALLOWED]）；
 *   `null` = 没装配白名单（老装置），此时**不放宽也不加严**，按路径本身判。
 */
internal fun workspaceDenialOf(path: String?, allowed: Boolean?): WorkspaceDenial = when {
    path == null -> WorkspaceDenial.NO_DIRECTORY
    allowed == false -> WorkspaceDenial.DIR_NOT_ALLOWED
    else -> WorkspaceDenial.NONE
}
