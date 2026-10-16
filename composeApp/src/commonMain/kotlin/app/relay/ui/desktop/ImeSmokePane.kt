// SPDX-License-Identifier: GPL-3.0-only

package app.relay.ui.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.relay.generated.resources.Res
import app.relay.generated.resources.desktop_ime_blur_action
import app.relay.generated.resources.desktop_ime_blur_no_callback
import app.relay.generated.resources.desktop_ime_blur_pending
import app.relay.generated.resources.desktop_ime_blur_value
import app.relay.generated.resources.desktop_ime_commits_value
import app.relay.generated.resources.desktop_ime_composing
import app.relay.generated.resources.desktop_ime_empty
import app.relay.generated.resources.desktop_ime_field_basic
import app.relay.generated.resources.desktop_ime_field_hint
import app.relay.generated.resources.desktop_ime_field_multiline
import app.relay.generated.resources.desktop_ime_field_outlined
import app.relay.generated.resources.desktop_ime_fields_head
import app.relay.generated.resources.desktop_ime_intro
import app.relay.generated.resources.desktop_ime_not_composing
import app.relay.generated.resources.desktop_ime_probe_blur
import app.relay.generated.resources.desktop_ime_probe_commits
import app.relay.generated.resources.desktop_ime_probe_composition
import app.relay.generated.resources.desktop_ime_probe_head
import app.relay.generated.resources.desktop_ime_probe_last_commit
import app.relay.generated.resources.desktop_ime_probe_selection
import app.relay.generated.resources.desktop_ime_probe_text
import app.relay.generated.resources.desktop_ime_reset_action
import app.relay.generated.resources.desktop_ime_title
import app.relay.ui.theme.RelayElevations
import app.relay.ui.theme.LocalAppSpacing
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import app.relay.ui.theme.ShapeRoles

/**
 * 输入法自检（桌面诊断页）。
 *
 * ## 为什么需要一台"仪器"
 * 桌面端输入法走的是 AWT/Skiko 桥接，与系统原生输入框不是同一条路，也和
 * Android/iOS 的输入法栈完全不同。它出问题时症状是**感觉不对**（"候选窗没跟着光标"、
 * "拼音没显示在框里"、"退格删不掉"），而感觉没法写进验收单、也没法写进 bug 报告。
 *
 * 这一屏把 Compose 真正持有的三样东西摆出来 —— [TextFieldValue.text]、
 * [TextFieldValue.composition]、[TextFieldValue.selection] —— 于是每一条感觉都能落成
 * 一个可复述的数字：预编辑时 `composition` 是不是非空、退格时它的区间有没有缩短、
 * 选词上屏后有没有回调、失焦时预编辑是被提交还是被丢掉。
 *
 * ## 它为什么是产品的一部分，而不是只在开发机上跑
 * 它做的是真事（暴露真实状态），不是占位符；用户报输入法问题时，截这一屏就能说清。
 * 但它在文案里**明说自己不是产品功能**，避免被当成输入法设置项。
 *
 * ## 三个输入框不是凑数
 * 产品里两种输入原语都在用：`OutlinedTextField`（设置页、节点页、桌面输入栏）与
 * `BasicTextField`（聊天输入栏 `ui/components/MessageComposer.kt`）。桥接层对两者的
 * 处理可能不同，所以两个都要能测；多行框是因为换行与 `singleLine` 在 IME 上走不同分支。
 *
 * ## 文案为什么全在 strings.xml
 * CJK 棘轮门禁（`tools/cjk-literal-gate.gradle.kts`）只允许字面量数下降，新增一处即失败，
 * 且不允许靠改基线放行。所以本文件**一个中文字面量都没有**，全部走资源。
 */
