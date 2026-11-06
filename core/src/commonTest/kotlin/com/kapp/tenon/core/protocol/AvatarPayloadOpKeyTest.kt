// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **发送侧自己造的出头像载荷，必须过得了接收侧自己的校验。**
 *
 * ## 缺陷形态（ARP-18 同族，2026-11-04 由 `AvatarPayloadBudgetTest` 撞出来）
 *
 * `AvatarGetPayload` / `AvatarDataPayload` / `AvatarMissPayload` 三个类型的 `op` 都写成
 * **带默认值的参数**（`val op: String = AvatarOps.GET`），而 `Codec.wireJson` 是
 * `encodeDefaults = false` —— 于是序列化时 `op` 因为「等于默认值」被整键省略。
 *
 * 而接收侧 `validateAvatar` 第一句就是 `o["op"] ?: return false`。
 * ⇒ 三条 `avatar` 帧（get / data / miss）**全部**会在对端被判 `bad-payload` 整帧丢弃。
 * 因为 `cap av1` 至今没有生产调用点传 `customAvatar = true`（见
 * `RelayControllerAvatarComponent.handlePickAvatar` 的 KDoc），这条路径没人走过，
 * 缺陷就一直躺着 —— 与本仓另外两起「发送侧发的东西过不了自己的白名单」同族：
 * `agent-approval` 漏 `riskLevel`/`diff`（ARP-15）、`agent-ack.kind` 白名单少两个在用值。
 *
 * ## 为什么判据这么写
 *
 * 不查「JSON 里有没有 op 这个键」（那是钉子实现），只问**这对函数能不能闭环**：
 * `Codec.avatarXxxPayload(...)` 造出来的对象，`validateAvatar` 认不认。
 * 这是发送端与接收端唯一共享的那份契约。
 */
class AvatarPayloadOpKeyTest {

    private val hash = "b".repeat(64)

    private fun assertRoundTrips(op: String, payload: kotlinx.serialization.json.JsonElement) {
        val o = payload as JsonObject
        assertTrue(validateAvatar(o), "op=" + op + " 的载荷过不了自家的接收侧校验：" + o.keys)
        assertEquals(op, o["op"]?.jsonPrimitive?.content, "op 取值必须与类型一致")
    }

    @Test
    fun theAvatarGetPayloadSurvivesOurOwnValidation() {
        assertRoundTrips(AvatarOps.GET, Codec.avatarGetPayload(hash))
    }

    @Test
    fun theAvatarDataPayloadSurvivesOurOwnValidation() {
        assertRoundTrips(AvatarOps.DATA, Codec.avatarDataPayload(hash, "AAAA"))
    }

    @Test
    fun theAvatarMissPayloadSurvivesOurOwnValidation() {
        assertRoundTrips(AvatarOps.MISS, Codec.avatarMissPayload(hash))
    }
}
