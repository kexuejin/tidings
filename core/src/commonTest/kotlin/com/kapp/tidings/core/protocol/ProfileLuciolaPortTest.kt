// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.protocol

import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** luciolaPort 是可选键:新端写它,老端(档案里没有)行为不变。 */
class ProfileLuciolaPortTest {

    private fun base() = Profile(
        nodeId = "11111111-2222-4333-8444-555555555555", nick = "n", company = "", dept = "", team = "",
        avatar = 1, profileRev = 1, host = "h", platform = "linux", tcpPort = 17879,
        ver = "1", caps = emptyList(),
    )

    @Test
    fun luciolaPortRoundTripsAndIsOmittedWhenNull() {
        val withPort = base().copy(luciolaPort = 17878)
        val text = Codec.wireJson.encodeToString(Profile.serializer(), withPort)
        assertTrue(text.contains("\"luciolaPort\":17878"), text)
        val decoded = Codec.wireJson.decodeFromString(Profile.serializer(), text)
        assertEquals(17878, decoded.luciolaPort)

        val legacy = base()
        val legacyText = Codec.wireJson.encodeToString(Profile.serializer(), legacy)
        assertFalse(legacyText.contains("luciolaPort"), "默认值不落盘:老节点档案字节不变")
        assertNull(Codec.wireJson.decodeFromString(Profile.serializer(), legacyText).luciolaPort)
    }

    @Test
    fun oldProfileWithoutKeyStillParses() {
        // 模拟老版本发来的档案(显式构造一个没有 luciolaPort 的 JSON)。
        val json = Codec.wireJson.encodeToString(Profile.serializer(), base())
        val obj = Codec.wireJson.parseToJsonElement(json).jsonObject
        assertFalse(obj.containsKey("luciolaPort"))
        val decoded = Codec.wireJson.decodeFromString(Profile.serializer(), json)
        assertNull(decoded.luciolaPort)
    }
}