@Composable
public fun ImeSmokePane(
    onBack: (() -> Unit)? = null) {
    val sp = LocalAppSpacing.current
    // 三个探针各自记账，观测台只显示"当前焦点所在的"那一个：一次看一个才读得清，
    // 而三个框共用一份记账会把 A 框的上屏次数算到 B 框头上。
    val probes = remember { List(FIELD_COUNT) { ImeProbe() } }
    var activeIndex by remember { mutableStateOf(0) }
    val active = probes[activeIndex]
    val focusManager = LocalFocusManager.current

    // 失焦之后不再有回调时，观测台不能永远停在"等失焦后的值"——那是把"没有"显示成
    // "还没来"。等一小会儿就把待定期收掉，让那一行如实变成"失焦后没有回调"。
    LaunchedEffect(active.blurToken) {
        if (active.blurToken > 0 && active.blurPending) {
            delay(BLUR_SETTLE_MS)
            if (active.blurPending) active.blurPending = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(sp.lg),
    ) {
        // 原型这一页**没有 24sp 大标题**：窗口标题栏已经写着「捎话 设置」，页内第一行是
        // `.grphead`（`prototype/relay.html`：`.win .grphead{padding:12px 3px 5px;font-size:11px;
        // letter-spacing:.06em}`，本屏另加了内联 `padding-top:0`）—— 与「桌面」那一页同款。
        Text(
            stringResource(Res.string.desktop_ime_title),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.06.em),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 3.dp, end = 3.dp, bottom = 5.dp),
        )
        // `.hint`：`font-size:var(--fs-hint)`=11px、`color:var(--on-2)`、
        // `.win .hint{line-height:1.55}`，本屏内联 `margin:0 0 10px`。
        Text(
            stringResource(Res.string.desktop_ime_intro),
            style = MaterialTheme.typography.labelSmall.copy(lineHeight = 1.55.em),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = sp.md10),
        )

        ProbeCard(probe = active)

        // 原型把三个输入框收在第二张卡里，卡头 `.card>h3` 写「三个输入框（与产品里同款）」。
        // `.field{margin-bottom:12px}` 就是行距，末项没有下边距。
        PaneCard(title = stringResource(Res.string.desktop_ime_fields_head)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 13.dp, top = sp.sm, end = 13.dp, bottom = 13.dp),
                verticalArrangement = Arrangement.spacedBy(sp.md),
            ) {
                ProbeField(
                    label = stringResource(Res.string.desktop_ime_field_outlined),
                    probe = probes[0],
                    basic = false,
                    multiline = false,
                    onFocus = { activeIndex = 0 },
                )
                ProbeField(
                    label = stringResource(Res.string.desktop_ime_field_multiline),
                    probe = probes[1],
                    basic = false,
                    multiline = true,
                    onFocus = { activeIndex = 1 },
                )
                ProbeField(
                    label = stringResource(Res.string.desktop_ime_field_basic),
                    probe = probes[2],
                    basic = true,
                    multiline = false,
                    onFocus = { activeIndex = 2 },
                )
            }
        }

        // 原型页尾是 `display:flex;gap:8px;justify-content:flex-end` 的两颗 `.btn.sm`。
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = sp.md),
            horizontalArrangement = Arrangement.spacedBy(sp.sm, Alignment.End),
        ) {
            OutlinedButton(
                onClick = {
                    // 先记账再移焦点：clearFocus() 之后 onValueChange 可能同步就来了，
                    // 那时候 blurBefore 必须已经写好，否则读到的"失焦前"是失焦后的值。
                    active.armBlur()
                    focusManager.clearFocus()
                },
                // `.win .btn.sm{padding:5px 9px;font-size:11px;border-radius:9px}`
                shape = ShapeRoles.Avatar,
                contentPadding = PaddingValues(horizontal = 9.dp, vertical = 5.dp),
            ) {
                Text(
                    stringResource(Res.string.desktop_ime_blur_action),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            TextButton(
                onClick = { active.reset() },
                shape = ShapeRoles.Avatar,
                contentPadding = PaddingValues(horizontal = 9.dp, vertical = 5.dp),
            ) {
                Text(
                    stringResource(Res.string.desktop_ime_reset_action),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

private const val FIELD_COUNT = 3

/** 失焦后等多久就认定"不会再有回调了"。够短到不烦人，够长到不误判慢回调。 */
private const val BLUR_SETTLE_MS = 600L

/**
 * 一个输入框的记账。
 *
 * 刻意不是 `data class`：它装的是可变状态，需要的是"同一份被两边看到"，而不是拷贝。
 * 刻意不放在 `RelayUiState` 里：这是宿主诊断，与协议、会话、传输都无关，
 * 塞进那个大 data class 只会让每屏都多背三个字段。
 */
private class ImeProbe {
    var value by mutableStateOf(TextFieldValue(""))
    var commits by mutableStateOf(0)
    var lastCommit by mutableStateOf("")
    var blurBefore by mutableStateOf<String?>(null)
    var blurAfter by mutableStateOf<String?>(null)
    var blurPending by mutableStateOf(false)

    /** 每次"移走焦点"自增，给 `LaunchedEffect` 当键；0 表示还没按过。 */
    var blurToken by mutableStateOf(0)

    /**
     * 收下一次输入。
     *
     * 「上屏」的判据是**这次回调里文本变了、且没有预编辑区间**：拼音挂着的过程中
     * `composition` 非空，文本也在变，但那些不是上屏；选词落定的一刻 `composition`
     * 归空、文本同时变长，那才是上屏。英文直接敲也是同一形状（无预编辑、文本变化），
     * 所以这个计数器对中英都成立 —— 正是中英切换那一项要看的东西。
     */
    fun record(next: TextFieldValue) {
        val previous = value
        value = next
        if (blurPending) {
            blurAfter = next.text
            blurPending = false
        }
        if (next.composition == null && next.text != previous.text) {
            commits += 1
            lastCommit = next.text
        }
    }

    fun armBlur() {
        blurBefore = value.text
        blurAfter = null
        blurPending = true
        blurToken += 1
    }

    fun reset() {
        value = TextFieldValue("")
        commits = 0
        lastCommit = ""
        blurBefore = null
        blurAfter = null
        blurPending = false
    }
}

/**
 * 原型的一张 `.card`：卡头 `.card>h3`（win 档 `padding:11px 13px 0`、11px/600/主色）+
 * `.card .padc{padding:var(--padc)}`（win 档 `8px 13px 13px`）的内容区。
 *
 * 卡头单独抽出来，是因为两处（观测台 / 三个输入框）都用同一形状 —— 原型也是同一个 `.card>h3`。
 */
@Composable
private fun PaneCard(title: String, content: @Composable () -> Unit) {
    val sp = LocalAppSpacing.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = RelayElevations.Card,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.04.em),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 13.dp, top = 11.dp, end = 13.dp),
            )
            content()
        }
    }
}

