// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.platform

/**
 * 本机当前时区的小时数（0..23）。
 *
 * 为什么单独开一个平台接口：core 只有 `nowMillis()`（UTC 毫秒），而"静默时段 22:00–08:00"
 * 是**用户当地时间**的概念。消息气泡上那个 `formatClock` 用的是"按 UTC+8 粗算"，
 * 那个近似放在显示上顶多让时间戳偏几小时，放在静默时段上却会把通知**漏在错误的时间**：
 * 静默时段存在的意义是少打扰人，判错时区反而变成"该响的时候不响"。
 *
 * 所以这里取系统的真实时区（含夏令时与用户手动改过的时区）。
 */
expect fun localHourOfDay(): Int
