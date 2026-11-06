// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.CodecProfile
import com.kapp.tenon.core.protocol.Codec
import com.kapp.tenon.core.protocol.Limits
import com.kapp.tenon.core.protocol.MsgTypes
import com.kapp.tenon.core.protocol.Profile
import com.kapp.tenon.core.protocol.ProfilePayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 分级声明（D-018 方案 b）的**核心读数**：桌面广播 caps 无论 luciola 开关如何，
 * 都必须 ≤ [Limits.CAPS] 且过 [CodecProfile.validateProfile] —— task-10 实锤的
 * 存量自拒（luciola-on 桌面 18 项被对端整份拒收 ⇒ 握手死）从这里起不再可能。
 *
 * 四种组合各有判据（luciola on/off × 手机/桌面）：
 *  - 桌面 on/off：fch1/scr1 不在广播里（改由本地事实 × 对端 caps 协商），清单 ≤16 且合法；
 *  - 手机 on（默认开）：13 项，合法；
 *  - 桌面 luciola-on 若仍把 fch1/scr1 加回去 ⇒ 超限（这条由「不在清单」的反向断言钉住 ——
 *    谁把广播分级撤了，这条红）。
 */
class CapsBroadcastTieringTest {

    private fun settings() = RelaySettings(nodeId = "self", nick = "demo-mac-97", profileRev = 1)

    private fun valid(p: Profile): Boolean {
        val json = Codec.wireJson.encodeToString(ProfilePayload.serializer(), ProfilePayload(p))
        return CodecProfile.validateProfile(
            (Codec.wireJson.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject)
                .getValue("profile"),
        )
    }

    @Test
    fun desktopBroadcastStaysWithinCapsBudgetRegardlessOfLuciola() {
        val s = settings()
        val on = s.toProfile(
            host = "mac", ver = "relay-0.1.0", remoteView = true, remoteShare = true,
            advertiseAgent = true, channelCapable = false,
        )
        // 注意：luciola 数据面可用性（原 fileOverChannel/screenOverChannel 实参）已不进
        // 广播 —— 两个开关值对广播面不再有影响，这里只验「无论什么状态都 ≤16 且合法」。
        assertTrue(on.caps.size <= Limits.CAPS, "桌面广播必须 ≤16：实际 " + on.caps.size)
        assertTrue(valid(on), "桌面广播必须过 validateProfile（存量自拒不再可能）")
    }

    @Test
    fun desktopLuciolaChannelsAreNeverBroadcast() {
        val on = settings().toProfile(
            host = "mac", ver = "relay-0.1.0", remoteView = true, remoteShare = true,
            advertiseAgent = true, channelCapable = false,
        )
        assertFalse(
            MsgTypes.CAP_FILE_CHANNEL in on.caps,
            "fch1 停止广播（D-018 分级声明）—— 谁把它加回广播，这条红",
        )
        assertFalse(
            MsgTypes.CAP_SCREEN_CHANNEL in on.caps,
            "scr1 停止广播（D-018 分级声明）",
        )
    }

    @Test
    fun phoneBroadcastRemainsValidWithAgc1() {
        val phone = settings().toProfile(host = "phone", ver = "relay-0.1.0")
        assertTrue(phone.caps.size <= Limits.CAPS, "手机广播 ≤16")
        assertTrue(valid(phone), "手机广播合法")
        assertTrue(
            MsgTypes.CAP_AGENT_CHANNEL in phone.caps,
            "手机保留 agc1（D-017 修订：解得动 Channel 帧）",
        )
    }

    @Test
    fun desktopBroadcastIsExactlyTheSixteenItemTier() {
        val on = settings().toProfile(
            host = "mac", ver = "relay-0.1.0", remoteView = true, remoteShare = true,
            advertiseAgent = true, channelCapable = false,
        )
        // 精确清单钉住：桌面广播 = 身份族 16 项（luciola 两项已移出）—— 数量级变了这条红。
        assertEquals(16, on.caps.size, "桌面广播清单 = 16 项（分级后）")
    }
}
