// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui

import com.kapp.tidings.core.protocol.ScreenPhase
import com.kapp.tidings.core.protocol.ScreenRole
import com.kapp.tidings.domain.model.ScreenRecordUi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull

class ResourceUiTextsTest {

    @Test
    fun screenCardTextDoesNotRecursivelyOverflow() = runTest {
        val record = ScreenRecordUi(
            v = 1,
            role = ScreenRole.Viewer,
            phase = ScreenPhase.Active,
            requestedAt = 1000L,
            startedAt = 1000L,
        )
        // 确保不会因为自身递归导致栈溢出崩溃
        val text = ResourceUiTexts.screenCardText(record)
        assertNotNull(text)
    }
}
