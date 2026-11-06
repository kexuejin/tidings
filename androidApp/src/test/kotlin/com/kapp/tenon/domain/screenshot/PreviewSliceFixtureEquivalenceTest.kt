// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain.screenshot

import com.kapp.tenon.domain.toAgentSlice
import com.kapp.tenon.domain.toAvatarSlice
import com.kapp.tenon.domain.toChatSlice
import com.kapp.tenon.domain.toChromeSlice
import com.kapp.tenon.domain.toNodeSlice
import com.kapp.tenon.domain.toSettingsSlice
import com.kapp.tenon.domain.toTransferSlice
import com.kapp.tenon.domain.RelaySettings
import com.kapp.tenon.domain.RelayUiState
import com.kapp.tenon.domain.BannerMessage
import com.kapp.tenon.domain.BannerTone
import com.kapp.tenon.domain.BannerUi
import com.kapp.tenon.domain.model.MessageUi
import com.kapp.tenon.domain.model.PeerUi
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 预览夹具 [previewSlicesOf] 与切片投影 `toXSlice()` 的**逐字等同**证明。
 *
 * 为什么必须有它：4.5 之后预览全部改传切片（不再传整状态），而「14 个 DesktopShell 预览帧不变」
 * 的唯一数据级依据就是「夹具切出来的片与壳以前自己投影出来的片一模一样」。这条用例把
 * 两者钉在一起；任何一边改字段而另一边没跟上，它会先红。
 *
 * 反向验证：把 [previewSlicesOf] 里 `chat.unreadByPeer` 改成 `emptyMap()`，本用例立即变红。
 */
class PreviewSliceFixtureEquivalenceTest {

    private val state = RelayUiState(
        settings = RelaySettings.firstRun().copy(nodeId = "self-node", nick = "本机"),
        peers = listOf(PeerUi(nodeId = "peer-a", nick = "alice", platform = "", caps = emptyList(), online = true)),
        messages = mapOf(
            "peer-a" to listOf(MessageUi(id = "m-1", peerId = "peer-a", mine = false, text = "hi", at = 7L)),
        ),
        unreadByPeer = mapOf("peer-a" to 3),
        pendingByPeer = mapOf("peer-a" to 2),
        banner = BannerUi(message = BannerMessage.InvalidHost, tone = BannerTone.Info),
        focusRequest = "peer-a",
        externalStoreTick = 9L,
        avatarCache = mapOf("hash-1" to byteArrayOf(1, 2, 3)),
        pickedAvatarHash = "hash-2",
        pickedAvatarName = "avatar.png",
        pickedAvatarBytes = byteArrayOf(4, 5),
    )

    @Test
    fun previewSliceFixtureIsFieldIdenticalToTheProjections() {
        val slices = previewSlicesOf(state)
        assertEquals(state.toChatSlice(), slices.chat, "chat 片与投影必须逐字同义")
        assertEquals(state.toAgentSlice(), slices.agent, "agent 片与投影必须逐字同义")
        assertEquals(state.toNodeSlice(), slices.node, "node 片与投影必须逐字同义")
        assertEquals(state.toTransferSlice(), slices.transfer, "transfer 片与投影必须逐字同义")
        assertEquals(state.toSettingsSlice(), slices.settings, "settings 片与投影必须逐字同义")
        assertEquals(state.toChromeSlice(), slices.chrome, "chrome 片与投影必须逐字同义")
        assertEquals(state.toAvatarSlice(), slices.avatar, "avatar 片与投影必须逐字同义")
    }
}
