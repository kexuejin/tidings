// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.platform

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidPickerBridgeTest {

    @Test
    fun launchSingleFileDeliversUri() = runTest {
        val bridge = AndroidPickerBridge()
        var launchedMime: Array<String>? = null
        bridge.attach { mimeTypes -> launchedMime = mimeTypes }

        val pickJob = async { bridge.launch(arrayOf("image/*")) }
        testScheduler.runCurrent()
        assertEquals(listOf("image/*"), launchedMime?.toList())

        bridge.deliver("content://media/external/images/media/42")
        val result = pickJob.await()
        assertEquals("content://media/external/images/media/42", result)
    }

    @Test
    fun launchMultipleFilesDeliversList() = runTest {
        val bridge = AndroidPickerBridge()
        var launchedMime: Array<String>? = null
        bridge.attachMultiple { mimeTypes -> launchedMime = mimeTypes }

        val pickJob = async { bridge.launchMultiple(arrayOf("*/*")) }
        testScheduler.runCurrent()
        assertEquals(listOf("*/*"), launchedMime?.toList())

        bridge.deliverMultiple(listOf("content://file/1", "content://file/2"))
        val result = pickJob.await()
        assertEquals(listOf("content://file/1", "content://file/2"), result)
    }

    @Test
    fun launchMultipleFallsBackToSingleWhenMultipleNotAttached() = runTest {
        val bridge = AndroidPickerBridge()
        bridge.attach { bridge.deliver("content://file/single") }

        val result = bridge.launchMultiple(arrayOf("*/*"))
        assertEquals(listOf("content://file/single"), result)
    }

    @Test
    fun detachCompletesPendingCalls() = runTest {
        val bridge = AndroidPickerBridge()
        bridge.attachMultiple { /* do not deliver */ }

        val pickJob = async { bridge.launchMultiple(arrayOf("*/*")) }
        testScheduler.runCurrent()
        bridge.detach()
        val result = pickJob.await()
        assertTrue(result.isEmpty())
    }

    @Test
    fun concurrentLaunchReturnsNullOrEmpty() = runTest {
        val bridge = AndroidPickerBridge()
        bridge.attach { /* wait */ }

        val first = async { bridge.launch(arrayOf("*/*")) }
        testScheduler.runCurrent()
        // Second call while first is pending
        val secondResult = bridge.launch(arrayOf("*/*"))
        assertNull(secondResult)

        bridge.deliver(null)
        first.await()
    }
}