@Composable
private fun ProbeCard(probe: ImeProbe) {
    val sp = LocalAppSpacing.current
    PaneCard(title = stringResource(Res.string.desktop_ime_probe_head)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 13.dp, top = sp.sm, end = 13.dp, bottom = 13.dp),
        ) {
            val empty = stringResource(Res.string.desktop_ime_empty)

            ProbeRow(
                label = stringResource(Res.string.desktop_ime_probe_text),
                value = probe.value.text.ifEmpty { empty },
            )

            val composition = probe.value.composition
            ProbeRow(
                label = stringResource(Res.string.desktop_ime_probe_composition),
                value = if (composition == null) {
                    stringResource(Res.string.desktop_ime_not_composing)
                } else {
                    stringResource(Res.string.desktop_ime_composing, composition.start, composition.end)
                },
            )

            ProbeRow(
                label = stringResource(Res.string.desktop_ime_probe_selection),
                value = "${probe.value.selection.start}..${probe.value.selection.end}",
            )
            ProbeRow(
                label = stringResource(Res.string.desktop_ime_probe_commits),
                value = stringResource(Res.string.desktop_ime_commits_value, probe.commits),
            )
            ProbeRow(
                label = stringResource(Res.string.desktop_ime_probe_last_commit),
                value = probe.lastCommit.ifEmpty { empty },
            )
            ProbeRow(
                label = stringResource(Res.string.desktop_ime_probe_blur),
                value = blurReadout(probe, empty),
                last = true,
            )
        }
    }
}

