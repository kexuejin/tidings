// SPDX-License-Identifier: GPL-3.0-only

package app.relay.platform

import java.util.Calendar

/**
 * 桌面（JVM）侧与 Android 同一实现：`Calendar` 已经是时区与夏令时都正确的那个 API。
 *
 * 刻意**不**复用 `DesktopUiPrefs` 那类桌面偏好：那读的是"用户设的偏好"，
 * 这里要的是"现在几点"，两件事不能混。
 */
actual fun localHourOfDay(): Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
