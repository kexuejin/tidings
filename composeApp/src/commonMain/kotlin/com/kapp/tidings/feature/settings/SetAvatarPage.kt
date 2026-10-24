// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kapp.tidings.domain.RelayUiState
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.common_remove
import com.kapp.tidings.generated.resources.ic_image
import com.kapp.tidings.generated.resources.ic_lock
import com.kapp.tidings.generated.resources.ic_key
import com.kapp.tidings.generated.resources.ic_save
import com.kapp.tidings.generated.resources.ic_warn
import com.kapp.tidings.generated.resources.set_avatar_card_pick
import com.kapp.tidings.generated.resources.set_avatar_change
import com.kapp.tidings.generated.resources.set_avatar_file_size
import com.kapp.tidings.generated.resources.set_avatar_hash_empty
import com.kapp.tidings.generated.resources.set_avatar_hash_hint
import com.kapp.tidings.generated.resources.set_avatar_hash_title
import com.kapp.tidings.generated.resources.set_avatar_none_picked
import com.kapp.tidings.generated.resources.set_avatar_page_subtitle
import com.kapp.tidings.generated.resources.set_avatar_page_title
import com.kapp.tidings.generated.resources.set_avatar_pick_hint
import com.kapp.tidings.generated.resources.set_avatar_picked_label
import com.kapp.tidings.generated.resources.set_avatar_protocol_body
import com.kapp.tidings.generated.resources.set_avatar_protocol_lead
import com.kapp.tidings.generated.resources.set_avatar_protocol_title
import com.kapp.tidings.generated.resources.set_avatar_reselect
import com.kapp.tidings.generated.resources.set_avatar_save
import com.kapp.tidings.generated.resources.set_avatar_saved_inline
import com.kapp.tidings.generated.resources.set_avatar_step1_desc
import com.kapp.tidings.generated.resources.set_avatar_step1_title
import com.kapp.tidings.generated.resources.set_avatar_step2_desc
import com.kapp.tidings.generated.resources.set_avatar_step2_title
import com.kapp.tidings.generated.resources.set_avatar_step3_desc
import com.kapp.tidings.generated.resources.set_avatar_step3_title
import com.kapp.tidings.generated.resources.set_avatar_step4_desc
import com.kapp.tidings.generated.resources.set_avatar_step4_title
import com.kapp.tidings.generated.resources.set_avatar_steps_hint
import com.kapp.tidings.generated.resources.set_invalid_nick
import com.kapp.tidings.ui.ContentWidths
import com.kapp.tidings.ui.SectionCard
import com.kapp.tidings.ui.centerCapped
import com.kapp.tidings.ui.components.AppButtonSize
import com.kapp.tidings.ui.components.AppOutlinedButton
import com.kapp.tidings.ui.components.AppTextButton
import com.kapp.tidings.ui.components.LargeTitleBar
import com.kapp.tidings.ui.components.TitleBarDivider
import com.kapp.tidings.ui.components.formatAvatarByteCount
import com.kapp.tidings.ui.theme.LocalAppSpacing
import com.kapp.tidings.ui.theme.RelayAvatarGradientFrom
import com.kapp.tidings.ui.theme.RelayAvatarGradientMid
import com.kapp.tidings.ui.theme.RelayAvatarGradientTo
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import com.kapp.tidings.ui.theme.relayCardShadow

/**
 * 设置 · 上传头像（独立页）。
 *
 * 设计真相源：`prototype/relay.html:3734` 的 `screenSetAvatar()`。
 *
 * **这一页只描述现状，不承诺目标态。** 原型的「裁方 → 编码 → 宣告」管线在 Compose 端
 * 尚未实现（Compose 端不做图片裁切与转码），所以顶栏副标题、四段步骤卡与 hint 只说
 * 真实发生的三件事：选图 → 本机算哈希 → 保存档案；裁剪 / webp / 192×192 / 宣告这些
 * **未实现的目标态**一个字都不写进文案。
 *
 * 页面上每一个读数都必须是真的：
 * - 「大小」来自 `pickedAvatarBytes.size`（真实字节，见 [formatAvatarByteCount]）；
 * - 哈希卡显示控制器在选中那一刻真算出来的 sha256（`RelayUiState.pickedAvatarHash`）；
 * - 「保存档案」走真的 `onSaveProfile`（只写资料字段；刚选的图**不**随档案保存 ——
 *   这一点由页面自己的「协议状态」卡对用户明说），结果在保存栏**行内**显示
 *   （这一页看不到全局 `settingsNote` 横幅）。
 *
 * @param state 给顶栏/卡头的当前值用；哈希卡读 `state.pickedAvatarHash` 与
 *   `state.settings.profileRev`。
 * @param pickedAvatarBytes 自定义头像解码后的字节；当不为 null 时顶栏预览用真图，否则用占位 SVG。
 *   Compose 端不实现图片解码与裁切 —— 这是宿主的事，这一页只负责把 UI 钉住。
 * @param onPickFromAlbum 「选择图片」/「换一张」/「重选」统一走这个钩子，弹系统文件选择框。
 * @param onRemovePicked 与宿主既有调用点保持兼容的形参；本页当前的「重选」走 [onPickFromAlbum]
 *   （换一张），移除入口在资料页的头像分段控件上 —— 这一页不提供第二个动作位。
 * @param onSaveProfile 与 SettingsProfileSubpage 同形：`(nick, company, dept, team, avatar)`。
 *   上传头像这一屏把当前 `state.settings.*` 原样回传（它没有表单字段可改）。
 * @param onBack 非 null 时顶栏左侧画返回键。截图夹具可以传 null。
 */
