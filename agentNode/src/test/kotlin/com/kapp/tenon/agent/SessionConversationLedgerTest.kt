// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** F4（C3）硬保证台账的钉子。反向验证：删掉 record 里的落盘调用 ⇒ 重启用例红。 */
class SessionConversationLedgerTest {

    private fun tmp() = File.createTempFile("relay-sessions-", ".json").apply { delete() }

    @Test
    fun recordSurvivesANewInstance() {
        val f = tmp()
        SessionConversationLedger(f).record("sess-1", "group:g1")
        assertEquals("group:g1", SessionConversationLedger(f).conversationOf("sess-1"))
    }

    @Test
    fun unknownSessionIsFailClosed() {
        val ledger = SessionConversationLedger(tmp())
        assertNull(ledger.conversationOf("nope"), "归属未知 = null，调用方不进候选")
        ledger.record("", "group:g1")
        assertFalse(File(ledger.toString()).exists())
    }

    @Test
    fun corruptFileIsWholeFileEmpty() {
        val f = tmp().apply { writeText("{ not json") }
        assertNull(SessionConversationLedger(f).conversationOf("sess-1"))
    }

    @Test
    fun unknownSchemaVersionIsWholeFileEmpty() {
        val f = tmp().apply { writeText("""{"schemaVersion":99,"bindings":[]}""") }
        assertNull(SessionConversationLedger(f).conversationOf("sess-1"))
    }

    @Test
    fun blankArgumentsChangeNothing() {
        val f = tmp()
        val ledger = SessionConversationLedger(f)
        assertFalse(ledger.record("  ", "group:g1"))
        assertFalse(ledger.record("s1", " "))
        assertTrue(ledger.record("s1", "group:g1"))
        assertTrue(f.isFile, "合法写入要落盘")
    }
}
