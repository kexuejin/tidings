// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kapp.tidings.core.fs.readFileBytes
import com.kapp.tidings.core.ioDispatcher
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.card_artifact_preview_close
import com.kapp.tidings.generated.resources.card_artifact_preview_failed
import com.kapp.tidings.generated.resources.card_artifact_preview_kind_diff
import com.kapp.tidings.generated.resources.card_artifact_preview_kind_markdown
import com.kapp.tidings.generated.resources.card_artifact_preview_kind_text
import com.kapp.tidings.generated.resources.card_artifact_preview_not_delivered
import com.kapp.tidings.generated.resources.ic_close
import com.kapp.tidings.ui.components.AppIconButton
import com.kapp.tidings.ui.theme.LocalAppSpacing
import com.kapp.tidings.ui.theme.accents
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * 产物**端内预览**的决策与渲染（升级计划 §4.2-3 / §7.4）。
 *
 * 数据源是**消息流**里与卡同 id 关联的文件行（收到的文件已落盘的路径），
 * 不是卡片条目自己 —— 卡片只描述「有这么个东西」，文件本体走 file-msg 那条通路。
 * 渲染种类只有三类：Markdown / Diff / 纯文本。其余类型**如实**显示「不可预览」，
 * 不猜、不假装能打开 —— 与「点不动的按钮比没有更糟」是同一条纪律。
 */
object ArtifactPreview {

    /** [readFileBytes] 对预览文档的读取上限。 */
    val MAX_PREVIEW_BYTES: Long = com.kapp.tidings.core.fs.MAX_READ_BYTES

    /** 预览的渲染种类。[None] = 端内不渲染（如实显示不可预览）。 */
    enum class Kind(val titleRes: StringResource?) {
        Markdown(Res.string.card_artifact_preview_kind_markdown),
        Diff(Res.string.card_artifact_preview_kind_diff),
        Text(Res.string.card_artifact_preview_kind_text),
        None(null),
    }

    /**
     * 预览路由决策：名字 + mime → 渲染种类。
     *
     * 名字优先（.diff/.patch/.md 的扩展名语义比 mime 可信 —— 发送侧 mime 缺省的
     * 卡是常态）；mime 只在没有可判扩展名时兜底。认不出 → [Kind.None]。
     * 这里**没有**「任意文本都能渲染」的兜底：二进制按文本解出来是乱码，
     * 乱码窗口比「不可预览」更伤信任。
     */
    fun kindOf(name: String, mime: String?): Kind = when {
        name.endsWith(".diff") || name.endsWith(".patch") -> Kind.Diff
        name.endsWith(".md") || name.endsWith(".markdown") -> Kind.Markdown
        name.endsWith(".txt") || name.endsWith(".log") -> Kind.Text
        name.endsWith(".json") -> Kind.Text
        mime == "text/x-diff" -> Kind.Diff
        mime == "text/markdown" -> Kind.Markdown
        mime == "text/plain" -> Kind.Text
        else -> Kind.None
    }

    /**
     * 这个产物条目**此刻**该不该给「预览」入口。
     *
     * 四个条件全部成立才给：
     * 1. 渲染种类可渲染（[Kind.None] 不给 —— 与协议同源的一把尺）；
     * 2. 发出方声明了 delivered=true（没投出去的文件在本端根本没有本体）；
     * 3. 消息流里找到了同 id 关联的已落盘文件（找不到 = 文件还没到，入口是死路）；
     * 4. 字节读得动（超限 = [readFileBytes] 返回 null，弹窗里如实说读不了）。
     *
     * 「不可预览为何」由 [blockedReasonOf] 单独给出 —— 按钮「不画」与「画了点开
     * 有一句解释」是两种体验，后者用在「文件还在路上」这类暂态。
     */
    fun canPreview(
        artifact: AgentCardArtifact,
        kind: Kind,
        savedPath: String?,
    ): Boolean = kind != Kind.None &&
        artifact.delivered == true &&
        !savedPath.isNullOrBlank()

    /** 暂态下的「为什么现在还不能看」：null = 没有阻塞（可预览）或没有入口可解释。 */
    fun blockedReasonOf(
        artifact: AgentCardArtifact,
        kind: Kind,
        savedPath: String?,
    ): StringResource? = when {
        kind == Kind.None -> null // 种类就不渲染：入口整个不画，无需解释文案。
        artifact.delivered != true -> Res.string.card_artifact_preview_not_delivered
        savedPath.isNullOrBlank() -> null // 文件行还没落盘：等它到了入口自然出现。
        else -> null
    }

    /** 预览窗的标题资源：按种类给（Markdown / Diff / 文本），None 无标题。 */
    fun titleResOf(kind: Kind): StringResource? = kind.titleRes
}

