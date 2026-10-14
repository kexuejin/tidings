// SPDX-License-Identifier: GPL-3.0-only

package app.relay.platform

import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitHour
import platform.Foundation.NSDate

/**
 * iOS 侧走 `NSCalendar`：它认系统时区、用户手动改过的时区以及夏令时，
 * 比自己在 `NSDate` 上加减偏移可靠。
 */
actual fun localHourOfDay(): Int {
    val components = NSCalendar.currentCalendar.components(
        unitFlags = NSCalendarUnitHour,
        fromDate = NSDate(),
    )
    return components.hour.toInt()
}