@Composable
public fun SetAvatarPage(
    state: RelayUiState,
    pickedAvatarBytes: ByteArray? = null,
    onPickFromAlbum: () -> Unit = {},
    onRemovePicked: () -> Unit = {},
    onSaveProfile: (String, String, String, String, Int) -> Unit = { _, _, _, _, _ -> },
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val sp = LocalAppSpacing.current
    val settings = state.settings

    Column(modifier = modifier.fillMaxSize()) {
        LargeTitleBar(
            title = stringResource(Res.string.set_avatar_page_title),
            subtitle = stringResource(Res.string.set_avatar_page_subtitle),
            onBack = onBack,
            contentMaxWidth = ContentWidths.Settings,
        )
        TitleBarDivider()

        Column(
            modifier = Modifier
                .weight(1f)
                // 宽屏收列（原型 `.pad.set{max-width:640px;margin:0 auto}`，:1045），
                // 跟 SettingsProfileSubpage 同源：先夹再滚，滚动条、overscroll、内容列
                // 三者重合，不会出现"能滚的比能看的宽"。
                .centerCapped(ContentWidths.Settings)
                .verticalScroll(rememberScrollState())
                .padding(
                    start = sp.lg,
                    top = sp.lg14,
                    end = sp.lg,
                    bottom = sp.lg,
                ),
            verticalArrangement = Arrangement.spacedBy(sp.md),
        ) {
            // ① 选图卡（`.avup-pick` + hint）
            PickImageCard(
                pickedAvatarBytes = pickedAvatarBytes,
                onPick = onPickFromAlbum,
                onRemove = onRemovePicked,
            )

            // ②/③/④ 四段步骤卡
            StepsCard()

            // ⑤ 线上只走这 64 个十六进制
            HashCard(state = state)

            // ⑥ 保存生效、但头像图本身还不上网（协议侧的 tt）
            ProtocolStatusCard()
        }

        // 底部「保存档案」按钮 —— 原型没有这一行，是为了让宿主真正按下「写真档」
        // 时能把回调钉住（之前 `SettingsScreen` 的 save 行是在资料子页里，这里独立
        // 一页没有那个上下文，所以补一个）。
        SaveProfileBar(
            nickBlank = settings.nick.isBlank(),
            profileRev = settings.profileRev,
            onSave = {
                onSaveProfile(
                    settings.nick,
                    settings.company,
                    settings.dept,
                    settings.team,
                    settings.avatar,
                )
            },
        )
    }
}

/* ===== ① 选图卡 ===== */

/**
 * 「选择图片」卡：行内一个 52dp 圆头像（占位 SVG / 选中后的真图）+ 名称/大小两行
 * + 行尾「重选」按钮。原型 `.avup-pick{...gap:11px;padding:10px 12px}`，
 * 对应这里的 `padding(sp.sm6, sp.md)`。整张卡的底色与桌面「重启…」一样走
 * `surfaceVariant` chip（原型 `.avup-pick{border-style:solid}` 说明这条 row 是被强调的），
 * 不抢整张 SectionCard 的 `surface` 底。
 *
 * **名称与大小只写真话**：控制器选图时只把字节与哈希存进状态，原始文件名没有落进
 * 任何 UI 可达的地方 —— 所以这里不编一个 `team-photo-1920.jpg`，选了图就说「本机图片」，
 * 大小给真实字节；宽高 / 格式拿不到，就不显示。
 */
