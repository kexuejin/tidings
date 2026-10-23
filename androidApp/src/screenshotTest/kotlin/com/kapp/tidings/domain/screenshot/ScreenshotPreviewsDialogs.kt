// 弹层族的门禁补齐（审计 §9 缺口④/§10 V8）。
//
// 为什么单独一个文件：这 11 帧此前**全部零门禁** —— 它们都挂在
// `remember { mutableStateOf(false) }` 后面，静态帧点不到，而本轮之前没有人
// 验证过弹层在 Layoutlib 下到底组不组合。
//
// 实测结论（2026-10-09 探针，证据见本文件末的 KDoc 与审计文档）：
// **`AlertDialog` 与 `androidx.compose.ui.window.Dialog` 都能完整组合** ——
// 标题/正文/按钮/遮罩/底层内容全在，与 `Popup` 的空框完全不同（缺口③那条
// 「弹层不可补」的结论只对 `Popup` 成立，不能外推到 Dialog 族）。
//
// 这批帧**没有改任何生产代码**：下面每个弹层的参数都是普通数据，
// 预览直接构造即可，不需要加 `initial*` 冷启动接缝。
//
// 底衬（substrate）：弹层自己带遮罩，底衬用什么不影响弹层内容，所以统一用
// `surface` 纯色而不是把整屏塞进来 —— 这一批钉的是**弹层自己**，整屏组合
// 已由 ScreenshotPreviews*.kt 的其它帧负责。遮罩压出来的深浅差异正是要看的。
package com.kapp.tidings.domain.screenshot

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.kapp.tidings.domain.MessageSearchResult
import com.kapp.tidings.domain.model.ScreenSessionUiState
import com.kapp.tidings.core.protocol.ScreenMode
import com.kapp.tidings.core.protocol.ScreenPhase
import com.kapp.tidings.core.protocol.ScreenRole
import com.kapp.tidings.core.protocol.ScreenSessionState
import com.kapp.tidings.feature.chat.HistorySearchOverlay
import com.kapp.tidings.ui.components.AdaptiveDiagDialog
import com.kapp.tidings.ui.components.ForwardTargetDialog
import com.kapp.tidings.ui.components.GroupCreateDialog
import com.kapp.tidings.ui.components.GroupInfoDialog
import com.kapp.tidings.ui.components.ImageViewerDialog
import com.kapp.tidings.ui.components.PkPickerDialog
import com.kapp.tidings.ui.components.RemoteViewSessionDialog
import com.kapp.tidings.ui.desktop.DesktopHistorySearchDialog
import com.kapp.tidings.ui.desktop.PREVIEW_NOW
import com.kapp.tidings.ui.desktop.agentPeer
import com.kapp.tidings.ui.desktop.desktopPreviewConversations
import com.kapp.tidings.ui.desktop.desktopPreviewGroupState
import com.kapp.tidings.ui.desktop.desktopPreviewState
import com.kapp.tidings.ui.desktop.linPeer
import com.kapp.tidings.ui.desktop.zhouPeer
import com.kapp.tidings.ui.theme.AppTheme
import com.android.tools.screenshot.PreviewTest

/** 弹层统一底衬：见文件头「底衬」一段。 */
@Composable
private fun DialogBackdrop(content: @Composable () -> Unit) {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            content()
        }
    }
}

// ── 转发目标选择（AlertDialog）────────────────────────────────────────────────

/**
 * 「转发到…」的目标列表。
 *
 * `sessions` 直接吃 `desktopPreviewConversations()` —— 与侧栏同一份会话清单，
 * 所以这一帧顺带钉住「转发列表和侧栏说的是同一批会话」。
 *
 * 此前零门禁：`ChatScreen.kt:603` 的那次调用从头到尾没有参考图。
 */
