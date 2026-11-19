// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

/**
 * 新建/编辑员工表单的**运行时下拉选项**（方案 §3.3）——**纯函数**，只有纯函数测得动：
 * `DropdownMenu` 走 `Popup`，Layoutlib 不组合（展开态没有快照断言可言）。
 *
 * @param roster 盘面全集名册（端口投影；null = 没有名册事实 ⇒ [emptyList]，表单画「先接一个运行时」）。
 * @return 只含**在位**的运行时（未装的不列 —— 装了才建，未装的去执行配置看安装说明），
 *   保持名册序（稳定、可扫读）。
 *
 * 登录态**不参与筛选**：「先建人、后登录」是本方案的宗旨，未登录的照样可选；
 * 状态词由 [driverOptionStateOf] 单独算（没检测过就沉默，不画词）。
 */
fun employeeDriverOptions(
    roster: List<DriverRosterEntryUi>?,
): List<DriverRosterEntryUi> {
    if (roster == null) return emptyList()
    return roster.filter { it.installed }
}

/**
 * 下拉项的状态词档（方案 §3.3）：可用（绿）/ 需登录（黄）/ 检测不到（灰字，**绝不写「未登录」**）。
 * null = 没检测过 ⇒ UI 不画状态词（沉默一档，不是「可用」也不是「需登录」）。
 */
enum class DriverOptionStateUi { AVAILABLE, NEEDS_LOGIN, UNKNOWN }

fun driverOptionStateOf(
    key: String,
    authStates: Map<String, DriverAuthCheckUi>?,
): DriverOptionStateUi? {
    val check = authStates?.get(key) ?: return null
    return when (check.state) {
        DriverAuthStateUi.AUTHENTICATED -> DriverOptionStateUi.AVAILABLE
        DriverAuthStateUi.NOT_AUTHENTICATED -> DriverOptionStateUi.NEEDS_LOGIN
        DriverAuthStateUi.UNKNOWN -> DriverOptionStateUi.UNKNOWN
    }
}
