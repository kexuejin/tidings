// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.protocol

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 身份恢复 L2 两张帧的载荷校验（方案 §6.3）：
 *  · `recovery-req` 严格空对象 —— 多一个键整帧拒收；
 *  · `recovery-data` 帧级白名单 + 消息条目形状校验（缺 id / 超限都拒）；
 *  · 未知类型的「旧端静默」契约钉死：解码通过、`known=false`。
 */
class CodecRecoveryTest {

    private fun validData() = buildJsonObject {
        put("batch", 0)
        put("more", false)
        put("messages", kotlinx.serialization.json.JsonArray(listOf(buildJsonObject { put("id", "m-1") })))
    }

    @Test
    fun recoveryReqMustBeEmpty() {
        assertTrue(Codec.validatePayload(MsgTypes.RECOVERY_REQ, buildJsonObject { }))
        assertFalse(
            Codec.validatePayload(MsgTypes.RECOVERY_REQ, buildJsonObject { put("since", 1) }),
            "多一个键整帧拒收（与 group-text 白名单同一口径）",
        )
    }

    @Test
    fun recoveryDataAcceptsWellFormedAndRejectsBroken() {
        assertTrue(Codec.validatePayload(MsgTypes.RECOVERY_DATA, validData()))
        // 帧级：多一个键
        assertFalse(
            Codec.validatePayload(
                MsgTypes.RECOVERY_DATA,
                buildJsonObject {
                    put("batch", 0)
                    put("more", false)
                    put("messages", kotlinx.serialization.json.JsonArray(emptyList()))
                    put("extra", 1)
                },
            ),
        )
        // batch 必须是非负整数
        assertFalse(
            Codec.validatePayload(
                MsgTypes.RECOVERY_DATA,
                buildJsonObject {
                    put("batch", -1)
                    put("more", false)
                    put("messages", kotlinx.serialization.json.JsonArray(emptyList()))
                },
            ),
        )
        // 条目必须有非空 id
        assertFalse(
            Codec.validatePayload(
                MsgTypes.RECOVERY_DATA,
                buildJsonObject {
                    put("batch", 0)
                    put("more", false)
                    put("messages", kotlinx.serialization.json.JsonArray(listOf(buildJsonObject { put("id", "") })))
                },
            ),
        )
        // more 必须是布尔
        assertFalse(
            Codec.validatePayload(
                MsgTypes.RECOVERY_DATA,
                buildJsonObject {
                    put("batch", 0)
                    put("more", "yes")
                    put("messages", kotlinx.serialization.json.JsonArray(emptyList()))
                },
            ),
        )
    }

    @Test
    fun unknownTypeDecodesAsKnownFalse() {
        // 旧端静默契约的机制本体：未知类型解码通过但 known=false，
        // 上层（UDP 分发只认 RELIABLE 集）自然忽略 —— 恢复帧对旧版对端就是黑洞。
        val envelope = buildJsonObject {
            put("v", Protocol.VERSION)
            put("type", JsonPrimitive("some-future-type"))
            put("id", "e-1")
            put("from", "peer-a")
            put("ts", 1)
            put("payload", buildJsonObject { })
        }
        val decoded = Codec.decodeEnvelopeObject(envelope)
        assertTrue(decoded is DecodeResult.Ok, "未知类型必须解码通过")
        assertFalse((decoded as DecodeResult.Ok).known, "known=false 是旧端静默的机制")
    }
}
