// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.kapp.tidings.generated.resources.chat_more_actions
import com.kapp.tidings.generated.resources.chat_reply_cancel
import com.kapp.tidings.generated.resources.chat_reply_preview_prefix
import com.kapp.tidings.generated.resources.ic_plus
import com.kapp.tidings.ui.theme.IconSizes
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.kapp.tidings.core.protocol.Protocol
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.chat_byte_hint
import com.kapp.tidings.generated.resources.chat_byte_over
import com.kapp.tidings.generated.resources.chat_composer_hint
import com.kapp.tidings.generated.resources.chat_emoji
import com.kapp.tidings.generated.resources.chat_route_tcp
import com.kapp.tidings.generated.resources.chat_route_udp
import com.kapp.tidings.generated.resources.chat_send
import com.kapp.tidings.generated.resources.ic_send
import com.kapp.tidings.generated.resources.chat_mention_all
import com.kapp.tidings.generated.resources.common_unknown_member
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

import com.kapp.tidings.ui.theme.LocalAppSpacing
import com.kapp.tidings.domain.model.MessageUi

/**
 * 输入栏能发起的一项能力（发文件、拍照、语音、位置…）。
 *
 * 列表由调用方按**真实实现**给：没有实现的能力根本不进列表，也就不会画出来。
 * 这条规矩和仓里其它「能力缺失」的处理一致 —— 一个按下去没反应的图标比没有图标更糟，
 * 用户会以为功能在，只是自己没按对。
 *
 * [id] 只用于测试与日志，不参与渲染；[label] 既是可见文案也是无障碍朗读文本。
 */
public data class ComposerCapability(
    val id: String,
    val label: StringResource,
    val icon: DrawableResource,
    val onPick: () -> Unit,
)

/**
 * 一条草稿按字节数落在哪一档。三档而不是桌面那个两态 `ComposeByteMode`：
 * 桌面的 `Tcp.overLimit == true` 把"换条路"和"发不出去"混成了同一个标志位，
 * 手机上这两件事必须分开说 —— 前者是提示，后者是报错。
 */
internal enum class ComposerByteState { Udp, Tcp, Over }

/**
 * 分档口径：UDP 上限**含**（`<=` 就走单播），TCP 上限也含（正好 4096 字节发得出去）。
 * 边界来自协议常量，不是随手取的数，所以拉出来单测，不埋在 composable 里。
 */
internal fun composerByteState(bytes: Int): ComposerByteState = when {
    bytes > Protocol.TEXT_TCP_LIMIT -> ComposerByteState.Over
    bytes <= Protocol.TEXT_UDP_LIMIT -> ComposerByteState.Udp
    else -> ComposerByteState.Tcp
}

/**
 * 在光标处（有选区则在选区处）插入一个表情，返回新文本与插入后的光标位置。
 *
 * 抽成纯函数是为了能单测：截图门禁能录到"面板开着"的样子，但录不到"点下去插在哪"，
 * 而插错位置（一律追加到末尾、或选区没被替换掉）是用户一眼就能看见的错。
 *
 * 光标用 `emoji.length` 前进，不是 `+1`：表情在 UTF-16 里多是代理对（长度 2），
 * 而 `TextRange` 数的就是 UTF-16 码元。写 `+1` 会让光标落在代理对中间。
 *
 * 下标先夹进合法范围再切：`selection` 与文本在极端情况下会短暂不同步
 * （比如调用方从外面清了草稿），`substring` 越界是直接抛异常。
 */
internal fun insertEmoji(text: String, start: Int, end: Int, emoji: String): Pair<String, Int> {
    val from = start.coerceIn(0, text.length)
    val to = end.coerceIn(from, text.length)
    return text.substring(0, from) + emoji + text.substring(to) to (from + emoji.length)
}

/**
 * 一个 @ 候选。
 *
 * [token] 由**身份解析器**给（agent = `@KIND`，真人 = `@显示名`），输入栏**不自己拼**：
 * 触发判据认的就是 kind，任何在这里另拼一次的写法都会造出「@ 了却叫不醒」。
 * [note] 是候选行的副文案（目前只有「未获取到资料」），null = 不画。
 */
