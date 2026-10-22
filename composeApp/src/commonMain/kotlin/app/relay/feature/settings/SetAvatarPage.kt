// SPDX-License-Identifier: GPL-3.0-only

package app.relay.feature.settings

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
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.relay.app.RelayUiState
import app.relay.generated.resources.Res
import app.relay.generated.resources.ic_image
import app.relay.generated.resources.ic_lock
import app.relay.generated.resources.ic_key
import app.relay.generated.resources.ic_scissors
import app.relay.generated.resources.ic_warn
import app.relay.generated.resources.set_avatar_card_pick
import app.relay.generated.resources.set_avatar_change
import app.relay.generated.resources.set_avatar_file_meta
import app.relay.generated.resources.set_avatar_hash_hint
import app.relay.generated.resources.set_avatar_hash_title
import app.relay.generated.resources.set_avatar_page_subtitle
import app.relay.generated.resources.set_avatar_page_title
import app.relay.generated.resources.set_avatar_pick_hint
import app.relay.generated.resources.set_avatar_protocol_body
import app.relay.generated.resources.set_avatar_protocol_lead
import app.relay.generated.resources.set_avatar_protocol_title
import app.relay.generated.resources.set_avatar_reselect
import app.relay.generated.resources.set_avatar_save
import app.relay.generated.resources.set_avatar_step1_desc
import app.relay.generated.resources.set_avatar_step1_title
import app.relay.generated.resources.set_avatar_step2_desc
import app.relay.generated.resources.set_avatar_step2_title
import app.relay.generated.resources.set_avatar_step3_desc
import app.relay.generated.resources.set_avatar_step3_title
import app.relay.generated.resources.set_avatar_step4_desc
import app.relay.generated.resources.set_avatar_step4_title
import app.relay.generated.resources.set_avatar_steps_hint
import app.relay.ui.ContentWidths
import app.relay.ui.SectionCard
import app.relay.ui.centerCapped
import app.relay.ui.components.AppButtonSize
import app.relay.ui.components.AppOutlinedButton
import app.relay.ui.components.AppTextButton
import app.relay.ui.components.LargeTitleBar
import app.relay.ui.components.TitleBarDivider
import app.relay.ui.theme.LocalAppSpacing
import app.relay.ui.theme.RelayAvatarGradientFrom
import app.relay.ui.theme.RelayAvatarGradientMid
import app.relay.ui.theme.RelayAvatarGradientTo
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import app.relay.ui.theme.relayCardShadow

/**
 * 设置 · 上传头像（独立页）。
 *
 * 设计真相源：`prototype/relay.html:3734` 的 `screenSetAvatar()`。
 *
 * 这是 `m-set-avatar` 屏的结构缺口补丁：
 * 原型是**整页**（顶栏 + `.avup-pick` 选图 + 四段 `.avstep` + `.kv` 哈希卡 + 「64 个十六进制」+ 「设置」鬼牌），
 * 之前只有一个内嵌在 `SettingsProfileSubpage` 里的 `AvatarPicker` 占位帧，对不上参考图。
 *
 * 把它写成独立 `@Composable` 而不是「在 SettingsScreen 内加分支」的原因：
 * - 顶栏是「上传头像 / 选一张图 → 裁方 → 哈希 → 宣告」，**不是**「我的资料」；
 * - 卡片顺序、键值对与「64 个十六进制」整段都不在资料子页的语义里；
 * - 截图夹具要的是这一屏**本身能渲染**，而不是「带这屏内容的资料子页」——后者只是把 SettingsProfileSubpage 顶了一缺。
 *
 * **当前协议状态**（原型第 4 张卡 `.tt`）：`Profile.avatarHash` 字段已在协议里，
 * 但本端不声明 `cap av1`、从不发 `avatar{op:"get"}`、收到的 data/miss 一律忽略；
 * 所以这一屏是「**UI 通了、协议没通**」的钉子 ——「保存」按协议惯例仍然是空操作，
 * 跟 `SettingsScreen.kt` 已有 `onSaveProfile` 钩子保持一致，留给 av1 接入或 ② 撤档。
 *
 * @param state 给顶栏/卡头的当前值用，本页不直接读 `state.settings.avatar`，那张头像图只在「上传档案」卡里复用 `PeerAvatar`。
 * @param pickedAvatarBytes 自定义头像解码后的字节；当不为 null 时顶栏预览用真图，否则用占位 SVG。
 *   Compose 端不实现图片解码与裁切 —— 这是宿主的事，这一页只负责把 UI 钉住。
 * @param onPickFromAlbum 「选择图片」/「换一张」/「重选」统一走这个钩子，弹系统文件选择框。
 * @param onRemovePicked 移除当前选中的本地图（还原到「还没选图」态）。
 * @param onSaveProfile 与 SettingsProfileSubpage 同形：`(nick, company, dept, team, avatar)`。
 *   上传头像这一屏只关心 `avatar`，其余四个参数保留是为了与既有回调契约对齐；
 *   真实写真档时把当前 `state.settings.*` 原样回传即可。
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
            HashCard()

            // ⑥ 但这一档现在点保存不会生效（协议侧的 tt）
            ProtocolStatusCard()
        }

        // 底部「保存档案」按钮 —— 原型没有这一行，是为了让宿主真正按下「写真档」
        // 时能把回调钉住（之前 `SettingsScreen` 的 save 行是在资料子页里，这里独立
        // 一页没有那个上下文，所以补一个）。
        SaveProfileBar(
            onSave = {
                onSaveProfile(
                    settings.nick,
                    settings.company,
                    settings.dept,
                    settings.team,
                    settings.avatar,
                )
            },
            enabled = pickedAvatarBytes != null,
        )
    }
}

/* ===== ① 选图卡 ===== */

