// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CronValidationTest {

    @Test
    fun testValidCronExpressions() {
        assertNull(CronValidation.validate("0 9 * * *"))
        assertNull(CronValidation.validate("*/5 * * * *"))
        assertNull(CronValidation.validate("0 0 1 1 *"))
        assertNull(CronValidation.validate("15,45 10-18 * 1-6 1-5"))
        assertNull(CronValidation.validate("0 0 * * 0,7"))
    }

    @Test
    fun testInvalidFieldCount() {
        assertNotNull(CronValidation.validate(""))
        assertNotNull(CronValidation.validate("* * * *"))
        assertNotNull(CronValidation.validate("* * * * * *"))
    }

    @Test
    fun testInvalidRangesAndValues() {
        assertNotNull(CronValidation.validate("60 * * * *")) // minute 60 > 59
        assertNotNull(CronValidation.validate("* 24 * * *")) // hour 24 > 23
        assertNotNull(CronValidation.validate("* * 32 * *")) // day 32 > 31
        assertNotNull(CronValidation.validate("* * * 13 *")) // month 13 > 12
        assertNotNull(CronValidation.validate("* * * * 8"))  // day-of-week 8 > 7
        assertNotNull(CronValidation.validate("10-5 * * * *")) // descending
        assertNotNull(CronValidation.validate("*/0 * * * *")) // step 0
        assertNotNull(CronValidation.validate("abc * * * *")) // non-numeric
    }
}