@Composable
private fun PickImageCard(
    pickedAvatarBytes: ByteArray?,
    onPick: () -> Unit,
    onRemove: () -> Unit,
) {
    val sp = LocalAppSpacing.current

    SectionCard(
        title = stringResource(Res.string.set_avatar_card_pick),
        icon = Res.drawable.ic_image,
        trailing = {
            // 原型 `.card>h3 .r{margin-left:auto;color:var(--on-2);font-weight:500;font-size:11px}`：
            // 卡头右上角是一个 11px/500 的次级文字出口，不是一颗绿字按钮。
            // 「换一张」只在真的有一张图时成立（没有图时它是谎话）；即使是一枚小字，
            // 也按按钮报（Role.Button）并保证最小触达 —— 小不是点不到的理由。
            if (pickedAvatarBytes != null) {
                Text(
                    text = stringResource(Res.string.set_avatar_change),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clickable(onClick = onPick, role = Role.Button),
                )
            }
        },
    ) {
        // 原型 `.avup-pick` 是 surfaceVariant 底 + 1px solid 描边，
        // 比 SectionCard 的 surface 底更"按下去一点"，正好对应"这张是被选中的那一张"。
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = MaterialTheme.shapes.medium,
                    )
                    .padding(horizontal = sp.md, vertical = sp.md10),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(sp.md),
            ) {
                PickedPreview(size = 52.dp, bytes = pickedAvatarBytes)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(sp.xxs),
                ) {
                    if (pickedAvatarBytes != null) {
                        Text(
                            text = stringResource(Res.string.set_avatar_picked_label),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            // 真实字节数换算的可读大小；宽高 / 格式拿不到就不显示。
                            text = stringResource(
                                Res.string.set_avatar_file_size,
                                formatAvatarByteCount(pickedAvatarBytes.size),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            text = stringResource(Res.string.set_avatar_none_picked),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // 原型 `.avup-acts .btn` 即小档（`.btn sm`）：白底 + 1px outline 描边的圆角胶囊。
                // 「重选」按的是「再弹一次选择框」（onPick），不是移除 —— 按钮的字面与行为一致。
                // 没选图时这颗是「选择图片」（这一页唯一的选图入口）。
                AppOutlinedButton(
                    text = stringResource(
                        if (pickedAvatarBytes != null) Res.string.set_avatar_reselect else Res.string.set_avatar_card_pick,
                    ),
                    onClick = onPick,
                    size = AppButtonSize.Small,
                )
                // 「移除」：选了图之后回到"还没选图"态。此前这一页只有「重选/选择」，
                // 而 `onRemovePicked` 是个没人调用的死参数 —— 选错图只能再选一张盖掉，
                // 想真正回到未选态得退回资料页。这里把它接上（同一份 domain 动作）。
                if (pickedAvatarBytes != null) {
                    AppTextButton(
                        text = stringResource(Res.string.common_remove),
                        onClick = onRemove,
                        size = AppButtonSize.Small,
                    )
                }
            }
        }
        Spacer(Modifier.height(sp.sm))
        HintText(
            html = stringResource(Res.string.set_avatar_pick_hint),
        )
    }
}

/**
 * 选图预览圆：选中后用 `decodeImageBytes` 解出真图（这是宿主已经实现的解码），否则
 * 用一个蓝紫渐变底 + 「山」形 SVG 占位（原型 `avPhotoSVG` 的笔画痕迹）。
 */
@Composable
private fun PickedPreview(size: androidx.compose.ui.unit.Dp, bytes: ByteArray?) {
    val bitmap = androidx.compose.runtime.remember(bytes) {
        bytes?.let { com.kapp.tidings.platform.decodeImageBytes(it) }
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                // 原型 `.avup-pv{background:linear-gradient(140deg,#9DB4C9 0%,#5E86AC 48%,#3D8B6B 100%)}`
                // （prototype/relay.html:1283）。**逐值取原型**，不是自拟的蓝紫渐变 ——
                // 早先那版写的是 #5A7BFF/#8B5CF6，是凭空造的两个色，属于保真事故。
                Brush.linearGradient(
                    colors = listOf(
                        RelayAvatarGradientFrom,
                        RelayAvatarGradientMid,
                        RelayAvatarGradientTo,
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.size(size).clip(CircleShape),
            )
        } else {
            // 占位 SVG —— 原型 `avPhotoSVG`：圆点 + 山形路径。
            androidx.compose.foundation.Canvas(modifier = Modifier.size(size * 0.62f)) {
                val stroke = 1.7f.dp.toPx()
                val w = size.toPx()
                val s = w * 0.62f
                drawCircle(
                    color = Color.White,
                    radius = s * 0.07f,
                    center = androidx.compose.ui.geometry.Offset(s * 0.37f, s * 0.39f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
                )
                // 山形：左下 → 左中 → 中峰 → 右中 → 右下（M3 -1,-1 / 4,-4 / 8.5,5 / 12,-2.5 / 18,8 / 24,15）
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(s * 0.13f, s * 0.83f)
                    lineTo(s * 0.40f, s * 0.57f)
                    lineTo(s * 0.57f, s * 0.73f)
                    lineTo(s * 0.68f, s * 0.63f)
                    lineTo(s * 0.87f, s * 0.82f)
                    close()
                }
                drawPath(
                    path = path,
                    color = Color.White,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = stroke,
                        join = androidx.compose.ui.graphics.StrokeJoin.Round,
                    ),
                )
            }
        }
    }
}