public data class MentionTarget(
    val token: String,
    val label: String,
    val note: String? = null,
    val isAgent: Boolean = false,
)

/**
 * 在光标处（有选区则在选区处）插入一个 @ mention，返回新文本与插入后的光标位置。
 *
 * 抽成纯函数是为了两个**必须一致**的入口共用：输入栏里敲 `@` 弹出的候选列表，以及
 * 群成员/气泡头像长按「直接 @」。两处各写一遍 token 拼装，迟早一处多一个空格、
 * 一处少一个词边界，症状就是「@ 了没反应」。
 *
 * 已经刚敲出一个 `@` 时不再补第二个（与旧的内联实现同一行为）。
 */
internal fun insertMention(text: String, start: Int, end: Int, token: String): Pair<String, Int> {
    val from = start.coerceIn(0, text.length)
    val to = end.coerceIn(from, text.length)
    val name = token.trim().removePrefix("@")
    val alreadyTypedAt = from > 0 && text[from - 1] == '@'
    val insertion = (if (alreadyTypedAt) "" else "@") + name + " "
    val cursor = from + insertion.length

    // **已经打完整个 token 时不再插一次。**
    //
    // 真身：在 `@panda` 后面再选一次 `@panda`，旧实现会得到 `@panda@panda ` ——
    // 用户按了候选、结果 token 变成两截，`@` 也重复了。判据是"光标左侧已经恰好
    // 是这个 token"（含其后的空格），那就只移动光标。
    val typedToken = (if (alreadyTypedAt) "" else "@") + name
    val leftOfCursor = text.substring(0, from)
    if (leftOfCursor.endsWith(typedToken)) {
        // 后面还没空格就补一个，已经有的不重复补。
        val needsSpace = !text.substring(from).startsWith(" ")
        val tail = if (needsSpace) text.substring(0, from) + " " + text.substring(from) else text
        return tail to (if (needsSpace) from + 1 else from)
    }

    return text.substring(0, from) + insertion + text.substring(to) to cursor
}

/**
 * 光标前正在进行的那段「@ 输入」：离光标最近的一个 `@` 到光标之间的文字。
 *
 * ## 它回答两个问题
 *
 * 1. **候选该不该开**：返回 null = 光标前没有进行中的 @ 输入（还没打 @、@ 与光标之间
 *    隔了空白/换行、或 @ 已被删掉）—— 候选列表该收起。此前候选只在「敲入 @」那一刻打开，
 *    之后**不过滤也不收起**：候选挂在那儿盖住正文，删除 @ 后还赖着不走。
 * 2. **过滤词是什么**：返回非 null = 用户正在 @ 后面打的字 —— 候选按它的前缀过滤，
 *    而不是不管打到哪儿都把全体成员摊出来。
 *
 * 抽成纯函数是为了让「删除 @ 收起」「按前缀过滤」这两条判据能被 jvmTest 钉住 ——
 * 候选开合错了没有任何编译期信号，只有边打字边看才知道。
 *
 * @param cursor 光标的 UTF-16 偏移（`TextFieldValue.selection.end`）。
 * @return @ 后面到光标为止的片段（可能为空串 = 刚敲完 @）；null = 没有进行中的 @ 输入。
 */
internal fun mentionQueryBeforeCursor(text: String, cursor: Int): String? {
    val c = cursor.coerceIn(0, text.length)
    var i = c - 1
    while (i >= 0) {
        val ch = text[i]
        // 空白/换行 = 这一段输入已经结束：@ 到光标之间不再连贯。
        if (ch.isWhitespace()) return null
        if (ch == '@') return text.substring(i + 1, c)
        i--
    }
    return null
}

