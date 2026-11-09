// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.chat

// 聊天里的**文件卡**（`FileCardView`）及其文件域纯件，从 `ChatBubbles.kt` 搬出来（ARP-14）。
//
// ## 为什么单独一个文件
//
// 它画的是**文件域**的内容（文件名/大小/进度/接收-暂停-续传/失败原因），却因为"嵌在气泡里"
// 一直住在聊天气泡那个文件：于是 `ChatBubbles.kt` 1517 行里，456 行在讲文件传输，和
// "一条消息长什么样"没有关系。改气泡的人必须先滚过整个文件卡；改文件卡的人要先在
// 气泡里找到它。
//
// ## 搬了什么，为什么搬得动
//
// 搬的是 `FileCardView` + 五个**只有它用**的件（两个扩展名集合、路径中间省略、
// 速率末段、是否显示「接收」、失败码翻译）。它们在全仓的其它调用点数是 0 —— 这不是
// "顺手多搬一点"，而是搬走的必须是一整块，否则要么留下一半在气泡文件里、要么得为了
// 跨文件访问把 `private` 放宽成 `internal`（那是纯代价，没有收益）。
//
// 包名不变（`com.kapp.tenon.feature.chat`）：它仍然是聊天气泡的一部分，只是换了文件。
// 因此 `FileCardStyleTest` 等既有测试一行不用改。

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kapp.tenon.core.protocol.OfferDeliveryStates
import com.kapp.tenon.domain.MediaKind
import com.kapp.tenon.domain.mediaKindOf
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.platform.ReceivedFileActions
import com.kapp.tenon.ui.components.formatBytes
import com.kapp.tenon.ui.theme.IconSizes
import com.kapp.tenon.ui.theme.LocalAppSpacing
import com.kapp.tenon.ui.theme.accents
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import com.kapp.tenon.domain.model.FileCardUi
import com.kapp.tenon.domain.model.TransferState
import com.kapp.tenon.generated.resources.common_cancel
import com.kapp.tenon.generated.resources.common_unknown
import com.kapp.tenon.generated.resources.chat_file_send_failed_hint
import com.kapp.tenon.generated.resources.file_accept
import com.kapp.tenon.generated.resources.file_bubble_count
import com.kapp.tenon.generated.resources.file_decline
import com.kapp.tenon.generated.resources.file_open
import com.kapp.tenon.generated.resources.file_saved_to
import com.kapp.tenon.generated.resources.file_reason_offer_expired
import com.kapp.tenon.generated.resources.file_reason_peer_offline
import com.kapp.tenon.generated.resources.file_reason_hash_mismatch
import com.kapp.tenon.generated.resources.file_reason_size_mismatch
import com.kapp.tenon.generated.resources.file_reason_write_error
import com.kapp.tenon.generated.resources.file_reason_path_escape
import com.kapp.tenon.generated.resources.file_reason_busy
import com.kapp.tenon.generated.resources.file_reason_canceled
import com.kapp.tenon.generated.resources.file_reason_not_found
import com.kapp.tenon.generated.resources.file_reason_bad_frame
import com.kapp.tenon.generated.resources.file_reason_socket_error
import com.kapp.tenon.generated.resources.file_reason_timeout
import com.kapp.tenon.generated.resources.file_share
import com.kapp.tenon.generated.resources.file_state_active_in
import com.kapp.tenon.generated.resources.file_state_active_out
import com.kapp.tenon.generated.resources.file_state_completed_in
import com.kapp.tenon.generated.resources.file_state_sent_success
import com.kapp.tenon.generated.resources.file_state_declined
import com.kapp.tenon.generated.resources.file_state_failed
import com.kapp.tenon.generated.resources.file_state_offered_auto
import com.kapp.tenon.generated.resources.file_state_offered_in
import com.kapp.tenon.generated.resources.file_state_offered_out
import com.kapp.tenon.generated.resources.file_table_hide_text
import com.kapp.tenon.generated.resources.file_table_show_text
import com.kapp.tenon.generated.resources.file_table_truncated
import com.kapp.tenon.generated.resources.ic_check_double
import com.kapp.tenon.generated.resources.ic_close
import com.kapp.tenon.generated.resources.ic_file
import com.kapp.tenon.generated.resources.ic_folder
import com.kapp.tenon.generated.resources.ic_image
import com.kapp.tenon.generated.resources.ic_speaker
import com.kapp.tenon.generated.resources.ic_terminal
import com.kapp.tenon.generated.resources.r2_chat_folder_progress
import com.kapp.tenon.generated.resources.r2_chat_folder_receiving
import com.kapp.tenon.generated.resources.r2_chat_folder_sending
import com.kapp.tenon.generated.resources.transfer_action_resume
import com.kapp.tenon.generated.resources.transfer_folder_count
import com.kapp.tenon.generated.resources.transfer_resume_hint
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.kapp.tenon.ui.theme.RelayShapes
import com.kapp.tenon.ui.theme.ShapeRoles
import com.kapp.tenon.ui.components.AppButton
import com.kapp.tenon.ui.components.AppButtonSize
import com.kapp.tenon.ui.components.AppButtonTone
import com.kapp.tenon.ui.components.AppIconButton
import com.kapp.tenon.ui.components.AppTextButton