@PreviewTest
@Preview(name = "dialog-forward-target", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ForwardTargetDialogPhonePreview() {
    DialogBackdrop {
        ForwardTargetDialog(
            sessions = desktopPreviewConversations(),
            onSelectTarget = {},
            onDismiss = {},
        )
    }
}

// ── 群信息（AlertDialog）─────────────────────────────────────────────────────

/**
 * 群信息弹层：群名、成员表、在线计数、以及三个可空回调都在位时的完整形态。
 *
 * 三个回调**必须都非 null** —— 它们是 `onAddMembers` / `onRemoveMember` /
 * `onUpdateMeta`，为 null 时对应的管理入口整个不画。传 null 拍出来的
 * 是一张「群主不是我」的降级形态，不是这一帧要钉的东西。
 *
 * `memberOnlineCount = 2` 与 `n/3 人在线` 口径同源（不含自己）。
 */
@PreviewTest
@Preview(name = "dialog-group-info", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun GroupInfoDialogPhonePreview() {
    DialogBackdrop {
        GroupInfoDialog(
            group = desktopPreviewGroupState().settings.groups.first(),
            selfNodeId = desktopPreviewState().selfNodeId,
            peers = listOf(linPeer(), zhouPeer(), agentPeer()),
            memberOnlineCount = 2,
            onDismiss = {},
            onAddMembers = {},
            onRemoveMember = {},
            onUpdateMeta = { _, _, _ -> },
        )
    }
}

// ── 建群（AlertDialog）───────────────────────────────────────────────────────

/**
 * 建群弹层。进来时群名空、一个人都没选，所以「确定」是**禁用**的 ——
 * 这一帧钉的正是那个禁用态（`canConfirm = name.isNotBlank() && chosen.isNotEmpty()`）。
 *
 * 想钉可提交态需要预置输入，而 `name` 与 `chosen` 都是屏内 `remember`
 * （`GroupCreateDialog.kt:70-71`），静态帧改不动 —— 与缺口③同类，属**记录在案**。
 */
@PreviewTest
@Preview(name = "dialog-group-create", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun GroupCreateDialogPhonePreview() {
    DialogBackdrop {
        GroupCreateDialog(
            peers = listOf(linPeer(), zhouPeer(), agentPeer()),
            onDismiss = {},
            onConfirm = { _, _ -> },
        )
    }
}

// ── 自适应诊断（AlertDialog）── 这一族只剩 `ChatInsetsPhonePreview`（`m-insets` 行）那张：
// 原来这里还有一张 `AdaptiveDiagDialogPhonePreview`，它与 `ChatInsetsPhonePreview` **逐字节
// 相同**（同一个 `AdaptiveDiagDialog` 全屏盖住背景），台账里**没有任何行**引用它 ⇒ 按
// :designDupFrameGate 的判据「删掉那张已无独立证据价值的帧（连同 reference PNG）」删掉。

// ── Pk 表情（androidx Dialog）────────────────────────────────────────────────

/**
 * Pk 对决的表情选择器（`androidx.compose.ui.window.Dialog`）。
 *
 * 这一帧同时是「`window.Dialog` 能被 Layoutlib 组合」的**存续证据** ——
 * 缺口③ 曾据 `Popup` 的空框结论把整个弹层族判为不可补，实测证明那是过度外推。
 */
@PreviewTest
@Preview(name = "dialog-pk-picker", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun PkPickerDialogPhonePreview() {
    DialogBackdrop {
        PkPickerDialog(onDismiss = {}, onPick = { _, _ -> })
    }
}

// ── 图片查看器（androidx Dialog）─────────────────────────────────────────────

/**
 * 图片查看器的**解码前**形态：90% 黑遮罩 + 右上角关闭/分享按钮。
 *
 * 图片本身拍不到，原因是硬限制而不是遗漏 —— 它走
 * `produceState { readFileBytes(...) }`（`ImageViewerDialog.kt:55-60`），
 * 而 §14.5 已证渲染器不执行 effect 协程体。想钉住「图真的显示出来了」必须
 * 加一个 `initialBitmap` 冷启动接缝或改走交互测试，两者都超出本批范围。
 *
 * 所以这一帧**明确不证明图片能显示**，只钉遮罩与按钮的存在与位置。
 */
@PreviewTest
@Preview(name = "dialog-image-viewer", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun ImageViewerDialogPhonePreview() {
    DialogBackdrop {
        ImageViewerDialog(
            imagePath = "/preview/does-not-exist.png",
            onDismiss = {},
        )
    }
}

// ── 屏幕协助（androidx Dialog，三个分支）─────────────────────────────────────

private fun screenSession(
    role: ScreenRole,
    phase: ScreenPhase,
): ScreenSessionState = ScreenSessionState(
    sessionId = "s-preview",
    peerId = linPeer().nodeId,
    peerName = linPeer().nick,
    peerIp = linPeer().ip,
    role = role,
    phase = phase,
    requestedAt = PREVIEW_NOW - 30_000L,
    startedAt = PREVIEW_NOW - 20_000L,
)

/**
 * 屏幕协助 —— **看别人屏幕的人**收到邀请，等待自己同意。
 *
 * `RemoteViewSessionDialog` 是同一个函数里的四个互斥分支（`:85` 等待同意 /
 * `:130` 共享中 / `:169` 观看中 / 兜底），一个预览只能拍到一个。三帧合起来才
 * 覆盖这条流程，缺一帧就等于缺一整个角色视角。
 *
 * 这一帧是**唯一**会问用户「同意吗」的那一屏，也是拒绝按钮存在的唯一位置。
 */
@PreviewTest
@Preview(name = "dialog-screen-consent", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun RemoteViewConsentPhonePreview() {
    DialogBackdrop {
        RemoteViewSessionDialog(
            state = ScreenSessionUiState(
                session = screenSession(ScreenRole.Sharer, ScreenPhase.AwaitingConsent),
            ),
            onAccept = {},
            onReject = {},
            onStop = {},
            onSetMode = {},
        )
    }
}

/**
 * 屏幕协助 —— **正在共享自己屏幕的人**看到的置顶提示条。
 *
 * 与上一帧同一函数、同一角色，只差 `phase`：`AwaitingConsent` → `Active`。
 * 两帧成对，才能看出「等待同意」与「共享中」不是同一块界面。
 *
 * 实测这一支的形态（录图核对过，不是读代码推断）：不是整屏黑，而是一条小小的
 * 居中圆角条（`primaryContainer` 底）= 16dp 转圈 + 「正在共享当前屏幕给 林岚」+
 * 红色「结束协助」按钮。**没有清晰度模式切换** —— `onSetMode` 在这一支完全不使用
 * （只有 Viewer 支的 `OutlinedButton` 用它），所以本帧传 `onSetMode = {}` 是如实的，
 * 不是被静态帧限制掉的。
 *
 * `isSharingScreen = true` 与前置分支一致；真正决定画哪一支的是 `role`/`phase`。
 */
@PreviewTest
@Preview(name = "dialog-screen-sharing", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun RemoteViewSharingPhonePreview() {
    DialogBackdrop {
        RemoteViewSessionDialog(
            state = ScreenSessionUiState(
                session = screenSession(ScreenRole.Sharer, ScreenPhase.Active),
                isSharingScreen = true,
            ),
            onAccept = {},
            onReject = {},
            onStop = {},
            onSetMode = {},
        )
    }
}

/**
 * 屏幕协助 —— **看别人屏幕的人**，对面正在共享。
 *
 * `role = Viewer`（`:169` 那一支），与上面两帧是**另一个角色**，不是同一块界面的
 * 另一种状态。实测这一支的形态（录图核对过，不是读代码推断）：整屏纯黑 + 顶部一条
 * 半透明控制栏 = 标题「屏幕协助 · 林岚」+ 一个显示当前 `session.mode` 的
 * `OutlinedButton`（本帧是「自动画质」）+ 一个右上角 × 圆形按钮（`onClick = onStop`）。
 * **没有**「停止观看」这类文字按钮 —— 退出这个角色的唯一出口就是那个 ×。
 *
 * `latestFrameBytes = null`：帧画面同样来自 effect，与 `dialog-image-viewer` 同理，
 * 所以帧区是纯黑而不是「画面没来」的占位。这里钉的是控制栏，不是画面内容。
 *
 * 附带一条：模式切换按钮背后的 `DropdownMenu` 展开态拍不到（`Popup` 死路，缺口③），
 * 所以四种模式的菜单项没有参考图 —— 这一帧只有按钮上的当前值。
 */
@PreviewTest
@Preview(name = "dialog-screen-viewing", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun RemoteViewViewingPhonePreview() {
    DialogBackdrop {
        RemoteViewSessionDialog(
            state = ScreenSessionUiState(
                session = screenSession(ScreenRole.Viewer, ScreenPhase.Active),
            ),
            onAccept = {},
            onReject = {},
            onStop = {},
            onSetMode = { _: ScreenMode -> },
        )
    }
}

// ── 会话内历史搜索（手机浮层 / 桌面弹窗）────────────────────────────────────

/**
 * 手机端会话内搜索浮层（全屏 `Surface`，不是 `Dialog`）。
 *
 * **这一帧拍的是「有结果」那一版**，和原型帧 `prototype/screenshots/relay/m11d-histsearch.png` 对齐：
 * 关键词「协议」+ 四个命中 + 「已列出这 4 条」。结果平时由
 * `LaunchedEffect(query, kind, fromAt, toAt)` 填，而渲染器不执行 effect —— 所以浮层开了一个
 * 只给截图门禁用的 `initialResults` 接缝（生产调用方一律用默认值）。
 * 四条命中覆盖 `HistoryHitItem` 的三个图标分支：文字 / 文件 / 图片。
 *
 * 日期月历在 `HistoryDateSheet` 那个 `Dialog` 里，是**点开才有**的另一层，本帧拍不到（也不该拍到）。
 *
 * 浮层自己是整屏的（原型 `.hsbd{position:absolute;inset:0;z-index:24}` 连顶栏一起盖住），
 * 所以这里不需要再传对端信息 —— 顶栏在这一层根本不存在。
 */
private val histPreviewHits = listOf(
    MessageSearchResult(
        id = "hs1", convId = "c-hs", peerId = zhouPeer().nodeId, senderId = zhouPeer().nick,
        mine = false, timestamp = PREVIEW_NOW - 3_600_000L, snippetText = "协议里 ack 要不要加重试?",
    ),
    MessageSearchResult(
        id = "hs2", convId = "c-hs", peerId = zhouPeer().nodeId, senderId = zhouPeer().nick,
        mine = false, timestamp = PREVIEW_NOW - 7_200_000L, snippetText = "协议 v2 我把 timeout 调成 3 秒了",
    ),
    MessageSearchResult(
        id = "hs3", convId = "c-hs", peerId = linPeer().nodeId, senderId = linPeer().nick,
        mine = false, timestamp = PREVIEW_NOW - 10_800_000L, snippetText = "", fileName = "需求文档-协议-v2.pdf",
    ),
    MessageSearchResult(
        id = "hs4", convId = "c-hs", peerId = agentPeer().nodeId, senderId = agentPeer().nick,
        mine = false, timestamp = PREVIEW_NOW - 14_400_000L, snippetText = "协议 v3 定稿，图我贴 wiki 了",
        fileName = "协议-拓扑.png",
    ),
)

@PreviewTest
@Preview(name = "dialog-history-search-phone", widthDp = 393, heightDp = 852, showBackground = true, locale = "zh-rCN")
@Composable
private fun HistorySearchOverlayPhonePreview() {
    DialogBackdrop {
        HistorySearchOverlay(
            onDismiss = {},
            onSearch = { _, _ -> histPreviewHits },
            onSelectHit = {},
            initialQuery = "协议",
            initialResults = histPreviewHits,
        )
    }
}

/**
 * 桌面端会话内搜索弹窗（680dp 居中 modal，原型 `d-histsearch`）。
 *
 * 与手机帧同一类限制：结果走 effect，所以这里是空结果态。
 * 768 宽是原型给的弹窗宽度 + 两侧留白，用来确认它居中而不是贴边。
 */
@PreviewTest
@Preview(name = "dialog-history-search-desktop", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DesktopHistorySearchDialogPreview() {
    DialogBackdrop {
        DesktopHistorySearchDialog(
            peer = linPeer(),
            onDismiss = {},
            onSearch = { _, _ -> emptyList() },
            onSelectHit = {},
            initialQuery = "",
        )
    }
}
