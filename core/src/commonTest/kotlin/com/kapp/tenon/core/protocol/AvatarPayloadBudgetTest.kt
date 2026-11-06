// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `avatar(op:"data")` 的预算：**发送侧与接收侧读同一个数，且这个数放得进一帧**。
 *
 * ## 缺陷形态（ARP-18⑥）
 *
 * 旧上限是 65536（字符），而整帧硬上限 [Limits.MAX_FRAME_BYTES] 也正好是 65536 字节 ——
 * 「字段合法」等于「信封必然超帧」。与 ARP-17 修掉的 `agent-event` body 是同一个形状：
 * 对端 framing 层读到 `len > 上限` 会按 peer-closed **拆掉整条链**，队列再把这张图
 * 循环重放。所以这条判据不能只看「字段校验通过没有」，要**编码出真帧、量它的字节数**。
 *
 * 这也是为什么本测试里有两条看似重复的断言：
 * - `validateAvatar` 的边界（取值域问题）；
 * - 上限值编成**真信封**之后的整帧字节数（收敛问题）。
 * 只保留前者的话，把上限改回 65536 依然全绿 —— 那正是缺陷原来的样子。
 */
class AvatarPayloadBudgetTest {

    // 64 位十六进制；`isHex64` 只认形状，不认内容。
    private val fakeHash = "a".repeat(64)

    private fun dataPayload(chars: Int): JsonObject =
        Codec.avatarDataPayload(fakeHash, "A".repeat(chars)) as JsonObject

    /** 正好压在上限上的 payload 通过校验（边界含等号，与接收侧 `>` 的判法一致）。 */
    @Test
    fun dataExactlyAtTheBudgetIsAccepted() {
        assertTrue(
            validateAvatar(dataPayload(Limits.AVATAR_DATA_B64)),
            "上限内的头像数据必须通过校验（上限 " + Limits.AVATAR_DATA_B64 + " 字符）",
        )
    }

    /** 超一位就拒 —— 放行它等于放行一帧永远到不了的对端载荷。 */
    @Test
    fun dataOneOverTheBudgetIsRejected() {
        assertFalse(
            validateAvatar(dataPayload(Limits.AVATAR_DATA_B64 + 1)),
            "超过上限的头像数据必须拒收",
        )
    }

    /**
     * **关键判据**：上限值编成真信封之后，整帧必须装得进 64 KiB。
     *
     * 反向验证：把 [Limits.AVATAR_DATA_B64] 改回 65536（旧值）这条立刻红 ——
     * 那正是「字段合法但帧必然超限」的原形。信封开销实测 ≈ 200 B，本断言不设宽容度，
     * 因为它量的就是「最坏合法载荷」的整帧大小。
     */
    @Test
    fun theWidestLegalAvatarFrameStillFitsInOneFrame() {
        val env = makeEnvelope(
            MsgTypes.AVATAR,
            "self-node",
            Codec.avatarDataPayload(fakeHash, "A".repeat(Limits.AVATAR_DATA_B64)),
        )
        val bytes = Codec.encodeToBytes(env)
        assertTrue(
            bytes.size <= Limits.MAX_FRAME_BYTES,
            "最宽合法头像帧 " + bytes.size + " B 超过整帧上限 " + Limits.MAX_FRAME_BYTES + " B",
        )
        // 而且它真的解得开：只管发不管收的判据没有意义。
        assertTrue(Codec.decodeTcp(bytes) is DecodeResult.Ok, "最宽合法头像帧必须自己解得开")
    }

    /**
     * 旧上限（65536）必须已经**不可用**：同一个数值编出来的帧超帧。
     *
     * 这条钉住「为什么改」而不是「改成了多少」—— 将来若有人把帧帽抬到 128 KiB，
     * 它会连同 [theWidestLegalAvatarFrameStillFitsInOneFrame] 一起重新对齐，
     * 而不是让 65536 悄悄变成合法值。
     */
    @Test
    fun theOldSixtyFourKilobyteBudgetWouldProduceAnOversizedFrame() {
        val env = makeEnvelope(
            MsgTypes.AVATAR,
            "self-node",
            Codec.avatarDataPayload(fakeHash, "A".repeat(65536)),
        )
        assertTrue(
            Codec.encodeToBytes(env).size > Limits.MAX_FRAME_BYTES,
            "旧上限 65536 字符编出来的帧居然装得下 —— 那条缺陷的成因就变了，判据要重看",
        )
        assertEquals(65536, Codec.utf8ByteLength("A".repeat(65536)), "Base64 是 ASCII，一字符一字节")
    }
}
