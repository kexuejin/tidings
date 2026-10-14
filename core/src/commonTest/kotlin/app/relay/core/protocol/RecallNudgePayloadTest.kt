// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `recall` / `nudge` 出站载荷的**线上形状**。
 *
 * 这两个 kind 在 TS 侧没有 golden vector（`GOLDEN_VECTORS` 里只有 text / group-text），
 * 所以这里的依据是协议文档 §8 的 `msg.payload` 字段表：
 *  - `nudge`：**只有 `kind`**（「payload 除 `kind` 外不带正文、群字段或文件引用」）；
 *  - `recall`：`kind` + `targetId`，群聊再加 `groupId`/`groupRev`，且两个**成对**。
 *
 * 断言写成字面量 JSON 而不是"字段相等"：键序也是契约的一部分（TS 侧 `JSON.stringify`
 * 的键序就是属性插入序，`neg-group-text-extra-field` 那种严格白名单就是这么比的）。
 */
class RecallNudgePayloadTest {

    private companion object {
        const val TARGET = "33333333-3333-4333-8333-333333333333"
        const val FROM = "11111111-1111-4111-8111-111111111111"
    }

    @Test
    fun nudgeCarriesNothingButTheKind() {
        assertEquals("""{"kind":"nudge"}""", Codec.nudgePayload().toString())
    }

    @Test
    fun recallCarriesKindAndTargetOnly() {
        assertEquals(
            """{"kind":"recall","targetId":"$TARGET"}""",
            Codec.recallPayload(TARGET).toString(),
        )
    }

    @Test
    fun groupRecallCarriesTheGroupRef() {
        assertEquals(
            """{"kind":"recall","targetId":"$TARGET","groupId":"grp-1","groupRev":12}""",
            Codec.recallPayload(TARGET, groupId = "grp-1", groupRev = 12).toString(),
        )
    }

    @Test
    fun halfAGroupRefIsRefusedInsteadOfSent() {
        // 只给 groupId 的载荷连我们自己的入站校验（validateGroupRef）都过不去，
        // 与其把它发出去让对方整帧丢弃，不如在这里就炸。
        assertFailsWith<IllegalArgumentException> { Codec.recallPayload(TARGET, groupId = "grp-1") }
        assertFailsWith<IllegalArgumentException> { Codec.recallPayload(TARGET, groupRev = 3) }
    }

    /**
     * 自己发出去的形状，自己的入站校验必须收得下 —— 出站构造器与入站白名单是两处独立代码，
     * 一边改了另一边没跟上的话，症状是"对方撤回不了"这种最难查的静默失败。
     */
    @Test
    fun whatWeEmitIsWhatWeAccept() {
        assertDecodes(Codec.nudgePayload())
        assertDecodes(Codec.recallPayload(TARGET))
        assertDecodes(Codec.recallPayload(TARGET, groupId = "grp-1", groupRev = 12))
    }

    private fun assertDecodes(payload: kotlinx.serialization.json.JsonElement) {
        val json = Codec.encode(
            makeEnvelope(MsgTypes.MSG, FROM, payload, id = TARGET, ts = 1_700_000_000_000L),
        )
        val decoded = Codec.decode(json)
        if (decoded !is DecodeResult.Ok) {
            fail("自己发出的载荷被自己拒了：$json → ${(decoded as DecodeResult.Rejected).reason}")
        }
        assertTrue(decoded.known, "msg 是已知类型")
    }
}
