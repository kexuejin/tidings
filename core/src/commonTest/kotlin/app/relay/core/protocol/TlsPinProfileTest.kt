// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * task-38：档案里新增的**可选** `tlsPin`（配对时把本机 pin 带给对端）。
 *
 * 三条契约：
 *  1. 带 pin ⇒ 编出来有 `profile.tlsPin`，且校验通过；
 *  2. **不带** pin（旧端）⇒ 照旧合法 —— 兼容性的那一半；
 *  3. 形状不对（超长）⇒ 整帧拒（坏值绝不许静默变成"信任的 pin"）。
 */
class TlsPinProfileTest {

    private fun profile(tlsPin: String? = null) = Profile(
        nodeId = "peer",
        nick = "n",
        company = "",
        dept = "",
        team = "",
        avatar = 0,
        profileRev = 0,
        host = "192.168.2.140",
        platform = "linux",
        tcpPort = 17879,
        ver = "relay-0.1.0",
        caps = listOf(MsgTypes.CAP_LINK),
        tlsPin = tlsPin,
    )

    @Test
    fun thePairingPinRidesAlongInTheProfileAndValidates() {
        val pin = "sha256:" + "a".repeat(64)
        val json = Codec.profilePayload(profile(pin)) as JsonObject
        val inner = json["profile"] as? JsonObject ?: error("缺 profile 对象：$json")
        assertEquals(pin, (inner["tlsPin"] as? JsonPrimitive)?.content, "pin 必须原样出现在档案里")
        assertTrue(CodecProfile.validateProfilePayload(json), "带合法 tlsPin 的档案必须通过")
    }

    @Test
    fun aProfileWithoutThePinStaysValidSoOldPhonesKeepWorking() {
        val json = Codec.profilePayload(profile(null)) as JsonObject
        val inner = json["profile"] as JsonObject
        assertFalse(inner.containsKey("tlsPin"), "没传就不该出现这个键（旧端逐字段校验，不整帧白名单）")
        assertTrue(CodecProfile.validateProfilePayload(json), "旧档案必须继续合法 ⇒ 主机退回 TOFU")
    }

    @Test
    fun anOverlongPinIsRejectedInsteadOfBeingSilentlyTrusted() {
        val json = Codec.profilePayload(profile("x".repeat(Limits.TLS_PIN + 1))) as JsonObject
        assertFalse(CodecProfile.validateProfilePayload(json), "超长 pin 必须整帧拒")
    }
}
