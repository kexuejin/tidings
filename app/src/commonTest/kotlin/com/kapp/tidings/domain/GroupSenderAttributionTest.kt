// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.core.protocol.Codec
import com.kapp.tidings.core.protocol.MsgKinds
import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.makeEnvelope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 收到一条群文本时，**这条到底是谁说的**。
 *
 * 这条判定（`RelayController.groupSenderIdOf`）是「群里 agent 消息」接收侧的全部逻辑，
 * 而它此前没有测试 —— 只有一条真机缺陷记录：不读 `senderId` 时，手机端把 agent 说的话
 * 显示成「桌面端发的」，而桌面端自己按 `agent:<host>:<kind>` 落库，同一个群两端显示成两个人。
 *
 * 判据分三类，缺一类都会在真机上以"错的发送者"出现，而不是报错：
 *  · 带 `senderId` ⇒ 用它（agent 转发、以及用户自己发的都走这条路）；
 *  · 没带 / 空串 / 不是字符串 ⇒ 退回信封 `from`（旧端与坏帧都不许把发送者变成空）；
 *  · **不是群文本**（私聊 `kind:"text"`）⇒ 一律不读这个键 —— 私聊的发送者就是 `from`，
 *    在那里读它等于给一条伪造的 `senderId` 开了后门。
 */
class GroupSenderAttributionTest {

    private val device = "11111111-1111-4111-8111-111111111111"
    private val agentSender = "agent:11111111-1111-4111-8111-111111111111:dsh"

    private fun groupText(senderId: String?) = makeEnvelope(
        MsgTypes.MSG,
        device,
        Codec.groupTextPayload(text = "hi", groupId = "grp-1", groupRev = 1, senderId = senderId),
    )

    @Test
    fun anAgentAttributedGroupTextCarriesItsSpeaker() {
        assertEquals(
            agentSender,
            RelayController.groupSenderIdOf(groupText(agentSender)),
            "不读 senderId ⇒ agent 的话被显示成发送设备说的",
        )
    }

    @Test
    fun aGroupTextWithoutTheKeyFallsBackToTheDevice() {
        assertNull(
            RelayController.groupSenderIdOf(groupText(null)),
            "没带 senderId 时必须返回 null，让调用方退回信封 from —— 不能凭空造一个空发送者",
        )
    }

    @Test
    fun aBlankSenderIdIsTreatedAsAbsent() {
        assertNull(RelayController.groupSenderIdOf(groupText("   ")), "空白串当没带，否则界面上是一个没名字的人")
        assertNull(RelayController.groupSenderIdOf(groupText("")), "空串同上")
    }

    @Test
    fun aNonStringSenderIdIsTreatedAsAbsent() {
        val broken: JsonObject = buildJsonObject {
            put("kind", MsgKinds.GROUP_TEXT)
            put("text", "hi")
            put("groupId", "grp-1")
            put("groupRev", 1)
            put("senderId", 42)
        }
        assertNull(
            RelayController.groupSenderIdOf(makeEnvelope(MsgTypes.MSG, device, broken)),
            "坏帧（senderId 不是字符串）不许让归属变成 \"42\"",
        )
    }

    @Test
    fun aPrivateTextNeverReadsTheKey() {
        val privateText = makeEnvelope(MsgTypes.MSG, device, Codec.textPayload("hi"))
        assertNull(
            RelayController.groupSenderIdOf(privateText),
            "私聊的发送者就是信封 from；在这里读 senderId 等于给伪造归属开后门",
        )
    }

    @Test
    fun aForgedSenderIdOnAPrivateTextIsIgnored() {
        val forged = buildJsonObject {
            put("kind", MsgKinds.TEXT)
            put("text", "hi")
            put("senderId", agentSender)
        }
        assertNull(RelayController.groupSenderIdOf(makeEnvelope(MsgTypes.MSG, device, forged)))
    }
}
