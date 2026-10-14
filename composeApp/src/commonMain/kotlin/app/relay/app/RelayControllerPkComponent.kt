// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import app.relay.app.model.DeliveryState
import app.relay.app.model.MessageUi
import app.relay.app.model.PkCardUi
import app.relay.core.nowMillis
import app.relay.core.protocol.Codec
import app.relay.core.protocol.ConvId
import app.relay.core.protocol.Envelope
import app.relay.core.protocol.PkGames
import app.relay.core.protocol.RpsValues
import app.relay.core.transport.SendOutcome
import app.relay.platform.localHourOfDay
import kotlinx.coroutines.launch

/**
 * PK 小游戏（比大小 / 猜拳）：发一张牌、收一张牌。
 *
 * 切出来的理由：这一族只有两个动作、两份状态（牌面 + 待接），其余 10 个分片的
 * 演化动力都跟它无关。过去它以 `internal fun RelayController.` 的形式散在一个
 * 136 行的文件里，读者要靠文件名猜它属于哪一族。
 *
 * @param host 组件只从控制器拿它真正用到的那几个成员（`runtime` / `scope` / `warn` /
 *   `showNotification`），而不是持有整个控制器：持有 host 会让「这族依赖什么」
 *   这件事在类型上不可见，而依赖面正是拆分想守住的东西。
 */
internal class RelayControllerPkComponent(
    private val host: RelayController,
) {

fun handleSendPk(
    convId: String,
    game: String,
    value: String,
) {
    val rt = host.runtime ?: run { host.warn(BannerMessage.NodeNotStarted); return }
    val isGroup = ConvId.isGroup(convId)
    val previewText = pkEmojiPreview(game, value)

    if (isGroup) {
        val groupId = ConvId.groupIdOf(convId) ?: return
        val group = host._state.value.settings.groups.firstOrNull { it.groupId == groupId } ?: run {
            host.warn(BannerMessage.GroupUnknown)
            return
        }
        if (!group.hasMember(host._state.value.selfNodeId)) {
            host.warn(BannerMessage.GroupLeft)
            return
        }
        host.scope.launch {
            val outcome = runCatching {
                rt.sendGroupPk(
                    game = game,
                    value = value,
                    groupId = groupId,
                    groupRev = group.rev,
                    members = group.members,
                )
            }.getOrNull() ?: return@launch
            host.handleAppendMessage(
                MessageUi(
                    id = outcome.envelopeId,
                    peerId = convId,
                    mine = true,
                    text = previewText,
                    at = nowMillis(),
                    delivery = if (outcome.outcome == SendOutcome.SENT) DeliveryState.Sent else DeliveryState.Queued,
                    pk = PkCardUi(game = game, value = value),
                ),
            )
        }
    } else {
        host.scope.launch {
            val outcome = runCatching {
                rt.sendPk(
                    peerId = convId,
                    game = game,
                    value = value,
                )
            }.getOrNull() ?: return@launch
            host.handleAppendMessage(
                MessageUi(
                    id = outcome.envelopeId,
                    peerId = convId,
                    mine = true,
                    text = previewText,
                    at = nowMillis(),
                    delivery = if (outcome.outcome == SendOutcome.SENT) DeliveryState.Sent else DeliveryState.Queued,
                    pk = PkCardUi(game = game, value = value),
                ),
            )
        }
    }
}


fun handleOnPkReceived(envelope: Envelope) {
    val decoded = Codec.decodePkMsg(envelope.payload) ?: return
    val groupId = decoded.groupId
    val convId = groupId?.let(ConvId::group) ?: envelope.from
    val previewText = pkEmojiPreview(decoded.game, decoded.value)

    host.handleAppendMessage(
        MessageUi(
            id = envelope.id,
            peerId = convId,
            mine = false,
            text = previewText,
            at = envelope.ts,
            senderId = if (groupId != null) envelope.from else null,
            pk = PkCardUi(game = decoded.game, value = decoded.value),
        ),
        incoming = true,
    )

    val peer = host._state.value.peers.firstOrNull { it.nodeId == envelope.from }
    val peerName = peer?.nick ?: envelope.from
    if (
        shouldNotify(
            settings = host._state.value.settings,
            fromAgent = peer?.isAgent == true,
            isGroup = groupId != null,
            mentioned = false,
            localHour = localHourOfDay(),
        )
    ) {
        host.showNotification(peerName, previewText, convId)
    }
}
}


internal fun pkEmojiPreview(game: String, value: String): String = when (game) {
    PkGames.RPS -> when (value) {
        RpsValues.ROCK -> "✊"
        RpsValues.SCISSORS -> "✌️"
        RpsValues.PAPER -> "🖐️"
        else -> "✊"
    }
    PkGames.DICE -> when (value) {
        "1" -> "⚀"
        "2" -> "⚁"
        "3" -> "⚂"
        "4" -> "⚃"
        "5" -> "⚄"
        "6" -> "⚅"
        else -> "🎲 $value"
    }
    else -> "🎲"
}