private val CODE_FILE_EXTENSIONS = setOf(
    "kt", "kts", "rs", "js", "ts", "json", "py", "java", "c", "cpp", "h", "go",
    "sh", "sql", "diff", "patch", "html", "css", "yaml", "yml", "xml", "toml",
)
private val ARCHIVE_FILE_EXTENSIONS = setOf("zip", "tar", "gz", "tgz", "7z", "rar", "bz2")

/**
 * 文件气泡。
 *
 * 与文字气泡共用同一条消息流：一次 transfer 只产生一条消息，进度事件原地更新它，
 * 于是「发文件」和「发消息」在会话里是同一种东西，不需要第二套列表，也不会两处对不上。
 */
@Composable
internal fun FileCardView(
    card: FileCardUi,
    onAcceptTransfer: (String) -> Unit,
    onDeclineTransfer: (String) -> Unit,
    /**
     * 撤回这次传输。null = 这个壳没有撤回能力，按钮就不画。
     *
     * 只在 [FileCardUi.mine] 为真时才用得上：core 的 `TransferManager.cancelOutgoing`
     * 动的是**出站**表，收文件那一侧没有取消接口，给收件人一个取消按钮只会点了没反应。
     */
    onCancel: (() -> Unit)? = null,
    /** 续传失败的入站文件。 */
    onResumeTransfer: ((String) -> Unit)? = null,
    /**
     * 打开 / 分享**收到的**文件。null = 本平台没有这条出口，那两个按钮都不画。
     *
     * 只对收到的文件有意义：它们落在应用内部目录，用户没有别的办法把它取出来。
     */
    receivedFileActions: ReceivedFileActions? = null,
    onImageClick: ((String) -> Unit)? = null,
) {
    val sp = LocalAppSpacing.current
    // 文件卡**自己就是气泡**：外层容器透明、`bubblePad = 0`（见 `chatBubbleShape`），
    // 所以这里的底色与圆角必须与文字气泡逐值相同 —— `--bubble-peer` / `--bubble-mine`
    // + `--r-bubble`（16dp），不画描边。曾经这里是白底 + 0.5dp 发丝边 + 12dp：那套在
    // 「气泡去边框」裁决时被漏掉（文件卡属于 `isCustomCard`，外层只删了 `bubbleBorder`，
    // 内层这层自己画的边没人碰），于是同一屏里文件卡带边、文字气泡不带。
    // 原型 `.filecard` 同步改成 bubble-peer / 无边框 / `var(--r-bubble)`；间距与图标尺寸不变
    // （`.filecard{display:flex;gap:10px;align-items:center;padding:9px 10px;min-width:196px}`，
    // `.win .filecard` 是 210）。文件夹走的是同一个 `.filecard`（`folderCard`），区别只在
    // `.fi` 里那颗图标 —— 所以「文件夹」这三个字不再另画一枚徽章：下面 `.fs` 那行本来就
    // 有「N 个文件 · 大小」，徽章是同一件事的第二个说法。
    Surface(
        // 底色按**归属**分，与文字气泡同一套语言：自己的走 `--bubble-mine`（`primaryContainer`），
        // 对端的走 `--bubble-peer`（`surfaceVariant`）。只给 `surfaceVariant` 会让「我发的文件」
        // 比「我发的文字」暗一档 —— 同一侧两种底色，比原来的白卡 + 发丝边更不一致。
        color = if (card.mine) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = RelayShapes.large,
        modifier = Modifier.widthIn(min = 196.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = sp.md10, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(sp.md10),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // `.filecard .fi{width:34px;height:34px;border-radius:9px;background:var(--primary-c);
            // color:var(--on-primary-c)}` + `.fi svg{18px}`。
            // 语义化区分：代码文件紫调（ic_terminal + agentAccent）、压缩包 teal 调（tertiaryContainer）、
            // 文件夹琥珀调（ic_folder + warn）、音频（ic_speaker + secondaryContainer）、媒体与文档（primaryContainer）。
            val ext = card.name.substringAfterLast('.', "").lowercase()
            val (fiIcon, fiContainer, fiContent) = when {
                card.isFolder -> Triple(
                    Res.drawable.ic_folder,
                    MaterialTheme.accents.warn.copy(alpha = 0.16f),
                    MaterialTheme.accents.warn,
                )
                ext in CODE_FILE_EXTENSIONS -> Triple(
                    Res.drawable.ic_terminal,
                    MaterialTheme.accents.agentAccentContainer,
                    MaterialTheme.accents.agentAccent,
                )
                ext in ARCHIVE_FILE_EXTENSIONS -> Triple(
                    Res.drawable.ic_file,
                    MaterialTheme.colorScheme.tertiaryContainer,
                    MaterialTheme.colorScheme.onTertiaryContainer,
                )
                mediaKindOf(card) == MediaKind.Image -> Triple(
                    Res.drawable.ic_image,
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.onPrimaryContainer,
                )
                mediaKindOf(card) == MediaKind.Audio -> Triple(
                    Res.drawable.ic_speaker,
                    MaterialTheme.colorScheme.secondaryContainer,
                    MaterialTheme.colorScheme.onSecondaryContainer,
                )
                else -> Triple(
                    Res.drawable.ic_file,
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Surface(
                shape = ShapeRoles.Avatar,
                color = fiContainer,
                modifier = Modifier.size(34.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(fiIcon),
                        contentDescription = null,
                        tint = fiContent,
                        modifier = Modifier.size(IconSizes.Body),
                    )
                }
            }
            Column(Modifier.weight(1f)) {
        // `.fn{font-size:12px;font-weight:600;overflow:hidden;text-overflow:ellipsis;
        // white-space:nowrap}` —— 一行，超出省略（原型不让它折出第二行）。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(sp.xs),
        ) {
            Text(
                text = card.name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            // 这里曾有一枚无条件渲染的「直接」徽章（8ef647b6 引入）：每个文件卡
            // （收/发/任何传输、任何数据面）都画，徽章失去信息量，设计规格里也没有
            // 这个元素。要么按事实渲染（卡片模型没有"走哪条数据面"的字段），要么不画 ——
            // 按本仓"诊断字段要么如实、要么不存在"的同一纪律，选不画。
        }
        Text(
            // 原型 folderCard 的 accepted 分支把**进度口径**写在这一行：
            // `${pct}% · ${sz} / ${sz} · ${spd}`（relay.html:4259-4261），状态行只留状态词。
            // 代码原来无论什么状态都只画计数，于是「62%」被挪进状态行、这一行退化成
            // 纯计数 —— 与原型「副行看进度、状态行看状态」的分工相反。
            // 速率末段（`· 1.2 MB/s`）现在有了：core 的 TransferManager 采样（EMA，
            // 按**整次传输**算，换文件不归零），Active 且有可信读数时才追加；
            // "MB/s" 是 ASCII 单位不走文案资源。
            text = if (card.isFolder && card.state == TransferState.Active) {
                stringResource(
                    Res.string.r2_chat_folder_progress,
                    (card.fraction * 100f).toInt(),
                    formatBytes(card.done),
                    formatBytes(card.size),
                )
            } else if (card.isFolder) {
                stringResource(Res.string.transfer_folder_count, card.fileCount) + " · " + formatBytes(card.size)
            } else if (card.fileCount > 1) {
                stringResource(Res.string.file_bubble_count, card.fileCount) +
                    " · " + formatBytes(card.size)
            } else {
                formatBytes(card.size)
            } + card.speedSuffix(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 表格图片的文字视图（协议 §3）。
        //
        // 表格里的数字要能选中复制，图片做不到这件事 —— 这就是协议为什么要为它留一个字段。
        // 措辞刻意不是"图片/文字切换"：这个开关说的是**文字**这一件事，
        // 图片预览是另一回事（见 [InlineImagePreview]），两者互不代替。
        val tableText = card.tableText
        if (tableText != null) {
            var showTableText by remember(card.transferId) { mutableStateOf(false) }
            AppTextButton(
                text = stringResource(
                    if (showTableText) {
                        Res.string.file_table_hide_text
                    } else {
                        Res.string.file_table_show_text
                    },
                ),
                size = AppButtonSize.Compact,
                onClick = { showTableText = !showTableText },
                modifier = Modifier.padding(top = sp.xxs),
            )
            if (showTableText) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().padding(top = sp.xs),
                ) {
                    // 制表符文本一行可能很长，让它横向滚而不是折成一片 ——
                    // 折行之后列就对不齐了，而"列对得齐"正是这段文字存在的理由。
                    Text(
                        text = tableText,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .padding(sp.sm)
                            .horizontalScroll(rememberScrollState()),
                    )
                }
                if (card.tableTextTruncated) {
                    Text(
                        text = stringResource(Res.string.file_table_truncated),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = sp.xs),
                    )
                }
            }
        }

        when (card.state) {
            TransferState.Offered -> {
                Text(
                    text = if (card.mine) {
                        stringResource(Res.string.file_state_offered_out)
                    } else if (card.autoAccepted) {
                        // 字节已经自动在收了，这句再喊「等你接收」就是把刚做完的事
                        // 又推回给用户决定一次（历史教训里那条两端分叉的另一半）。
                        stringResource(Res.string.file_state_offered_auto)
                    } else {
                        stringResource(Res.string.file_state_offered_in)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 只有对方发来的、还没被接收的才给按钮：自己的出价等对方点，
                // 已经开始的传输再给"接收"只会让人以为点了能重来。
                // 判据抽成 [fileCardShowsAccept] 是为了能被单测钉住 —— 尤其是
                // 「进度态（Active）里绝不出现『接收』」这条，靠肉眼看布局迟早会漏。
                if (fileCardShowsAccept(card)) {
                    Row(
                        modifier = Modifier.padding(top = sp.sm),
                        horizontalArrangement = Arrangement.spacedBy(sp.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 主操作「接收」实心、次操作「拒绝」降级成文字 —— 与 Completed
                        // 分支的「打开 / 分享」用同一条规矩，两张卡的手感才一致。
                        AppButton(
                            text = stringResource(Res.string.file_accept),
                            size = AppButtonSize.Compact,
                            onClick = { onAcceptTransfer(card.transferId) },
                        )
                        AppTextButton(
                            text = stringResource(Res.string.file_decline),
                            size = AppButtonSize.Compact,
                            tone = AppButtonTone.Danger,
                            onClick = { onDeclineTransfer(card.transferId) },
                        )
                    }
                }
            }

            TransferState.Active -> {
                val percent = (card.fraction * 100f).toInt()
                // 原型 `.bar{height:3px;border-radius:2px;background:var(--surface-3);margin-top:6px;
                // overflow:hidden}` + `.bar i{background:var(--primary)}` —— 传输进度是一条 3px 高、
                // 圆角 2px 的实心条，没有 M3 那圈描边端点、也没有末端停靠点。
                // 原型 `.bar{height:3px;border-radius:2px;background:var(--surface-3)}`：
                // 轨道是**填充** token（`accents.surface3`），不是描边用的 `--hair`。
                LinearProgressIndicator(
                    progress = { card.fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = sp.sm6)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.accents.surface3,
                    strokeCap = StrokeCap.Butt,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = sp.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        // 文件夹的进度已经写在副行，状态行按原型只留状态词
                        // （`accepted → 传输中/发送中`，relay.html:4254-4255）。
                        // 单个文件的进度没有别的地方可写，仍留在状态行（原型 fileCard 的
                        // `active-in/out` label 就是带百分号的，relay.html:2714-2715）。
                        text = if (card.isFolder) {
                            stringResource(
                                if (card.mine) {
                                    Res.string.r2_chat_folder_sending
                                } else {
                                    Res.string.r2_chat_folder_receiving
                                },
                            )
                        } else if (card.mine) {
                            stringResource(Res.string.file_state_active_out, percent)
                        } else {
                            stringResource(Res.string.file_state_active_in, percent)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    // 「正在发」是唯一一个用户仍然反悔得回来的时刻：撤了就是撤了，
                    // 传到一半的文件留在那里只会让人以为还在跑。
                    if (onCancel != null && card.mine) {
                        AppIconButton(
                            icon = Res.drawable.ic_close,
                            contentDescription = stringResource(Res.string.common_cancel),
                            onClick = { onCancel() },
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
            }

            TransferState.Completed -> {
                // 内联预览：图片画缩略图、音频给一个「播放」按钮。
                //
                // 位置在状态文案之前，因为它是**内容**，下面几行都是元数据。
                // 收端要等传完才有 `savedPaths`，发端在 offer 时就有 `localPaths` ——
                // 两边都拿不到路径（老消息、字段缺失）就什么都不画，退回原来那张文件卡。
                val previewPath = if (card.mine) {
                    card.localPaths.firstOrNull()
                } else {
                    card.savedPaths.firstOrNull()
                }
                if (previewPath != null) {
                    when (mediaKindOf(card)) {
                        MediaKind.Image -> InlineImagePreview(
                            path = previewPath,
                            onClick = onImageClick?.let { click -> { click(previewPath) } },
                        )
                        MediaKind.Audio -> InlineAudioPreview(card.transferId, previewPath)
                        MediaKind.Other -> Unit
                    }
                }
                if (card.mine) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(sp.xs),
                        modifier = Modifier.padding(top = sp.xxs),
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_check_double),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(IconSizes.Badge),
                        )
                        Text(
                            text = stringResource(Res.string.file_state_sent_success),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                } else {
                    Text(
                        text = stringResource(Res.string.file_state_completed_in),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                val savedPath = card.savedPaths.firstOrNull()
                savedPath?.let { path ->
                    // 「已存到 <路径>」是**事实陈述**，必须保留（见 [ReceivedFileActions] 的 KDoc：
                    // 收下的文件落在应用内部目录，用户没有别的办法知道它在哪）。
                    // 但完整路径是实现细节（`/data/user/0/com.kapp.tenon/files/relay/downloads/…`）：
                    // 它一折成两行就把卡片本身压住了，而用户真正要看的是**文件名那一段**。
                    // 所以这里显示**省略过的**路径（中间用 `…` 标记、保留末尾），并且只占一行；
                    // 完整路径在「打开 / 分享」交给系统之后仍然可见。
                    Text(
                        text = stringResource(Res.string.file_saved_to, elidePathTail(path)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 收到的文件落在应用内部目录（filesDir/relay/downloads），文件管理器看不见 ——
                // 没有出口的话，那句"已存到 <路径>"就是一条死路。自己发出去的不给：
                // 本来就知道存在哪，而且 P0 的缺口专指"收到的拿不到"。
                if (!card.mine && receivedFileActions != null && savedPath != null) {
                    Row(
                        modifier = Modifier.padding(top = sp.sm),
                        horizontalArrangement = Arrangement.spacedBy(sp.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 这里不猜"能不能打开"：按钮只是入口，真正的动作在平台层
                        // （Android 是 ACTION_VIEW / ACTION_SEND + FileProvider）。
                        //
                        // **主次**：「打开」是这张卡的主操作（用户拿到文件后的下一步就是它），
                        // 给实心 [Button]；「分享」是次要出口，降级成 [TextButton]。
                        // 两个都是纯文字按钮时看不出该点哪个 —— 这与 Offered 分支的
                        // 「接收（实心）/ 拒绝（文字）」是同一条规矩。
                        // 用按钮族的 **Compact 档**（9×5 / labelSmall），不用裸 M3 `Button`：
                        // M3 的 `Button` 自带 `minHeight = 40dp`，`contentPadding` 只压得动横向，
                        // 于是这张卡里那行按钮的**背景比文字高一大截**（真机反馈 #5）。
                        // 同一处「打开/分享」的主次关系不变：主操作实心、次操作文字。
                        AppButton(
                            text = stringResource(Res.string.file_open),
                            onClick = { receivedFileActions.open(savedPath) },
                            size = AppButtonSize.Compact,
                        )
                        AppTextButton(
                            text = stringResource(Res.string.file_share),
                            onClick = { receivedFileActions.share(savedPath) },
                            size = AppButtonSize.Compact,
                        )
                    }
                }
            }

            TransferState.Failed -> {
                // 样式收敛（A22 用户反馈）：失败态此前是「失败原因 + 可续传标签 +
                // 继续 + 取消」四件套 —— 「可续传」与「继续」说的是同一件事。
                // 现在能力折进状态行（… · 可续传），动作只留 [继续] [取消] 两个。
                val reasonText = stringResource(
                    Res.string.file_state_failed,
                    // core 给的是**机器码**（socket-error / hash-mismatch / offer-expired…），
                    // 直接印给用户等于让用户去猜"是我网坏了还是软件坏了"。
                    // 这里翻译成人话；认不出的码**原样显示**（不吞掉事实，也不编一个说法）。
                    transferFailureReason(card.reason) ?: stringResource(Res.string.common_unknown),
                )
                val resumableHere = !card.mine && onResumeTransfer != null
                Text(
                    text = if (resumableHere) {
                        reasonText + " · " + stringResource(Res.string.transfer_resume_hint)
                    } else {
                        reasonText
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                if (card.mine) {
                    // 出站失败**没有续传入口**（core 只有入站 resume；重发的正路是重新挑文件），
                    // 所以要把下一步说清楚，而不是只留一句失败原因 —— 那读起来像死路。
                    Text(
                        text = stringResource(Res.string.chat_file_send_failed_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = sp.xs),
                    )
                }
                if (!card.mine && onResumeTransfer != null) {
                    Row(
                        modifier = Modifier.padding(top = sp.sm6),
                        horizontalArrangement = Arrangement.spacedBy(sp.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppButton(
                            text = stringResource(Res.string.transfer_action_resume),
                            size = AppButtonSize.Compact,
                            onClick = { onResumeTransfer(card.transferId) },
                        )
                        if (onCancel != null) {
                            AppTextButton(
                                text = stringResource(Res.string.common_cancel),
                                size = AppButtonSize.Compact,
                                tone = AppButtonTone.Danger,
                                onClick = onCancel,
                            )
                        }
                    }
                }
            }

            TransferState.Declined -> Text(
                text = stringResource(Res.string.file_state_declined),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    }
    }
}

/**
 * 把一条**实现细节**路径压成一行可读的尾巴：`…/downloads/q1.bin`。
 *
 * 为什么不是直接 `maxLines = 2`：文件卡里的完整路径是
 * `/data/user/0/com.kapp.tenon/files/relay/downloads/q1.bin` 这种应用内部目录，折成两行就把卡片
 * 本身压住了；而按行尾截断（`TextOverflow.Ellipsis`）留下的恰恰是最没用的前缀。
 * 所以这里**从中间省略**：留 `…` 标记 + 末尾一段（文件名所在处），并且**优先从 `/` 起**，
 * 让结果读起来像一段路径而不是被切碎的字符串。
 *
 * 它是纯函数、没有平台依赖，所以能被单测直接钉住（见 `FileCardStyleTest`）。
 *
 * @param maxChars 展示上限（含 `…`）。默认值对应卡片里那一行的可用宽度。
 */
internal fun elidePathTail(path: String, maxChars: Int = 34): String {
    if (path.length <= maxChars) return path
    val tail = path.takeLast(maxChars - 1)
    val slash = tail.indexOf('/')
    val readable = if (slash > 0) tail.substring(slash) else tail
    return "…" + readable
}

/**
 * 副行的速率末段（` · 1.2 MB/s`）。只在 [TransferState.Active] 且 core 给了
 * 可信读数时出现 —— Offered 的静置、Completed 的定格都不该带一个过期的速率。
 * "MB/s" 是 ASCII 单位，不走文案资源（也就不受新增 key 的解析问题影响）。
 */
internal fun FileCardUi.speedSuffix(): String {
    if (state != TransferState.Active) return ""
    val bps = speedBps ?: return ""
    return " · " + formatBytes(bps) + "/s"
}

/**
 * 这张文件卡要不要给「接收」按钮。
 *
 * 两条规矩，缺一不可：
 *  · **对方发来的**才给（自己发出去的出价在等对方点，本端给「接收」没有意义）；
 *  · 而且必须是**还没开始传**的 `Offered`。
 *
 * 第二条是需求里点名的那条：`Active`（正在接收 62%）时再出现一个「接收」按钮，用户会以为
 * 点了能重来。抽成纯函数是为了让它**能被测到** —— 布局里「少画一个按钮」用肉眼迟早会漏。
 */
internal fun fileCardShowsAccept(card: FileCardUi): Boolean =
    card.state == TransferState.Offered && !card.mine && !card.autoAccepted

/**
 * 把 core 的传输失败**机器码**翻译成人话；认不出时返回 null（调用方退回原文或「未知」）。
 *
 * ## 为什么必须有这一层
 *
 * `TransferState.Failed` 的 `reason` 是传输层造的诊断码（`socket-error`、`hash-mismatch`、
 * `offer-expired`、`timeout-waiting-pull-ok`…）。它们对排障有价值、对用户毫无意义 ——
 * 真机上用户看到的是「文件传输失败：socket-error」，第一反应是「这软件坏了还是我网坏了」，
 * 而这恰恰是产品最该替他回答的问题。
 *
 * ## 两道纪律
 *
 * 1. **认不出的码原样显示**（返回 null ⇒ 调用方画 `common_unknown`）。绝不把不认识的码
 *    硬塞进某个笼统说法里 —— 那是在替事实编故事。
 * 2. **每条都要同时说「发生了什么」和「我该怎么办」**。只说「失败了」等于把问题原样退回给用户。
 *
 * 前缀匹配是刻意的：错误码族里有 `timeout` / `timeout-data` / `timeout-waiting-pull-ok`
 * 这样的派生形态，逐个枚举迟早漏。
 */
@Composable
internal fun transferFailureReason(reason: String?): String? {
    val code = reason?.trim()?.lowercase().orEmpty()
    if (code.isEmpty()) return null
    return when {
        code.startsWith("offer-expired") -> stringResource(Res.string.file_reason_offer_expired)
        code.startsWith("peer-offline") -> stringResource(Res.string.file_reason_peer_offline)
        code.startsWith("hash-mismatch") -> stringResource(Res.string.file_reason_hash_mismatch)
        code.startsWith("size-mismatch") -> stringResource(Res.string.file_reason_size_mismatch)
        code.startsWith("write-error") || code.startsWith("part-read-error") ->
            stringResource(Res.string.file_reason_write_error)
        code.startsWith("path-escape") -> stringResource(Res.string.file_reason_path_escape)
        code.startsWith("busy") -> stringResource(Res.string.file_reason_busy)
        code.startsWith("cancel") || code == "close" || code == "closed" ->
            stringResource(Res.string.file_reason_canceled)
        code.startsWith("not-found") -> stringResource(Res.string.file_reason_not_found)
        code.startsWith("bad-frame") || code.startsWith("bad-offset") || code.startsWith("bad-msg") ->
            stringResource(Res.string.file_reason_bad_frame)
        code.startsWith("socket-error") || code.startsWith("disconnected") || code == "type" ->
            stringResource(Res.string.file_reason_socket_error)
        code.startsWith("timeout") || code.startsWith(OfferDeliveryStates.ACK_TIMEOUT) || code.startsWith(OfferDeliveryStates.GAVE_UP) ->
            stringResource(Res.string.file_reason_timeout)
        else -> null
    }
}
