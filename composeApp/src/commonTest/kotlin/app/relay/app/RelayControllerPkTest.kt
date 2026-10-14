// SPDX-License-Identifier: GPL-3.0-only
package app.relay.app

import app.relay.core.nowMillis
import app.relay.core.protocol.Codec
import app.relay.core.protocol.makeEnvelope
import app.relay.core.protocol.MsgTypes
import app.relay.core.protocol.PkGames
import app.relay.core.protocol.RpsValues
import app.relay.platform.InMemoryAppPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class RelayControllerPkTest {
    private val scope = CoroutineScope(Job())

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun controller() = RelayController(
        platform = InMemoryAppPlatform(),
        store = InMemorySettingsStore(),
        scope = scope,
    )

    @Test
    fun testPkEmojiPreview() {
        assertEquals("✊", pkEmojiPreview(PkGames.RPS, RpsValues.ROCK))
        assertEquals("✌️", pkEmojiPreview(PkGames.RPS, RpsValues.SCISSORS))
        assertEquals("🖐️", pkEmojiPreview(PkGames.RPS, RpsValues.PAPER))
        assertEquals("⚀", pkEmojiPreview(PkGames.DICE, "1"))
        assertEquals("⚅", pkEmojiPreview(PkGames.DICE, "6"))
    }

    @Test
    fun testInboundPkMessageDecodesIntoCard() {
        val controller = controller()
        val envelope = makeEnvelope(
            type = MsgTypes.MSG,
            id = "msg-pk-1",
            from = "peer-pk-sender",
            ts = nowMillis(),
            payload = Codec.pkPayload(PkGames.RPS, RpsValues.ROCK),
        )

        controller.messagesCtl.handleOnInbound(envelope)

        val messages = controller.state.value.messagesWith("peer-pk-sender")
        assertEquals(1, messages.size)
        val msg = messages.first()
        val pk = msg.pk
        assertNotNull(pk)
        assertEquals(PkGames.RPS, pk.game)
        assertEquals(RpsValues.ROCK, pk.value)
    }

    @Test
    fun testInboundDicePkMessage() {
        val controller = controller()
        val envelope = makeEnvelope(
            type = MsgTypes.MSG,
            id = "msg-pk-2",
            from = "peer-dice-sender",
            ts = nowMillis(),
            payload = Codec.pkPayload(PkGames.DICE, "4"),
        )

        controller.messagesCtl.handleOnInbound(envelope)

        val messages = controller.state.value.messagesWith("peer-dice-sender")
        assertEquals(1, messages.size)
        val msg = messages.first()
        val pk = msg.pk
        assertNotNull(pk)
        assertEquals(PkGames.DICE, pk.game)
        assertEquals("4", pk.value)
    }
}
