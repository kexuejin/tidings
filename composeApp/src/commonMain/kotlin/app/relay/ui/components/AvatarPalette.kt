// SPDX-License-Identifier: GPL-3.0-only

package app.relay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.relay.core.protocol.AVATAR_ANIMAL_MAX_VALUE
import app.relay.core.protocol.AVATAR_LEGACY_INITIAL_VALUE
import app.relay.generated.resources.Res
import app.relay.generated.resources.r2_set_avatar_mode_icon
import app.relay.generated.resources.r2_set_avatar_mode_initial
import app.relay.generated.resources.r2_set_avatar_mode_upload
import app.relay.generated.resources.set_avatar_change
import app.relay.generated.resources.set_avatar_file_meta
import app.relay.ui.theme.LocalAppSpacing
import app.relay.ui.theme.RelayElevations
import app.relay.ui.theme.RelayShapes
import org.jetbrains.compose.resources.stringResource

/**
 * 头像调色板与动物图标规范，与 prototype/relay.html 及 Limits.kt 对齐。
 */
object AvatarPalette {
    val ANIMALS = listOf(
        "🐶", "🐱", "🐷", "🐰", "🐼", "🐻", "🦊", "🐯", "🦁", "🐮",
        "🐸", "🐵", "🐨", "🐹", "🐧", "🐥", "🦉", "🦄", "🐳", "🐙"
    )

    data class AvatarColor(val name: String, val bg: Color)

    val COLORS = listOf(
        AvatarColor("茶青", Color(0xFF3D8B6B)),
        AvatarColor("湖蓝", Color(0xFF2F80A7)),
        AvatarColor("靛蓝", Color(0xFF5366B3)),
        AvatarColor("紫藤", Color(0xFF7B5BA7)),
        AvatarColor("莓红", Color(0xFFB65072)),
        AvatarColor("枫红", Color(0xFFB75D4A)),
        AvatarColor("杏黄", Color(0xFFC88A2D)),
        AvatarColor("橄榄", Color(0xFF6F7F3F)),
        AvatarColor("石墨", Color(0xFF55616D)),
        AvatarColor("墨青", Color(0xFF2F6F73)),
    )

    val ANIMAL_COUNT: Int get() = ANIMALS.size

    fun nickHash(nick: String): Int {
        var h = 0
        for (ch in nick) {
            h = (h * 31 + ch.code) and 0x7FFFFFFF
        }
        return h
    }

    fun colorIndex(avatar: Int, nick: String): Int {
        return if (avatar in 0..AVATAR_ANIMAL_MAX_VALUE) {
            (avatar / ANIMAL_COUNT) % COLORS.size
        } else {
            nickHash(nick.ifBlank { "茶" }) % COLORS.size
        }
    }

    fun animalIndex(avatar: Int): Int {
        return if (avatar in 0..AVATAR_ANIMAL_MAX_VALUE) {
            avatar % ANIMAL_COUNT
        } else {
            0
        }
    }

    fun encode(colorIdx: Int, animalIdx: Int): Int =
        colorIdx * ANIMAL_COUNT + animalIdx

    fun resolveBg(avatar: Int, nick: String): Color =
        COLORS[colorIndex(avatar, nick)].bg

    fun resolveAnimal(avatar: Int): String? =
        if (avatar in 0..AVATAR_ANIMAL_MAX_VALUE) ANIMALS[animalIndex(avatar)] else null
}