/* ===== ②/③/④ 步骤卡 ===== */

/**
 * 「四段现状」步骤卡：每一步 `.avstep` 由左到右是序号圆 + 图标方 + 文案两行，
 * step 之间用 1dp `outlineVariant` 发丝环隔开（原型 `.avstep+.avstep{border-top:1px solid var(--hair)}`）。
 * 末尾再附一条「以上是这一页现在的真实流程…」的 hint。
 *
 * 四段说的都是**现状**（选图 / 算哈希 / 只存本机 / 保存档案字段）；原型的「裁方 →
 * 编码 webp → 宣告」是未实现的目标态，不进这四段 —— 目标态与现状混排，
 * 用户就分不清哪句是承诺哪句是事实。
 */
@Composable
private fun StepsCard() {
    val sp = LocalAppSpacing.current

    // 原型 `.card > .padc`：这一张卡**没有标题**，而 [SectionCard] 的 `title` 是必填且
    // 总画一行标题（空串也会留一行 + 一段 bottom padding）。所以这里直接用 Card 复刻
    // SectionCard 的视觉（surface 底 + 自绘 `--shadow` 投影 + `shapes.medium`），
    // 自己管内边距，避免那行空标题把步骤卡的留白撑歪。
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .relayCardShadow(shape = MaterialTheme.shapes.medium),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = MaterialTheme.shapes.medium,
    ) {
        // 内边距收紧到 `sp.md`，对齐原型 `.card > .padc`（SectionCard 用的是 `sp.lg`）。
        Column(modifier = Modifier.padding(horizontal = sp.md, vertical = sp.md)) {
            StepRow(
                index = 1,
                icon = Res.drawable.ic_image,
                title = stringResource(Res.string.set_avatar_step1_title),
                desc = stringResource(Res.string.set_avatar_step1_desc),
                showDivider = false,
            )
            StepRow(
                index = 2,
                icon = Res.drawable.ic_key,
                title = stringResource(Res.string.set_avatar_step2_title),
                desc = stringResource(Res.string.set_avatar_step2_desc),
                showDivider = true,
            )
            StepRow(
                index = 3,
                icon = Res.drawable.ic_lock,
                title = stringResource(Res.string.set_avatar_step3_title),
                desc = stringResource(Res.string.set_avatar_step3_desc),
                showDivider = true,
            )
            StepRow(
                index = 4,
                icon = Res.drawable.ic_save,
                title = stringResource(Res.string.set_avatar_step4_title),
                desc = stringResource(Res.string.set_avatar_step4_desc),
                showDivider = true,
            )
            Spacer(Modifier.height(sp.md))
            HintText(
                html = stringResource(Res.string.set_avatar_steps_hint),
            )
        }
    }
}

/** `.avstep`：序号 19dp 圆 + 30dp 圆角方图标 + 两行文案。 */
@Composable
private fun StepRow(
    index: Int,
    icon: DrawableResource,
    title: String,
    desc: String,
    showDivider: Boolean,
) {
    val sp = LocalAppSpacing.current
    if (showDivider) {
        // 原型 `.avstep+.avstep{border-top:1px solid var(--hair)}`：这条线就在两行之间，
        // 上下没有额外留白，留白全在 `.avstep{padding:11px 2px 12px}` 里。
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = sp.md),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(sp.md),
    ) {
        // 序号圆：`.asn{width:19px;height:19px;border-radius:50%;background:var(--primary-c);color:#fff;font-size:11px}`
        Box(
            modifier = Modifier
                .size(19.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = index.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.SemiBold,
            )
        }
        // 图标方：`.asi{width:30px;height:30px;border-radius:9px;background:var(--surface-2)}`
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        // 文案两行：`.asb .t1{14/600}` + `.asb .t2{11/--on-2/1.5}`
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(sp.xs / 2),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = desc,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp,
            )
        }
    }
}