/**
 * 「选择图片」卡：行内一个 52dp 圆头像（占位 SVG / 选中后的真图）+ 文件名/尺寸/类型三件套
 * + 行尾「重选」按钮。原型 `.avup-pick{...gap:11px;padding:10px 12px}`，
 * 对应这里的 `padding(sp.sm6, sp.md)`。整张卡的底色与桌面「重启…」一样走
 * `surfaceVariant` chip（原型 `.avup-pick{border-style:solid}` 说明这条 row 是被强调的），
 * 不抢整张 SectionCard 的 `surface` 底。
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
            Text(
                text = stringResource(Res.string.set_avatar_change),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(onClick = onPick),
            )
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
                    Text(
                        text = "team-photo-1920.jpg",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(Res.string.set_avatar_file_meta),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 原型 `.avup-acts .btn` 即小档（`.btn sm`）：白底 + 1px outline 描边的圆角胶囊。
                // 内边距 / 字号 / 圆角现在由按钮族的小档统一给（原型那颗是 6×9 / 11px；
                // 小档是 6×10 / labelMedium，1px 档位差已在按钮族里记为一处刻意偏离）。
                // 高度仍由 M3 的最小触控高度决定（与桌面设置窗「重试」同一先例），原型画的是 27px。
                AppOutlinedButton(
                    text = stringResource(Res.string.set_avatar_reselect),
                    onClick = onRemove,
                    size = AppButtonSize.Small,
                )
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
        bytes?.let { app.relay.platform.decodeImageBytes(it) }
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
 * 「四步同一条线」步骤卡：每一步 `.avstep` 由左到右是序号圆 + 图标方 + 文案两行，
 * step 之间用 1dp `outlineVariant` 发丝环隔开（原型 `.avstep+.avstep{border-top:1px solid var(--hair)}`）。
 * 末尾再附一条「四条是同一条线上...」的 hint。
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
                icon = Res.drawable.ic_scissors,
                title = stringResource(Res.string.set_avatar_step2_title),
                desc = stringResource(Res.string.set_avatar_step2_desc),
                showDivider = true,
            )
            StepRow(
                index = 3,
                // 原型第三步的 symbol 是 `lock` 不是 `key`（relay.html:3737），
                // 经 `${ic(i)}` 动态调用 —— 曾因静态计数器认不出 `ic(i)` 而被误判未使用。
                icon = Res.drawable.ic_lock,
                title = stringResource(Res.string.set_avatar_step3_title),
                desc = stringResource(Res.string.set_avatar_step3_desc),
                showDivider = true,
            )
            StepRow(
                index = 4,
                icon = Res.drawable.ic_key,
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
 * **注意这里没有「四个 mini 头像 + 设置」那一排。** 早先一版把它画在卡里，是把原型的
 * **底导航**（`.dnav`：消息 / 文件柜 / 节点 / Agent / 设置，`relay.html:2637 dnav()`）
 * 误读成了页面内容 —— 截图 `prototype/screenshots/relay/m13a2-set-avatar.png` 底部那一排
 * 就是底导航，`screenSetAvatar()`（`relay.html:3734`）正文里根本没有这一行。
 * 底导航属于外壳 chrome，由截图夹具的 `Screen(...)`（`androidApp/src/screenshotTest/kotlin/ScreenshotPreviewsExtra.kt` →
 * `CompactScaffold`）负责画，不该出现在页面 body 里。
 */
@Composable
private fun HashCard() {
    val sp = LocalAppSpacing.current

    SectionCard(
        title = stringResource(Res.string.set_avatar_hash_title),
        icon = Res.drawable.ic_key,
        trailing = null,
    ) {
        KvRow("profile.avatarHash", "3f9c1b…e1a7")
        KvRow("profileRev", "12 → 13")
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
 * 原型第 4 张卡的「但这一档现在点保存不会生效」段落 —— 协议侧还没跟上
 * (`MsgTypes.UNHANDLED` 还写着 `AVATAR`)，这一屏是「**UI 通了、协议没通**」的钉子。
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

/**
 * 底部固定的「保存档案」行：选图前 disabled（pickedAvatarBytes == null），
 * 选中后高亮 primary。原型没有这行 —— 因为原型把"写真档"塞进了 step 4 的描述里。
 * Compose 侧把这一步做成显式按钮，是为了让 `onSaveProfile` 这条回调路径能真正按下去；
 * 不然截图夹具没法证明「这屏能保存」。
 */
@Composable
private fun SaveProfileBar(onSave: () -> Unit, enabled: Boolean) {
    val sp = LocalAppSpacing.current
    Column(Modifier.fillMaxWidth()) {
        // 底栏的上边缘：原型里固定底栏与内容之间靠一条发丝线（--hair）分开，
        // 不是「底色比内容深一档」。海拔染色会把白底染成偏绿的 #F4F7F6，
        // 方向恰好与原型相反（见 Color.kt 里 surfaceTint 那段）。
        TitleBarDivider()
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = sp.lg, vertical = sp.md),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppTextButton(
                    text = stringResource(Res.string.set_avatar_save),
                    onClick = onSave,
                    enabled = enabled,
                )
            }
        }
    }
}

/* ===== 复用工具 ===== */

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
