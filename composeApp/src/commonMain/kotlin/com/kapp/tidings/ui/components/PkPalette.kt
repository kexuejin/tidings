// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 捎话 Tidings contributors

package com.kapp.tidings.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.kapp.tidings.core.protocol.PkGames
import com.kapp.tidings.core.protocol.RpsValues
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.chat_pk_dice
import com.kapp.tidings.generated.resources.chat_pk_dice_outcome
import com.kapp.tidings.generated.resources.chat_pk_dice_title
import com.kapp.tidings.generated.resources.chat_pk_paper
import com.kapp.tidings.generated.resources.chat_pk_rock
import com.kapp.tidings.generated.resources.chat_pk_rps
import com.kapp.tidings.generated.resources.chat_pk_rps_outcome
import com.kapp.tidings.generated.resources.chat_pk_scissors
import com.kapp.tidings.generated.resources.chat_pk_title
import com.kapp.tidings.generated.resources.common_cancel
import com.kapp.tidings.generated.resources.ic_hand
import com.kapp.tidings.generated.resources.ic_pk
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

import com.kapp.tidings.ui.theme.IconSizes
import com.kapp.tidings.ui.theme.LocalAppSpacing
import com.kapp.tidings.ui.theme.RelayElevations
import com.kapp.tidings.ui.theme.RelayShapes
import com.kapp.tidings.domain.model.PkCardUi
import com.kapp.tidings.domain.pkEmojiPreview

/**
 * 骰子自绘点数白瓷板（1~6 点）。
 */
@Composable
public fun DiceTile(
    dots: Int,
    modifier: Modifier = Modifier,
) {
    val clampedDots = dots.coerceIn(1, 6)
    val dotColor = MaterialTheme.colorScheme.onSurface
    val sp = LocalAppSpacing.current
    Surface(
        shape = RelayShapes.small,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        shadowElevation = RelayElevations.Card,
        modifier = modifier.size(42.dp),
    ) {
        Canvas(modifier = Modifier.padding(sp.sm6).fillMaxSize()) {
            val dotRadius = size.width * 0.1f
            val w = size.width
            val h = size.height

            val left = w * 0.28f
            val midX = w * 0.5f
            val right = w * 0.72f

            val top = h * 0.28f
            val midY = h * 0.5f
            val bottom = h * 0.72f

            when (clampedDots) {
                1 -> {
                    drawCircle(color = dotColor, radius = dotRadius * 1.35f, center = Offset(midX, midY))
                }
                2 -> {
                    drawCircle(color = dotColor, radius = dotRadius, center = Offset(left, top))
                    drawCircle(color = dotColor, radius = dotRadius, center = Offset(right, bottom))
                }
                3 -> {
                    drawCircle(color = dotColor, radius = dotRadius, center = Offset(left, top))
                    drawCircle(color = dotColor, radius = dotRadius, center = Offset(midX, midY))
                    drawCircle(color = dotColor, radius = dotRadius, center = Offset(right, bottom))
                }
                4 -> {
                    drawCircle(color = dotColor, radius = dotRadius, center = Offset(left, top))
                    drawCircle(color = dotColor, radius = dotRadius, center = Offset(right, top))
                    drawCircle(color = dotColor, radius = dotRadius, center = Offset(left, bottom))
                    drawCircle(color = dotColor, radius = dotRadius, center = Offset(right, bottom))
                }
                5 -> {
                    drawCircle(color = dotColor, radius = dotRadius, center = Offset(left, top))
                    drawCircle(color = dotColor, radius = dotRadius, center = Offset(right, top))
                    drawCircle(color = dotColor, radius = dotRadius, center = Offset(midX, midY))
                    drawCircle(color = dotColor, radius = dotRadius, center = Offset(left, bottom))
                    drawCircle(color = dotColor, radius = dotRadius, center = Offset(right, bottom))
                }
                6 -> {
                    val y1 = h * 0.24f
                    val y2 = h * 0.5f
                    val y3 = h * 0.76f
                    drawCircle(color = dotColor, radius = dotRadius, center = Offset(left, y1))
                    drawCircle(color = dotColor, radius = dotRadius, center = Offset(left, y2))
                    drawCircle(color = dotColor, radius = dotRadius, center = Offset(left, y3))
                    drawCircle(color = dotColor, radius = dotRadius, center = Offset(right, y1))
                    drawCircle(color = dotColor, radius = dotRadius, center = Offset(right, y2))
                    drawCircle(color = dotColor, radius = dotRadius, center = Offset(right, y3))
                }
            }
        }
    }
}

/**
 * 猜拳手势白瓷板（石头 / 剪刀 / 布）。
 */
@Composable
public fun RpsTile(
    value: String,
    modifier: Modifier = Modifier,
) {
    val emoji = pkEmojiPreview(PkGames.RPS, value)
    Surface(
        shape = RelayShapes.small,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        shadowElevation = RelayElevations.Card,
        modifier = modifier.size(42.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = emoji,
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}

/**
 * PK 小游戏卡片视图（猜拳 / 骰子），嵌入消息气泡中。
 */
@Composable
public fun PkCardView(
    pk: PkCardUi,
    modifier: Modifier = Modifier,
) {
    val isDice = pk.game == PkGames.DICE
    val gameTitle = if (isDice) {
        stringResource(Res.string.chat_pk_dice_title)
    } else {
        stringResource(Res.string.chat_pk_rps)
    }
    val valueLabel = if (isDice) {
        val dots = pk.value.toIntOrNull() ?: 1
        stringResource(Res.string.chat_pk_dice_outcome, dots)
    } else {
        val label = when (pk.value) {
            RpsValues.ROCK -> stringResource(Res.string.chat_pk_rock)
            RpsValues.PAPER -> stringResource(Res.string.chat_pk_paper)
            RpsValues.SCISSORS -> stringResource(Res.string.chat_pk_scissors)
            else -> pk.value
        }
        stringResource(Res.string.chat_pk_rps_outcome, label)
    }

    val sp = LocalAppSpacing.current
    Surface(
        shape = RelayShapes.medium,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = modifier.widthIn(min = 160.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = sp.md10, vertical = sp.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(sp.md10),
        ) {
            if (isDice) {
                DiceTile(dots = pk.value.toIntOrNull() ?: 1)
            } else {
                RpsTile(value = pk.value)
            }
            Column(
                verticalArrangement = Arrangement.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(sp.xs),
                ) {
                    Icon(
                        painter = painterResource(if (isDice) Res.drawable.ic_pk else Res.drawable.ic_hand),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(IconSizes.Badge),
                    )
                    Text(
                        text = gameTitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Spacer(Modifier.height(sp.xxs))
                Text(
                    text = valueLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
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
