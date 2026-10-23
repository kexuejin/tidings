// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 捎话 Tidings contributors

package com.kapp.tidings.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.kapp.tidings.domain.pkEmojiPreview
import com.kapp.tidings.core.protocol.PkGames
import com.kapp.tidings.core.protocol.RpsValues
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.chat_pk_dice
import com.kapp.tidings.generated.resources.chat_pk_dice_val
import com.kapp.tidings.generated.resources.chat_pk_paper
import com.kapp.tidings.generated.resources.chat_pk_rock
import com.kapp.tidings.generated.resources.chat_pk_rps
import com.kapp.tidings.generated.resources.chat_pk_scissors
import com.kapp.tidings.generated.resources.chat_pk_title
import com.kapp.tidings.generated.resources.common_cancel
import org.jetbrains.compose.resources.stringResource

import com.kapp.tidings.ui.theme.LocalAppSpacing
import com.kapp.tidings.ui.theme.RelayShapes
import com.kapp.tidings.domain.model.PkCardUi

/**
 * PK 小游戏卡片视图（猜拳 / 骰子），嵌入消息气泡中。
 */
@Composable
public fun PkCardView(
    pk: PkCardUi,
    modifier: Modifier = Modifier,
) {
    val gameTitle = if (pk.game == PkGames.RPS) {
        stringResource(Res.string.chat_pk_rps)
    } else {
        stringResource(Res.string.chat_pk_dice)
    }
    val valueLabel = when (pk.game) {
        PkGames.RPS -> when (pk.value) {
            RpsValues.ROCK -> stringResource(Res.string.chat_pk_rock)
            RpsValues.PAPER -> stringResource(Res.string.chat_pk_paper)
            RpsValues.SCISSORS -> stringResource(Res.string.chat_pk_scissors)
            else -> pk.value
        }
        PkGames.DICE -> stringResource(Res.string.chat_pk_dice_val, pk.value.toIntOrNull() ?: 1)
        else -> pk.value
    }
    val emoji = pkEmojiPreview(pk.game, pk.value)

    Surface(
        shape = RelayShapes.medium,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = LocalAppSpacing.current.lg, vertical = LocalAppSpacing.current.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = gameTitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(LocalAppSpacing.current.sm))
            Text(
                text = emoji,
                fontSize = 40.sp,
            )
            Spacer(Modifier.height(LocalAppSpacing.current.sm))
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * 发起 PK 选择弹窗。
 */
@Composable
public fun PkPickerDialog(
    onDismiss: () -> Unit,
    onPick: (game: String, value: String) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RelayShapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth().padding(horizontal = LocalAppSpacing.current.lg),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(Res.string.chat_pk_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(LocalAppSpacing.current.lg))

                // --- 猜拳区域 ---
                Text(
                    text = stringResource(Res.string.chat_pk_rps),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.Start),
                )
                Spacer(Modifier.height(LocalAppSpacing.current.sm))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    RpsChoiceButton(
                        emoji = "✊",
                        label = stringResource(Res.string.chat_pk_rock),
                        onClick = { onPick(PkGames.RPS, RpsValues.ROCK); onDismiss() },
                    )
                    RpsChoiceButton(
                        emoji = "✌️",
                        label = stringResource(Res.string.chat_pk_scissors),
                        onClick = { onPick(PkGames.RPS, RpsValues.SCISSORS); onDismiss() },
                    )
                    RpsChoiceButton(
                        emoji = "🖐️",
                        label = stringResource(Res.string.chat_pk_paper),
                        onClick = { onPick(PkGames.RPS, RpsValues.PAPER); onDismiss() },
                    )
                }

                Spacer(Modifier.height(LocalAppSpacing.current.lg))
                HorizontalDivider()
                Spacer(Modifier.height(LocalAppSpacing.current.lg))

                // --- 掷骰子区域 ---
                Text(
                    text = stringResource(Res.string.chat_pk_dice),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.Start),
                )
                Spacer(Modifier.height(LocalAppSpacing.current.sm))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    val diceFaces = listOf("1" to "⚀", "2" to "⚁", "3" to "⚂", "4" to "⚃", "5" to "⚄", "6" to "⚅")
                    diceFaces.forEach { (v, face) ->
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RelayShapes.small)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable {
                                    onPick(PkGames.DICE, v)
                                    onDismiss()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(text = face, fontSize = MaterialTheme.typography.headlineSmall.fontSize)
                        }
                    }
                }
                Spacer(Modifier.height(LocalAppSpacing.current.md))
                FilledTonalButton(
                    onClick = {
                        val rolled = (1..6).random().toString()
                        onPick(PkGames.DICE, rolled)
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("🎲 ${stringResource(Res.string.chat_pk_dice)}")
                }

                Spacer(Modifier.height(LocalAppSpacing.current.md))
                AppTextButton(
                    text = stringResource(Res.string.common_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
}

@Composable
private fun RpsChoiceButton(
    emoji: String,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RelayShapes.medium)
            .clickable(onClick = onClick)
            .padding(LocalAppSpacing.current.sm),
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = emoji, fontSize = MaterialTheme.typography.headlineMedium.fontSize)
        }
        Spacer(Modifier.height(LocalAppSpacing.current.xs))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