/**
 * 按光标前的 token 前缀过滤 @ 候选。
 *
 * [query] 是 [mentionQueryBeforeCursor] 的产物：空串 = 刚敲完 @，全体候选都给；
 * 非空 = 只留 token（去掉前导 @）以它开头的候选（大小写不敏感 —— 用户打 `@dsh`
 * 也该叫得出 `@DSH`，与身份解析器「名字只有一份」的纪律一致）。
 */
internal fun filterMentionTargets(targets: List<MentionTarget>, query: String): List<MentionTarget> =
    if (query.isEmpty()) {
        targets
    } else {
        targets.filter { it.token.removePrefix("@").startsWith(query, ignoreCase = true) }
    }

/**
 * 移动端输入栏：一条药丸装输入框，右侧一颗圆形发送键，能力入口排在药丸里。
 *
 * 形态参考的是市场上 IM 的输入区，而不是桌面那张浮起卡片（桌面要拖拽改高、要键盘快捷键、
 * 要并排的工具栏，手机上没有这些余地）。三处刻意与旧版不同：
 *
 * 1. **无边框药丸**：旧的 `OutlinedTextField` + 带文字的 `Button` 在一行里挤了三种视觉重量
 *    （描边框、图标、实心按钮），窄屏上发送键还要吃掉近百 dp。现在输入框没有自己的边框，
 *    它连同内置表情一起住在药丸里，发送键与「+」号是药丸外两颗独立图标圆钮。
 * 2. **能力入口收敛至「+」号更多面板**：扩展能力（发文件、发图片、PK、窗口震动、语音、历史搜索）
 *    统一收纳在右侧「+」号展开的 [ComposerMorePanel] 网格卡片中，不在输入框上方平铺拥挤的工具栏。
 * 3. **表情是内置能力，不进 [capabilities]**：它不依赖对端、不依赖平台，永远可用，
 *    内置在输入药丸尾部，随手可得。
 * 4. **三态互斥管理**：软键盘、表情面板与更多面板三者互斥。点表情或「+」号先 `clearFocus()` 收起键盘，
 *    点击输入框则键盘升起、面板收起（`onFocusChanged`）。
 * 5. **状态行只在有话说时才画**：说明单条上限与走哪条路（UDP / TCP）。
 *
 * 回车键发消息（[ImeAction.Send]）：旧实现在手机上回车只换行，与 IM 的习惯相反；
 * 需要换行时用 `maxLines` 内的软换行，桌面那套 Shift+Enter 在软键盘上没有对应键。
 *
 * **不要在这里加 `imePadding()`**：根层 `WindowInsets.safeDrawing` 已经含 IME，
 * 两处都加会把输入栏向上推两次。
 */
