// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.Codec
import com.kapp.tenon.core.protocol.CodecQuest
import com.kapp.tenon.core.protocol.Envelope
import com.kapp.tenon.core.protocol.MsgTypes
import com.kapp.tenon.core.protocol.QuestDispatchPayload
import kotlinx.coroutines.launch

/**
 * 主支线跨设备帧的入站路由（QUEST_DISPATCH / QUEST_NOTICE，Phase 2）。
 *
 * 安全面（§3 第 7 种教训的镜像）：派发的**信任判定**是发端 ∈ RelaySettings.questPairNodes
 * （对称配对清单，空 = 双向拒绝）——配了才能派，才能被派。拒绝时回一帧 FAILED
 * 通知（派单端的便签据此翻面成失败卡，绝不静默）。
 */
internal object RelayControllerQuestWire {

    fun handleOnQuestDispatch(receiver: RelayController, envelope: Envelope) {
        val payload = envelope.payload as? kotlinx.serialization.json.JsonObject ?: return
        if (!Codec.validatePayload(MsgTypes.QUEST_DISPATCH, payload)) {
            receiver.diagnosticLogSink?.invoke("RELAY-QUEST", "quest dispatch rejected: payload validation failed")
            return
        }
        val p = QuestDispatchPayload(
            clientToken = (payload["clientToken"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return,
            prompt = (payload["prompt"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return,
        )
        val sender = envelope.from ?: return
        val allowed = receiver.state.value.settings.questPairNodes
        if (sender !in allowed) {
            receiver.diagnosticLogSink?.invoke(
                "RELAY-QUEST",
                "quest dispatch rejected: sender not paired (" + sender.take(12) + ")",
            )
            // 拒绝也要让对端的便签翻面成失败卡——静默就是 §3 的「发送侧正常、什么都没发生」。
            receiver.scope.launch {
                receiver.runtime?.sendQuestNotice(
                    peerId = sender,
                    notice = com.kapp.tenon.core.protocol.AssistantQuestNotice(
                        questId = "rejected-" + p.clientToken.take(16),
                        title = p.prompt.take(40),
                        status = com.kapp.tenon.core.protocol.QuestStatus.FAILED,
                        summary = "Dispatch rejected: this device does not accept quest dispatches from " + sender.take(12) + ".",
                        convId = com.kapp.tenon.core.protocol.ConvId.ASSISTANT,
                        clientToken = p.clientToken,
                        rev = 0L,
                    ),
                )
            }
            return
        }
        val handled = receiver.onAssistantQuestDispatch?.invoke(p.prompt, p.clientToken, sender) ?: false
        if (!handled) {
            receiver.diagnosticLogSink?.invoke("RELAY-QUEST", "quest dispatch received but no executor attached")
        }
    }

    fun handleOnQuestNotice(receiver: RelayController, envelope: Envelope) {
        val payload = envelope.payload as? kotlinx.serialization.json.JsonObject ?: return
        if (!Codec.validatePayload(MsgTypes.QUEST_NOTICE, payload)) {
            receiver.diagnosticLogSink?.invoke("RELAY-QUEST", "quest notice rejected: payload validation failed")
            return
        }
        val notice = CodecQuest.decodeQuestNotice(payload) ?: return
        // 来源宿主随信封：翻面的卡盖上宿主 senderId，questSyncPull 才认得出
        // 这张卡属于哪台宿主（归宿主缺失 = 终态对账永远轮不到它）。
        receiver.handleAssistantQuestNotice(notice, originHost = envelope.from)
    }
}
