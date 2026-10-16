// SPDX-License-Identifier: GPL-3.0-only

package app.relay.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

class ThinkingBlockTest {

    @Test
    fun elapsedSecondsFormattingIsStable() {
        assertEquals("0s", formatElapsedSeconds(0L))
        assertEquals("0.1s", formatElapsedSeconds(100L))
        assertEquals("0.5s", formatElapsedSeconds(500L))
        assertEquals("1s", formatElapsedSeconds(1000L))
        assertEquals("1.2s", formatElapsedSeconds(1200L))
        assertEquals("4.2s", formatElapsedSeconds(4200L))
        assertEquals("10s", formatElapsedSeconds(10000L))
        assertEquals("65.8s", formatElapsedSeconds(65800L))
    }
}
