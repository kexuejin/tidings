// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.platform

import java.util.Calendar

/**
 * Android 侧直接用 `java.util.Calendar`：它读的是系统时区设置与夏令时规则，
 * 不需要引入 `java.time`（那在低 minSdk 上要脱糖）。
 */
actual fun localHourOfDay(): Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