/**
 * 200 种动物头像 + 底色选择器组件。
 *
 * 与 prototype/relay.html 的 avPicker() 对齐：
 * 支持 10 种底色选择、20 种动物图标、以及「图标头像」/「昵称首字」模式切换。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AvatarPicker(
    avatar: Int,
    nick: String,
    onAvatarChange: (Int) -> Unit,
    pickedAvatarBytes: ByteArray? = null,
    onRemovePickedAvatar: (() -> Unit)? = null,
    /** 第三档「上传图片」的出口；null = 没接线，该档置灰。 */
    onOpenAvatarPage: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val sp = LocalAppSpacing.current
    val hasCustomAvatar = pickedAvatarBytes != null
    val isInitialMode = !hasCustomAvatar && (avatar == AVATAR_LEGACY_INITIAL_VALUE || avatar !in 0..AVATAR_ANIMAL_MAX_VALUE)
    val currentColorIdx = AvatarPalette.colorIndex(if (isInitialMode || hasCustomAvatar) 0 else avatar, nick)
    val currentAnimalIdx = AvatarPalette.animalIndex(if (isInitialMode || hasCustomAvatar) 0 else avatar)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(sp.md10),
    ) {
        AvatarModeSegments(
            isInitialMode = isInitialMode,
            hasCustomAvatar = hasCustomAvatar,
            onSelectIcon = {
                onRemovePickedAvatar?.invoke()
                onAvatarChange(AvatarPalette.encode(currentColorIdx, currentAnimalIdx))
            },
            onSelectInitial = {
                onRemovePickedAvatar?.invoke()
                onAvatarChange(AVATAR_LEGACY_INITIAL_VALUE)
            },
            onOpenAvatarPage = onOpenAvatarPage,
        )

        if (hasCustomAvatar) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(sp.md).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(sp.md),
                ) {
                    PeerAvatar(name = nick, size = 48.dp, avatarBytes = pickedAvatarBytes)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(Res.string.r2_set_avatar_mode_upload),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(Res.string.set_avatar_file_meta),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (onOpenAvatarPage != null) {
                        AppOutlinedButton(
                            text = stringResource(Res.string.set_avatar_change),
                            onClick = onOpenAvatarPage,
                            size = AppButtonSize.Small,
                        )
                    }
                }
            }
        } else if (!isInitialMode) {
            // 底色选择：原型 `.avedit-cs{display:flex;flex-wrap:wrap;gap:8px}` +
            // `.avedit-c{width:24px;height:24px;border:2px solid var(--surface);border-radius:50%;
            // box-shadow:0 0 0 1px var(--hair)}`，选中换成 `box-shadow:0 0 0 2px var(--primary)`，
            // 并且中间多一颗 8px 的 `::after`（底色是 `var(--ink)`）。
            // 代码原来是 32dp、没有那圈 2dp 的底色描边。
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(sp.sm),
                verticalArrangement = Arrangement.spacedBy(sp.sm),
            ) {
                AvatarPalette.COLORS.forEachIndexed { idx, color ->
                    val isSelected = idx == currentColorIdx
                    Box(
                        modifier = Modifier
                            .size(LocalAppSpacing.current.xl)
                            .clip(CircleShape)
                            .background(color.bg)
                            .border(
                                width = LocalAppSpacing.current.xxs,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                                shape = CircleShape,
                            )
                            .clickable {
                                onAvatarChange(AvatarPalette.encode(idx, currentAnimalIdx))
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .size(LocalAppSpacing.current.sm)
                                    .clip(CircleShape)
                                    .background(Color.White),
                            )
                        }
                    }
                }
            }

            // 动物图标：原型 `.avedit-lb{display:flex;align-items:baseline;gap:6px;
            // font-size:12px;color:var(--on-2);margin-bottom:7px}`（名字加粗、右侧 `.nb` 是 `n / N`）
            // + `.avedit-grid{display:grid;grid-template-columns:repeat(10,minmax(0,44px));gap:6px}`
            // + `.avedit-ch{aspect-ratio:1;border:0;border-radius:50%;background:var(--c);padding:0;
            // font-size:clamp(14px,5cqw,22px)}`，选中
            // `.avedit-ch[aria-pressed="true"]{box-shadow:0 0 0 2px var(--primary),…}`。
            // 即：**十列定宽、格子上限 44dp、正圆、emoji 字号是容器宽度的 5%（钳在 14–22）**，
            // 格子底色就是当前选中的底色。代码原来是 FlowRow + 固定 36dp 的方角格 + 写死 18sp。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(sp.sm6),
            ) {
                Text(
                    text = "动物图标",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = AvatarPalette.ANIMALS[currentAnimalIdx],
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${currentAnimalIdx + 1} / ${AvatarPalette.ANIMALS.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                )
            }
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val columns = 10
                val gap = sp.sm6
                val cell = minOf(44.dp, (maxWidth - gap * (columns - 1)) / columns)
                val emojiSize = (maxWidth.value * 0.05f).coerceIn(14f, 22f).sp
                Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                    AvatarPalette.ANIMALS.chunked(columns).forEachIndexed { rowIdx, row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                            row.forEachIndexed { colIdx, emoji ->
                                val idx = rowIdx * columns + colIdx
                                val isSelected = idx == currentAnimalIdx
                                Box(
                                    modifier = Modifier
                                        .size(cell)
                                        .clip(CircleShape)
                                        .background(AvatarPalette.COLORS[currentColorIdx].bg)
                                        .border(
                                            width = LocalAppSpacing.current.xxs,
                                            color = if (isSelected) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                Color.Transparent
                                            },
                                            shape = CircleShape,
                                        )
                                        .clickable {
                                            onAvatarChange(AvatarPalette.encode(currentColorIdx, idx))
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(text = emoji, fontSize = emojiSize)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 三档等宽分段控件：图标头像 / 昵称首字 / 上传图片。
 *
 * 与 `feature/settings/SettingsPageChrome.kt` 的 `ThemeSegments` 同一套 `.seg` 规格
 * （原型 `prototype/relay.html:311-315`）：容器 `surface-2` + 12dp 圆角 + 3px 内衬与间隙；
 * 档位 8dp 圆角、等宽；选中档白底 + 600 + 投影；**未选中档不写 fontWeight**
 * （原型 `.seg button` 没声明权重，写 `Normal` 会凭空多出一个代码侧 400）。
 *
 * [onOpenAvatarPage] 为 null 时第三档置灰且不可点。
 */
@Composable
private fun AvatarModeSegments(
    isInitialMode: Boolean,
    hasCustomAvatar: Boolean = false,
    onSelectIcon: () -> Unit,
    onSelectInitial: () -> Unit,
    onOpenAvatarPage: (() -> Unit)?,
) {
    val sp = LocalAppSpacing.current
    val options = listOf(
        Triple(stringResource(Res.string.r2_set_avatar_mode_icon), !isInitialMode && !hasCustomAvatar, onSelectIcon),
        Triple(stringResource(Res.string.r2_set_avatar_mode_initial), isInitialMode && !hasCustomAvatar, onSelectInitial),
        Triple(stringResource(Res.string.r2_set_avatar_mode_upload), hasCustomAvatar, onOpenAvatarPage ?: {}),
    )
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(sp.xs),
            horizontalArrangement = Arrangement.spacedBy(sp.xs),
        ) {
            options.forEachIndexed { index, (label, active, onClick) ->
                // 前两档永远可用；第三档只有接了出口才可用。
                val enabled = index < 2 || onOpenAvatarPage != null
                Surface(
                    color = if (active) MaterialTheme.colorScheme.surface else Color.Transparent,
                    shape = RelayShapes.small,
                    shadowElevation = if (active) RelayElevations.Card else 0.dp,
                    modifier = Modifier.weight(1f),
                ) {
                    Box(
                        modifier = Modifier
                            .clickable(enabled = enabled, onClick = onClick)
                            .padding(vertical = sp.sm6),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (active) FontWeight.SemiBold else null,
                            color = when {
                                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                active -> MaterialTheme.colorScheme.onSurface
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}
