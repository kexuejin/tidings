// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 派单 preflight 四问的钉子（D7/§4.3）。
 *
 * 反向验证：把 [DispatchPreflight.isDispatchable] 退回「只看前三问」或把
 * `directoryResolved == null` 当 ok ⇒ 未解析用例红（那正是「@ 了没反应」的显示面）。
 */
class DispatchPreflightTest {

    @Test
    fun `all green is dispatchable`() {
        val answers = DispatchPreflight.evaluate(
            hostOnline = true, agentPresent = true, directoryResolved = true, authorized = true,
        )
        assertTrue(DispatchPreflight.isDispatchable(answers))
        assertEquals(4, answers.size)
    }

    @Test
    fun `unresolved directory blocks even when unknown`() {
        // unknown（群没设目录）≠ ok：未知不判红的是「授权」，不是「目录」。
        val unknown = DispatchPreflight.evaluate(
            hostOnline = true, agentPresent = true, directoryResolved = null, authorized = null,
        )
        assertFalse(DispatchPreflight.isDispatchable(unknown))
        assertTrue(unknown.first { it.key == DispatchPreflight.Key.DIRECTORY_RESOLVED }.unknown)
    }

    @Test
    fun `offline host shows red but sending is not blocked here`() {
        // 拦截不在这里做（store-and-forward 是既有语义）：四问只是显示面。
        val answers = DispatchPreflight.evaluate(
            hostOnline = false, agentPresent = true, directoryResolved = true, authorized = null,
        )
        assertFalse(DispatchPreflight.isDispatchable(answers))
        assertTrue(answers.first { it.key == DispatchPreflight.Key.AUTHORIZED }.unknown)
    }

    @Test
    fun `unknown authorization is honest -- not a fake green`() {
        val answers = DispatchPreflight.evaluate(
            hostOnline = true, agentPresent = true, directoryResolved = true, authorized = null,
        )
        val authorized = answers.first { it.key == DispatchPreflight.Key.AUTHORIZED }
        assertTrue(authorized.ok)
        assertTrue(authorized.unknown)
    }
}