@Composable
public fun MessageComposer(
    draft: String,
    onDraftChange: (String) -> Unit,
    /**
     * 输入框上方那句"现在发出去会怎样"的提示；`null` = 不显示。
     *
     * 以前这里是 `peerOnline: Boolean`，由本组件自己决定说不说那句
     * 「对方离线，消息会排队」。群会话一来这条就不成立了：群里"对方"是好几个人，
     * 头部那行明明写着 `1/3 人在线`，底下再说"对方离线"是自相矛盾 ——
     * 而群消息的排队规则也与 1:1 不同（逐成员入队，见 `NodeRuntime.sendGroupText`）。
     * 说法由**调用方**给：只有它知道这是群还是一个人。
     */
    offlineHint: String?,
    capabilities: List<ComposerCapability>,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    /**
     * 可供 @ 提及的候选（为空表示非群聊或没有可提及的人，不触发 @ 选单）。
     *
     * 候选由**身份解析器**产出（[MentionTarget.token] 已经带 `@`），输入栏只负责插入，
     * 不判断谁是 agent、更不自己把 agent kind 拼成名字。
     */
    mentionTargets: List<MentionTarget> = emptyList(),
    /**
     * 当前正在引用的消息，为 null 时不显示引用预览条。
     */
    replyingTo: MessageUi? = null,
    /**
     * [replyingTo] 的发送者显示名（调用方用身份解析器算好）。
     *
     * 为什么由调用方给而不是在这里解析：身份规则全在 `SenderIdentityContext` 那一层，
     * 输入栏不该知道。null = 资料没到 —— 预览条如实说「未知成员」，
     * **不把 senderId 前 8 位的裸 hex 当人名印**（那是一个事实的两个说法里最糟的那个）。
     */
    replySenderName: String? = null,
    /**
     * 取消引用回调。
     */
    onCancelReply: (() -> Unit)? = null,
    /**
     * 只读态：这个会话现在**发不出去**（最典型：你已被移出这个群）。
     *
     * 真身：被移出群后输入栏仍然完全可用，用户打完字点发送、只得到一条红色的
     * 「发送失败」—— 反复试，因为界面从没告诉过他原因。
     * 非 null = 用这句话**替换整个输入区**（不画输入框、不画发送键）。
     */
    readOnlyReason: String? = null,
    /**
     * 只给截图预览用的接缝：面板开合是屏内状态，单帧截图抓不到"打开着"的样子。
     * 生产调用方一律用默认值（关着）。与 `DesktopShell` 的 `initialView` 同一路数。
     */
    initialEmojiOpen: Boolean = false,
    /**
     * 更多功能面板的初始开合状态，主要用于测试或截图预览接缝。
     */
    initialMoreOpen: Boolean = false,
    /**
     * 工具条**右端**的入口（原型那一排最右边的历史搜索）。
     *
     * 为什么和 [capabilities] 分开：原型把这一排分成两段 —— 左边是"发出去什么"
     * （表情 / PK / 窗口震动 / 截图 / 图片 / 文件 / 文件夹），右端隔着一段空白是
     * "看看之前说过什么"（历史搜索）。两者语义不同，所以是两组数据、一次布局：
     * `Spacer(weight(1f))` 就是原型那个 `.tsep{flex:1}`。
     *
     * 原型 `toolsRow(group, histTo)`（`prototype/relay.html:3094-3118`）左右两段共用
     * 同一份清单，所以这里也是同一个类型 [ComposerCapability]。
     */
    toolsTrailing: List<ComposerCapability> = emptyList(),
    /**
     * 计数行右端那句话的覆盖值；`null` = 默认按草稿字节数说"走 UDP 单播 / 改走 TCP"。
     *
     * 发文件夹时原型写的是「一次最多 2,000 个文件」（`m-sendfolder`），它替换掉的正是
     * 那条路由文案 —— 同一行、同一位置，说的是这一屏真正需要用户注意的另一条上限。
     */
    hintTrailing: String? = null,
    /**
     * 工具条左段末尾的一句提示（原型群聊那排的「仅在线群成员可接收图片/文件」）。
     *
     * 位置是**图标之后、空白之前**（原型 `.tools .shint` 紧跟最后一颗图标，再才是
     * `.tsep`）—— 它说的是左边那几颗能不能用，不是右端那颗搜索的注脚。
     */
    toolsHint: String? = null,
) {
    // 字节口径与桌面端同一个算法（见 ui/desktop/DesktopLayout.kt 的 draftByteCount）：
    // 中文一行 3 字节，按字符数算会把"看着没超"的草稿发失败。
    val bytes = draft.encodeToByteArray().size
    val byteState = composerByteState(bytes)
    val overLimit = byteState == ComposerByteState.Over
    val canSend = draft.isNotBlank() && !overLimit
    val onUdp = byteState == ComposerByteState.Udp

    // 草稿仍然归调用方所有（`draft` 是唯一事实来源），这里多存一份 `TextFieldValue`
    // 只是为了知道**光标在哪** —— 表情要插在光标处，而不是一律追加到末尾。
    var fieldValue by remember { mutableStateOf(TextFieldValue(draft, TextRange(draft.length))) }
    LaunchedEffect(draft) {
        if (fieldValue.text != draft) {
            fieldValue = fieldValue.copy(text = draft, selection = TextRange(draft.length))
        }
    }
    var emojiOpen by remember { mutableStateOf(initialEmojiOpen) }
    var moreOpen by remember { mutableStateOf(initialMoreOpen) }
    val focusManager = LocalFocusManager.current
    val allCapabilities = remember(capabilities, toolsTrailing) { capabilities + toolsTrailing }

    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = LocalAppSpacing.current.md, vertical = LocalAppSpacing.current.sm),
        ) {
            // 只读态**整块替换**输入区：不画输入框、不画发送键、不显示引用条。
            // 留着输入框等于让用户白打一遍字再吃一条「发送失败」。
            if (readOnlyReason != null) {
                Text(
                    text = readOnlyReason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = LocalAppSpacing.current.sm, vertical = LocalAppSpacing.current.md10),
                )
                return@Column
            }
            if (replyingTo != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = LocalAppSpacing.current.sm6),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = LocalAppSpacing.current.md10, vertical = LocalAppSpacing.current.sm6),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        // 不做 take(40) 预截断：那按 UTF-16 码元切，emoji/组合字符会被劈开
                        // （预览末尾出现半个字符）。交给 maxLines=1 + Ellipsis 按字素收尾。
                        val replyText = replyingTo.text.ifBlank { replyingTo.file?.name.orEmpty() }
                        // 发送者名走身份解析（调用方算好传进来）；资料没到就如实说
                        // 「未知成员」，不再印 8 位 hex。
                        val senderLabel = replySenderName
                            ?: stringResource(Res.string.common_unknown_member)
                        Text(
                            text = stringResource(
                                Res.string.chat_reply_preview_prefix,
                                senderLabel,
                                replyText,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (onCancelReply != null) {
                            // 触达 48dp（视觉仍是那颗 16dp 的小叉）：24dp 的按钮拇指按不中，
                            // 反而容易点到下面的输入框。
                            IconButton(onClick = onCancelReply, modifier = Modifier.size(48.dp)) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(Res.string.chat_reply_cancel),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
            if (offlineHint != null) {
                Text(
                    text = offlineHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = LocalAppSpacing.current.sm6, bottom = LocalAppSpacing.current.sm6),
                )
            }
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.xs),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    // 用 surfaceVariant 而不是 M3 的 surfaceContainerHigh：后者在浅色下是
                    // 紫调容器色（主题只覆盖了 surface/surfaceVariant 这一层），
                    // 跟绿色主色和米白背景摆在一起像从别的应用里抠来的。
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.weight(1f),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = LocalAppSpacing.current.sm, end = LocalAppSpacing.current.xxs),
                    ) {
                        var mentionMenuOpen by remember { mutableStateOf(false) }
                        // 光标前正在打的那段 @ 输入（mentionQueryBeforeCursor 的产物）：
                        // 候选开着时它决定**过滤**；变成 null（@ 被删掉 / 跨过空白）就收起。
                        var mentionQuery by remember { mutableStateOf<String?>(null) }
                        val canMention = mentionTargets.isNotEmpty()

                        val field = Modifier
                            .weight(1f)
                            .padding(vertical = LocalAppSpacing.current.sm)
                            // 手指落回输入框 = 想打字了，表情与更多功能面板让位给键盘。
                            .onFocusChanged {
                                if (it.isFocused) {
                                    emojiOpen = false
                                    moreOpen = false
                                }
                            }
                        Box(modifier = field) {
                            BasicTextField(
                                value = fieldValue,
                                onValueChange = { next ->
                                    val previousText = fieldValue.text
                                    fieldValue = next
                                    onDraftChange(next.text)
                                    if (canMention) {
                                        // 刚敲入 '@'：开候选（与桌面端同一条判据）。
                                        if (next.text.length > previousText.length) {
                                            val cursor = next.selection.end
                                            if (cursor > 0 && next.text.getOrNull(cursor - 1) == '@') {
                                                mentionMenuOpen = true
                                            }
                                        }
                                        // 每次输入都重读光标前的 @ 片段：删掉 '@'（或跨过空白）
                                        // 后它变 null，候选**收起** —— 从前候选只开不收，
                                        // 删了 @ 也一直挂着盖住正文。
                                        val query = mentionQueryBeforeCursor(next.text, next.selection.end)
                                        if (mentionMenuOpen && query == null) {
                                            mentionMenuOpen = false
                                        }
                                        mentionQuery = if (mentionMenuOpen) query else null
                                    }
                                },
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                maxLines = 5,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                                modifier = if (focusRequester != null) Modifier.fillMaxWidth().focusRequester(focusRequester) else Modifier.fillMaxWidth(),
                                decorationBox = { inner ->
                                    Box {
                                        if (fieldValue.text.isEmpty()) {
                                            Text(
                                                text = stringResource(Res.string.chat_composer_hint),
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        inner()
                                    }
                                },
                            )

                            if (canMention) {
                                // @所有人 的 token 也参与同一个插入函数（同一份拼装规则）。
                                val mentionAllToken = "@" + stringResource(Res.string.chat_mention_all)
                                // 按**光标前的前缀**过滤候选（[filterMentionTargets]）：
                                // 刚敲完 @ 给全体；@ 后打了字就只留匹配的 —— 不再不管打到
                                // 哪儿都把全部成员摊出来。
                                val query = mentionQuery.orEmpty()
                                val showMentionAll = mentionAllToken.removePrefix("@")
                                    .startsWith(query, ignoreCase = true)
                                val visibleTargets = filterMentionTargets(mentionTargets, query)
                                DropdownMenu(
                                    expanded = mentionMenuOpen,
                                    onDismissRequest = {
                                        mentionMenuOpen = false
                                        mentionQuery = null
                                    },
                                    modifier = Modifier.heightIn(max = 240.dp),
                                ) {
                                    if (showMentionAll) {
                                        DropdownMenuItem(
                                            text = { Text(mentionAllToken) },
                                            onClick = {
                                                mentionMenuOpen = false
                                                mentionQuery = null
                                                val (nextText, nextCursor) = insertMention(
                                                    text = fieldValue.text,
                                                    start = fieldValue.selection.min,
                                                    end = fieldValue.selection.max,
                                                    token = mentionAllToken,
                                                )
                                                fieldValue = TextFieldValue(nextText, TextRange(nextCursor))
                                                onDraftChange(nextText)
                                            },
                                        )
                                    }
                                    visibleTargets.forEach { target ->
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text(target.token)
                                                    // 资料没到时如实说一句；有名字的人不画副行（不制造噪声）。
                                                    target.note?.let { note ->
                                                        Text(
                                                            text = note,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                    }
                                                }
                                            },
                                            onClick = {
                                                mentionMenuOpen = false
                                                mentionQuery = null
                                                val (nextText, nextCursor) = insertMention(
                                                    text = fieldValue.text,
                                                    start = fieldValue.selection.min,
                                                    end = fieldValue.selection.max,
                                                    token = target.token,
                                                )
                                                fieldValue = TextFieldValue(nextText, TextRange(nextCursor))
                                                onDraftChange(nextText)
                                            },
                                        )
                                    }
                                }
                            }
                        }

                        // 表情排在输入框右侧内部：内置能力，随时插表情且不挤占外部按钮位置
                        IconButton(
                            onClick = {
                                if (emojiOpen) {
                                    emojiOpen = false
                                    focusRequester?.requestFocus()
                                } else {
                                    emojiOpen = true
                                    moreOpen = false
                                    focusManager.clearFocus()
                                }
                            },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.EmojiEmotions,
                                contentDescription = stringResource(Res.string.chat_emoji),
                                tint = if (emojiOpen) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(IconSizes.Title),
                            )
                        }
                    }
                }

                if (allCapabilities.isNotEmpty()) {
                    // 「+」号更多面板入口
                    IconButton(
                        onClick = {
                            if (moreOpen) {
                                moreOpen = false
                                focusRequester?.requestFocus()
                            } else {
                                moreOpen = true
                                emojiOpen = false
                                focusManager.clearFocus()
                            }
                        },
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_plus),
                            contentDescription = stringResource(Res.string.chat_more_actions),
                            tint = if (moreOpen) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(IconSizes.Title),
                        )
                    }
                }

                FilledIconButton(
                    onClick = onSend,
                    enabled = canSend,
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_send),
                        contentDescription = stringResource(Res.string.chat_send),
                        modifier = Modifier.size(IconSizes.Body),
                    )
                }
            }
            // 计数行**常显**（原型 `.composer .hintline`）：草稿为空时它写「0 / 800 字节」，
            // 说的不是"你打了 0 个字"，而是"这条消息最多能有多长、会走哪条路" —— 那两条
            // 上限是这一屏的规则，不该等用户先打几个字才告诉他。
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = LocalAppSpacing.current.sm6, end = LocalAppSpacing.current.xxs, top = LocalAppSpacing.current.xs),
            ) {
                Text(
                    text = if (overLimit) {
                        stringResource(Res.string.chat_byte_over, Protocol.TEXT_TCP_LIMIT)
                    } else {
                        stringResource(
                            Res.string.chat_byte_hint,
                            bytes,
                            if (onUdp) Protocol.TEXT_UDP_LIMIT else Protocol.TEXT_TCP_LIMIT,
                        )
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (overLimit) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (!overLimit) {
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.width(LocalAppSpacing.current.sm))
                    Text(
                        text = hintTrailing ?: stringResource(
                            if (onUdp) Res.string.chat_route_udp else Res.string.chat_route_tcp,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (moreOpen && allCapabilities.isNotEmpty()) {
                ComposerMorePanel(
                    items = allCapabilities,
                    hint = toolsHint,
                    onPick = { item ->
                        item.onPick()
                        moreOpen = false
                    },
                    modifier = Modifier.padding(top = LocalAppSpacing.current.sm),
                )
            } else if (emojiOpen) {
                EmojiPalettePanel(
                    onPick = { emoji ->
                        val (text, caret) = insertEmoji(
                            text = fieldValue.text,
                            start = fieldValue.selection.min,
                            end = fieldValue.selection.max,
                            emoji = emoji,
                        )
                        // 光标停在表情后面，连着点几个表情是依次追加，不是每次都从头来。
                        fieldValue = TextFieldValue(text, TextRange(caret))
                        onDraftChange(text)
                    },
                    modifier = Modifier.padding(top = LocalAppSpacing.current.sm),
                )
            }
        }
    }
}

/**
 * 更多功能面板：点击输入栏「+」号后在下方展开。
 *
 * 采用 4 列网格展示所有可用能力（发文件、图片、PK、窗口震动、语音、历史搜索等）。
 * 每项由一个大图标卡片和单行标签组成，点击触发后自动收起面板。
 */
@Composable
internal fun ComposerMorePanel(
    items: List<ComposerCapability>,
    hint: String?,
    onPick: (ComposerCapability) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = LocalAppSpacing.current.sm, vertical = LocalAppSpacing.current.md),
        ) {
            if (hint != null) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = LocalAppSpacing.current.sm6, start = LocalAppSpacing.current.xs),
                )
            }
            val columns = 4
            items.chunked(columns).forEachIndexed { index, row ->
                if (index > 0) {
                    Spacer(Modifier.height(LocalAppSpacing.current.md))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.xs),
                ) {
                    row.forEach { item ->
                        ComposerMoreItem(
                            item = item,
                            onClick = { onPick(item) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(columns - row.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposerMoreItem(
    item: ComposerCapability,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(vertical = LocalAppSpacing.current.xs),
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.size(54.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(item.icon),
                    contentDescription = stringResource(item.label),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(IconSizes.Title),
                )
            }
        }
        Spacer(Modifier.height(LocalAppSpacing.current.xs))
        Text(
            text = stringResource(item.label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
