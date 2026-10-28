// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.platform

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FilePickerContractTest {

    @Test
    fun pickMultipleDefaultImplementationFallsBackToSinglePick() = runTest {
        val picker = object : FilePicker {
            override suspend fun pick(): PickedFile =
                PickedFile(name = "test.txt", sizeBytes = 123L, path = "/tmp/test.txt")
        }

        val multiple = picker.pickMultiple()
        assertEquals(1, multiple.size)
        assertEquals("test.txt", multiple.first().name)
        assertEquals(123L, multiple.first().sizeBytes)
        assertEquals("/tmp/test.txt", multiple.first().path)
    }

    @Test
    fun pickMultipleDefaultImplementationReturnsEmptyWhenPickReturnsNull() = runTest {
        val picker = object : FilePicker {
            override suspend fun pick(): PickedFile? = null
        }

        val multiple = picker.pickMultiple()
        assertEquals(emptyList(), multiple)
    }

    @Test
    fun unavailableFilePickerThrowsOnPickMultiple() = runTest {
        assertFailsWith<PlatformCapabilityUnavailable> {
            UnavailableFilePicker.pickMultiple()
        }
    }
}