/**
 * 产物预览**全屏对话框**（升级计划 §7.4「点击后端内直接拉起全屏/弹窗高保真渲染」）。
 *
 * Diff 复用卡片 hunk 视窗的既有解析（[agentCardHunkLinesOf]）与上色判据 ——
 * 行号、加绿、删红与卡片内是同一份逻辑，两端各画一份就是分叉的开始。
 * Markdown 复用 [MarkdownMessage]；纯文本等宽渲染。
 *
 * 读文件走 [readFileBytes]（IO 派发器上，不卡组合）：读不到 / 超限 / 空文件
 * 如实显示失败文案，不渲染一个空气窗。
 */
@Composable
fun ArtifactPreviewDialog(
    artifact: AgentCardArtifact,
    savedPath: String,
    onDismiss: () -> Unit,
) {
    val kind = remember(artifact.name, artifact.mime) {
        ArtifactPreview.kindOf(artifact.name, artifact.mime)
    }
    // 内容读取挂 IO：组合期不碰磁盘（与 RemoteViewSessionDialog 同一条纪律）。
    val content by produceState<String?>(initialValue = null, savedPath) {
        value = withContext(ioDispatcher) {
            readFileBytes(savedPath)?.decodeToString()
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 顶栏：种类标题 + 关闭。预览窗是全屏的，没有关闭就出不来了。
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = LocalAppSpacing.current.md, vertical = LocalAppSpacing.current.sm),
                ) {
                    Column(Modifier.weight(1f)) {
                        ArtifactPreview.titleResOf(kind)?.let { res ->
                            Text(
                                text = stringResource(res),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Text(
                            text = artifact.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    AppIconButton(
                        icon = Res.drawable.ic_close,
                        contentDescription = stringResource(Res.string.card_artifact_preview_close),
                        onClick = onDismiss,
                    )
                }
                val body = content
                when {
                    body == null -> PreviewStatusText(
                        text = stringResource(Res.string.card_artifact_preview_failed),
                    )
                    kind == ArtifactPreview.Kind.Markdown -> MarkdownMessage(
                        text = body,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = LocalAppSpacing.current.md),
                    )
                    kind == ArtifactPreview.Kind.Diff -> DiffPreviewBody(body)
                    else -> PlainPreviewBody(body)
                }
            }
        }
    }
}

/** Diff 正文：行号 + 加绿删红，逐字复用卡片 hunk 视窗的解析与配色判据。 */
@Composable
private fun DiffPreviewBody(body: String) {
    val lines = remember(body) { agentCardHunkLinesOf(body.lines()) }
    val listState = rememberLazyListState()
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(lines.size) { index ->
            PreviewHunkLineRow(lines[index])
        }
    }
}

/** 纯文本正文：等宽、按行渲染，横向滚动保住对齐（与表格图片的文字视图同一取舍）。 */
@Composable
private fun PlainPreviewBody(body: String) {
    val lines = remember(body) { body.lines() }
    val listState = rememberLazyListState()
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(lines.size) { index ->
            Text(
                text = lines[index],
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = LocalAppSpacing.current.md, vertical = LocalAppSpacing.current.xxs),
            )
        }
    }
}

/** 读不到 / 超限时的如实一行。 */
@Composable
private fun PreviewStatusText(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(LocalAppSpacing.current.lg),
        )
    }
}

/**
 * 预览窗里的 diff 行：与卡片内 CardHunkLineRow 同一配色判据（加绿 12% 微底、
 * 删红 12% 微底），行号 gutter 同宽同档 —— 预览只是「更大的那扇窗」，
 * 不是第二套配色方案。
 */
@Composable
private fun PreviewHunkLineRow(line: AgentCardHunkLine) {
    val (bg, fg) = when (line.kind) {
        AgentCardHunkKind.Add ->
            MaterialTheme.accents.online.copy(alpha = 0.12f) to MaterialTheme.accents.online
        AgentCardHunkKind.Del ->
            MaterialTheme.colorScheme.error.copy(alpha = 0.12f) to MaterialTheme.colorScheme.error
        AgentCardHunkKind.HunkHead ->
            Color.Transparent to MaterialTheme.colorScheme.onSurfaceVariant
        AgentCardHunkKind.Context ->
            Color.Transparent to MaterialTheme.colorScheme.onSurface
    }
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = LocalAppSpacing.current.md),
    ) {
        Text(
            text = line.number?.toString() ?: "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(end = LocalAppSpacing.current.sm),
        )
        Text(
            text = line.text,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 「不可预览」的如实解释行（条目上渲染，替代点不动的承诺）。 */
@Composable
internal fun ArtifactPreviewBlockedReason(reason: StringResource) {
    Text(
        text = stringResource(reason),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = LocalAppSpacing.current.xxs),
    )
}
