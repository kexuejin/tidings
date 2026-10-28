// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.ui.components

import com.kapp.tidings.core.protocol.PkGames
import com.kapp.tidings.core.protocol.RpsValues
import com.kapp.tidings.domain.model.PkCardUi
import kotlin.test.Test
import kotlin.test.assertEquals

class PkPaletteTest {

    @Test
    fun diceValuesAreNumericOrClamped() {
        val dice1 = PkCardUi(game = PkGames.DICE, value = "6")
        assertEquals(6, dice1.value.toIntOrNull())

        val invalidDice = PkCardUi(game = PkGames.DICE, value = "invalid")
        val fallback = invalidDice.value.toIntOrNull() ?: 1
        assertEquals(1, fallback)
    }

    @Test
    fun rpsValuesAreRecognized() {
        val rock = PkCardUi(game = PkGames.RPS, value = RpsValues.ROCK)
        assertEquals(RpsValues.ROCK, rock.value)

        val scissors = PkCardUi(game = PkGames.RPS, value = RpsValues.SCISSORS)
        assertEquals(RpsValues.SCISSORS, scissors.value)

        val paper = PkCardUi(game = PkGames.RPS, value = RpsValues.PAPER)
        assertEquals(RpsValues.PAPER, paper.value)
    }
}