/* ===== ⑤ 哈希卡 ===== */

/**
 * 「线上只走这 64 个十六进制」卡：`.kv` 两行（profile.avatarHash / profileRev）+ 一段 hint。
 *
 * 两个值都是**真读数**：
 * - `profile.avatarHash` = 控制器在选中那一刻真算出来的 sha256
 *   （`RelayUiState.pickedAvatarHash`，截前 8 位显示）；还没选图就明说「尚未选图」，
 *   不再编一个 `3f9c1b…e1a7` 出来。
 * - `profileRev` = 当前档案版本。保存后版本由控制器 +1（见 [avatarSaveFeedback]），
 *   这一卡不预支那个「+1」。
 *
 * **注意这里没有「四个 mini 头像 + 设置」那一排。** 早先一版把它画在卡里，是把原型的
 * **底导航**（`.dnav`：消息 / 文件柜 / 节点 / Agent / 设置，`relay.html:2637 dnav()`）
 * 误读成了页面内容 —— 截图 `prototype/screenshots/relay/m13a2-set-avatar.png` 底部那一排
 * 就是底导航，`screenSetAvatar()`（`relay.html:3734`）正文里根本没有这一行。
 * 底导航属于外壳 chrome，由截图夹具的 `Screen(...)`（`androidApp/src/screenshotTest/kotlin/ScreenshotPreviewsExtra.kt` →
 * `CompactScaffold`）负责画，不该出现在页面 body 里。
 */
@Composable
private fun HashCard(state: RelayUiState) {
    val sp = LocalAppSpacing.current
    val pickedHash = state.pickedAvatarHash

    SectionCard(
        title = stringResource(Res.string.set_avatar_hash_title),
        icon = Res.drawable.ic_key,
        trailing = null,
    ) {
        KvRow(
            key = "profile.avatarHash",
            value = pickedHash?.let { it.take(8) + "…" } ?: stringResource(Res.string.set_avatar_hash_empty),
        )
        KvRow("profileRev", state.settings.profileRev.toString())
        Spacer(Modifier.height(sp.sm6))
        HintText(
            html = stringResource(Res.string.set_avatar_hash_hint),
        )
    }
}