/**
 * 失焦那一行的三种如实说法，一种都不能省：
 * - 还没按过 → 空；
 * - 按了、回调也来了 → 前值 → 后值（值变了说明预编辑被**提交**）；
 * - 按了、回调没来 → 明说没有回调（预编辑被**丢掉**，而且是静默丢的）。
 *
 * 第三种最要紧：把它显示成"等失焦后的值"就是把"没有"说成"还没来"。
 */
@Composable
private fun blurReadout(probe: ImeProbe, empty: String): String {
    val before = probe.blurBefore ?: return empty
    val shownBefore = before.ifEmpty { empty }
    val after = probe.blurAfter
    return when {
        after != null -> stringResource(
            Res.string.desktop_ime_blur_value,
            shownBefore,
            after.ifEmpty { empty },
        )

        probe.blurPending -> stringResource(Res.string.desktop_ime_blur_pending, shownBefore)
        else -> stringResource(Res.string.desktop_ime_blur_no_callback, shownBefore)
    }
}

/**
 * 原型的一行键值 `.kv`：win 档 `padding:7px 13px`、`gap:12px`、`align-items:baseline`、
 * `font-size:12px`、`border-bottom:1px solid var(--hair)`，**最后一行没有下边线**
 * （`.kv:last-child{border-bottom:0}`）。
 *
 * `.kv .k{width:var(--kv-k)}` —— win 档 104px、`flex:none`，所以标签列是定宽而不是按比例分。
 * `.kv .v.mono{font-family:…monospace;font-size:12px}`。
 */
@Composable
private fun ProbeRow(label: String, value: String, last: Boolean = false) {
    val sp = LocalAppSpacing.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(sp.md),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(104.dp),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.weight(1f),
            )
        }
        if (!last) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/**
 * 一个探针输入框。
 *
 * 焦点变化只在 `isFocused` 时上报：失焦那一帧也要报的话，观测台会在你点走的一刻
 * 跳到**另一个**框上，而你正想读的是刚刚那个框的失焦结果。
 */
@Composable
private fun ProbeField(
    label: String,
    probe: ImeProbe,
    basic: Boolean,
    multiline: Boolean,
    onFocus: () -> Unit,
) {
    val sp = LocalAppSpacing.current
    // `.field label{font-size:12px;color:var(--on-2);margin-bottom:5px}`
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val hint = stringResource(Res.string.desktop_ime_field_hint)
        val focusModifier = Modifier.onFocusChanged { if (it.isFocused) onFocus() }

        if (basic) {
            BasicTextField(
                value = probe.value,
                onValueChange = probe::record,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(focusModifier)
                    .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.medium)
                    // `.win .inp{font-size:14px;padding:8px 11px}`（本框内联清掉了边框与底色）
                    .padding(horizontal = 11.dp, vertical = sp.sm),
                // `.inp{font-size:var(--fs-body)}` = 14px（`.win .inp` 同样是 14px），
                // 不是 `bodyLarge` 的 16sp —— 差集里那一处「只代码 16」就是这里来的。
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                singleLine = true,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (probe.value.text.isEmpty()) {
                            Text(
                                text = hint,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        innerTextField()
                    }
                },
            )
        } else {
            OutlinedTextField(
                value = probe.value,
                onValueChange = probe::record,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(focusModifier)
                    // 原型多行框是 `<textarea rows="2">`：两行 14px 正文 + `.win .inp` 的
                    // `padding:8px 11px` + 1px 边框 ≈ 52dp。
                    .then(if (multiline) Modifier.heightIn(min = 52.dp) else Modifier),
                singleLine = !multiline,
                textStyle = MaterialTheme.typography.bodyMedium,
                placeholder = {
                    Text(hint, style = MaterialTheme.typography.bodyMedium)
                },
            )
        }
    }
}
