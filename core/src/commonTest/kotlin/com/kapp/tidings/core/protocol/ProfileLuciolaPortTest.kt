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
    /**
     * 两个可选端口键是拨号目标，必须与 tcpPort 同一条范围纪律。
     *
     * 反向验证：把 validateProfile 里那两行注释掉，下面两个用例会红。
     * 不校验的后果不是「拒帧」，而是越界值进 registry 后被 toUShort() 截断 ——
     * 消息发不出去、也没有任何错误（§3 静默丢失）。
     */
    @Test
    fun outOfRangeLuciolaPortIsRejectedByProfileValidation() {
        for (bad in listOf("0", "-1", "65536", "70000", "\"17878\"")) {
            val json = profileJsonWith("luciolaPort", bad)
            assertFalse(
                CodecProfile.validateProfile(Codec.wireJson.parseToJsonElement(json)),
                "luciolaPort=$bad 必须判非法（越界/非整数都不能进拨号目标）",
            )
        }
    }

    @Test
    fun outOfRangeScreenPortIsRejectedByProfileValidation() {
        for (bad in listOf("0", "-1", "65536", "\"17888\"")) {
            val json = profileJsonWith("screenPort", bad)
            assertFalse(
                CodecProfile.validateProfile(Codec.wireJson.parseToJsonElement(json)),
                "screenPort=$bad 必须判非法",
            )
        }
    }

    @Test
    fun validOptionalPortsStillPassValidation() {
        val withPorts = profileJsonWith("luciolaPort", "17878").dropLast(1) + ",\"screenPort\":17888}"
        assertTrue(
            CodecProfile.validateProfile(Codec.wireJson.parseToJsonElement(withPorts)),
            "合法端口不许被误拒（否则两端永远配不上）",
        )
        // 老端档案（两个键都没有）也必须照旧通过。
        assertTrue(CodecProfile.validateProfile(Codec.wireJson.parseToJsonElement(profileJsonWith(null, null))))
    }

    /** 在合法档案 JSON 里追加一个可选键（value 是 JSON 字面量原文）。 */
    private fun profileJsonWith(key: String?, value: String?): String {
        val base = Codec.wireJson.encodeToString(Profile.serializer(), base())
        if (key == null) return base
        return base.dropLast(1) + ",\"" + key + "\":" + value + "}"
    }
}