/** `.kv` 一行：键名 `onSurfaceVariant` + 值（哈希用 mono 字体）。 */
@Composable
private fun KvRow(key: String, value: String) {
    val sp = LocalAppSpacing.current
    val isHash = value.contains("…") || value.length > 8
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = sp.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = key,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = if (isHash) {
                MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
            } else {
                MaterialTheme.typography.bodyMedium
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/* ===== ⑥ 协议侧 tt 卡 ===== */

/**
 * 原型第 4 张卡的协议段：**保存本身是生效的**（写资料字段并回 `profileRev`，
 * 见 [SaveProfileBar] 的行内回执），还不上网的是**头像图本身** ——
 * 图片同步链路尚未实现（`MsgTypes.UNHANDLED` 还写着 `AVATAR`）。
 * 这一屏是「**保存通了、图片同步没通**」的钉子：标题不许再说「点保存不会生效」，
 * 那是同一屏里被下面那行回执当场打脸的谎。
 */
@Composable
private fun ProtocolStatusCard() {
    val sp = LocalAppSpacing.current

    SectionCard(
        title = stringResource(Res.string.set_avatar_protocol_title),
        icon = Res.drawable.ic_warn,
        trailing = null,
    ) {
        // 原型这一段是 `.padc > .tt` 的纯文本（`prototype/relay.html:3789`）：
        // 没有内层卡、没有底色、没有圆角、也没有额外内边距 —— 卡自己的 `.padc` 已经给过留白。
        Column {
            Text(
                text = stringResource(Res.string.set_avatar_protocol_lead),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(sp.xs))
            Text(
                text = stringResource(Res.string.set_avatar_protocol_body),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp,
            )
        }
    }
}

/* ===== 底部保存栏 ===== */

/** 「保存档案」这一行的行内结果（这一页看不到全局 `settingsNote` 横幅）。 */
internal sealed interface AvatarSaveFeedback {
    data object Idle : AvatarSaveFeedback

    /** 昵称为空：控制器会拒收（`NickEmpty`），按钮在保存前就置灰并给出这句。 */
    data object NickRequired : AvatarSaveFeedback

    /** 保存生效：控制器把 `profileRev` +1 —— 那就是成功的真凭据。 */
    data class Saved(val rev: Int) : AvatarSaveFeedback
}

/**
 * 保存结果的判定（纯函数，可单测）。
 *
 * 成功的判据是 **profileRev 真的涨了**（控制器 `handleSaveProfile` 对非空昵称恒 +1），
 * 不猜时间、不看横幅。昵称为空时无论 rev 怎样都先报 [AvatarSaveFeedback.NickRequired] ——
 * 那次保存注定被拒，不能把它显示成成功。
 */
internal fun avatarSaveFeedback(nickBlank: Boolean, revBefore: Int?, revNow: Int): AvatarSaveFeedback = when {
    nickBlank -> AvatarSaveFeedback.NickRequired
    revBefore != null && revNow > revBefore -> AvatarSaveFeedback.Saved(revNow)
    else -> AvatarSaveFeedback.Idle
}

/**
 * 底部固定的「保存档案」行 + **行内结果**。
 *
 * 只有昵称为空才 disabled（控制器会拒收那次保存）；**没有选图也能保存** ——
 * 这个按钮写的是资料字段（昵称 / 公司 / 部门 / 团队 / 头像模式），不是那张图的正文。
 * 点下后按钮不再无反馈：控制器把 `profileRev` +1 的那一刻，行内出现
 * 「已保存，资料版本 v…；刚选的这张图还没有随档案保存」—— 成功与限度一句话说全。
 * 原型没有这行 —— 因为原型把"写真档"塞进了 step 4 的描述里。Compose 侧把这一步做成
 * 显式按钮，是为了让 `onSaveProfile` 这条回调路径能真正按下去。
 *
 * @param nickBlank 当前昵称是否为空（为空 = 控制器必拒收）。
 * @param profileRev 当前档案版本；涨了就是保存成功的凭据。
 */
@Composable
private fun SaveProfileBar(
    nickBlank: Boolean,
    profileRev: Int,
    onSave: () -> Unit,
) {
    val sp = LocalAppSpacing.current
    // 点保存那一刻记下 rev；控制器把 rev 顶上去之后，[avatarSaveFeedback] 就落在 Saved。
    var revBefore by remember { mutableStateOf<Int?>(null) }
    val feedback = avatarSaveFeedback(nickBlank, revBefore, profileRev)

    Column(Modifier.fillMaxWidth()) {
        // 底栏的上边缘：原型里固定底栏与内容之间靠一条发丝线（--hair）分开，
        // 不是「底色比内容深一档」。海拔染色会把白底染成偏绿的 #F4F7F6，
        // 方向恰好与原型相反（见 Color.kt 里 surfaceTint 那段）。
        TitleBarDivider()
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = sp.lg, vertical = sp.md),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppTextButton(
                        text = stringResource(Res.string.set_avatar_save),
                        onClick = {
                            revBefore = profileRev
                            onSave()
                        },
                        enabled = !nickBlank,
                    )
                }
                when (feedback) {
                    AvatarSaveFeedback.Idle -> Unit
                    AvatarSaveFeedback.NickRequired -> Text(
                        text = stringResource(Res.string.set_invalid_nick),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = sp.xs),
                    )
                    is AvatarSaveFeedback.Saved -> Text(
                        text = stringResource(Res.string.set_avatar_saved_inline, feedback.rev),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = sp.xs),
                    )
                }
            }
        }
    }
}

/* ===== 复用工具 ===== */

/*
 * formatAvatarByteCount / decimalAtMostOne 挪去了 ui/components/AvatarPalette.kt：
 * 资料页选图卡（AvatarPicker）也要用同一份格式化显示真实大小，两个屏必须同源，
 * 共享层不能反向 import feature/。本页与 SettingsHonestyTest 都从那边 import。
 */

/**
 * 原型 `.hint`：单段长 hint，**不实现** `<b>` 解析 —— Compose 端直接把整段塞进去
 * 让读者认字即可，「`b` 加粗」是排版细节不是结构信息，这一处选择先丢。
 * 若日后要还原，再加一段 `AnnotatedString` 解析。
 */
@Composable
private fun HintText(html: String) {
    val sp = LocalAppSpacing.current
    Text(
        text = html.replace("<b>", "").replace("</b>", ""),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        lineHeight = 16.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = sp.sm),
    )
}
