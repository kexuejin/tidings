// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

/**
 * 一个可以授权执行的设备 —— 「谁能让这个 agent 干活」那一屏的一行。
 *
 * ## 为什么它属于领域侧
 *
 * 这些字段不是 UI 的自由发挥，而是**从台账算出来的事实**：[execDeviceRows] 拿
 * 授权记录（`scope=ALL` 与 `scope=AGENT`）与群成员交叉出这张表，界面只负责画成
 * 勾选列表。判据是"谁算出它"，不是"谁画它" —— 所以它跟着算它的那一侧。
 *
 * ## 两个维度必须分开
 *
 * - [trusted]：「我批准过这台设备」（来自 `scope=ALL` 的授权记录）；
 * - [checked]：「它现在有权让**这个** agent 干活」。
 *
 * 一台可信设备可以不勾（它能看，但不许跑命令）；一台不可信设备也可以勾（单点授权）。
 * 把两者合成一个布尔，这个功能的全部意义就消失了。
 */
public data class ExecDeviceRow(
    val nodeId: String,
    /** 展示名（昵称或 nodeId 前 8 位，由调用方决定）。 */
    val label: String,
    /** 是不是「我批准过的可信设备」（来自 `scope=ALL` 的授权记录）。 */
    val trusted: Boolean,
    /** 当前是否已授权这个 agent。 */
    val checked: Boolean,
    /** 这台设备是不是我自己（本机用户，永远勾上且不可取消）。 */
    val isSelf: Boolean,
)
